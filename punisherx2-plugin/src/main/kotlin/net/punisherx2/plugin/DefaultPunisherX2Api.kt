package net.punisherx2.plugin

import com.github.benmanes.caffeine.cache.AsyncLoadingCache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Scheduler
import net.punisherx2.api.Punishment
import net.punisherx2.api.PunishmentAppliedEvent
import net.punisherx2.api.PunishmentCommand
import net.punisherx2.api.PunishmentEvent
import net.punisherx2.api.PunishmentRepository
import net.punisherx2.api.PunishmentRevokedEvent
import net.punisherx2.api.PunisherX2Api
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Flow
import java.util.concurrent.SubmissionPublisher

/**
 * Default implementation of [PunisherX2Api] backed by asynchronous repositories and Caffeine caches.
 */
class DefaultPunisherX2Api(
    private val repository: PunishmentRepository,
    private val cacheConfig: PunishmentCacheConfig,
    private val executor: Executor,
    private val clock: Clock
) : PunisherX2Api {

    private val activePunishmentCache: AsyncLoadingCache<UUID, Punishment?> = Caffeine.newBuilder()
        .expireAfterWrite(cacheConfig.activePunishmentTtl)
        .refreshAfterWrite(cacheConfig.activePunishmentRefresh)
        .maximumSize(cacheConfig.maximumSize)
        .scheduler(Scheduler.systemScheduler())
        .executor(executor)
        .buildAsync { playerId, _ ->
            repository.findActivePunishment(playerId)
                .thenApply { punishment -> punishment?.takeIf { it.isActive(now()) } }
        }

    private val punishmentHistoryCache: AsyncLoadingCache<UUID, List<Punishment>> = Caffeine.newBuilder()
        .expireAfterWrite(cacheConfig.historyTtl)
        .refreshAfterWrite(cacheConfig.historyRefresh)
        .maximumSize(cacheConfig.maximumSize)
        .scheduler(Scheduler.systemScheduler())
        .executor(executor)
        .buildAsync { playerId, _ ->
            repository.findPunishmentHistory(playerId)
                .thenApply { history -> history.sortedByDescending(Punishment::issuedAt) }
        }

    private val eventPublisher = SubmissionPublisher<PunishmentEvent>(executor, Flow.defaultBufferSize())

    override fun findActivePunishment(playerId: UUID): CompletableFuture<Punishment?> =
        activePunishmentCache.get(playerId)

    override fun fetchPunishmentHistory(playerId: UUID): CompletableFuture<List<Punishment>> =
        punishmentHistoryCache.get(playerId)

    override fun applyPunishment(command: PunishmentCommand): CompletableFuture<Punishment> =
        repository.savePunishment(command)
            .thenApply { punishment ->
                activePunishmentCache.put(
                    punishment.targetId,
                    CompletableFuture.completedFuture(punishment.takeIf { it.isActive(now()) })
                )
                punishmentHistoryCache.synchronous().invalidate(punishment.targetId)
                eventPublisher.submit(PunishmentAppliedEvent(punishment))
                punishment
            }

    override fun revokePunishment(
        punishmentId: UUID,
        revokedBy: UUID?,
        reason: String?
    ): CompletableFuture<Boolean> =
        repository.revokePunishment(punishmentId, revokedBy, reason)
            .thenApply { updated ->
                if (updated != null) {
                    activePunishmentCache.put(
                        updated.targetId,
                        CompletableFuture.completedFuture(updated.takeIf { it.isActive(now()) })
                    )
                    punishmentHistoryCache.synchronous().invalidate(updated.targetId)
                    eventPublisher.submit(PunishmentRevokedEvent(updated))
                    true
                } else {
                    false
                }
            }

    override fun events(): Flow.Publisher<PunishmentEvent> = eventPublisher

    private fun now(): Instant = clock.instant()

    override fun close() {
        eventPublisher.close()
    }
}
