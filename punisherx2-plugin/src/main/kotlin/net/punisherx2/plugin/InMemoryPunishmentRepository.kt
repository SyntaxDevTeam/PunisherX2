package net.punisherx2.plugin

import net.punisherx2.api.Punishment
import net.punisherx2.api.PunishmentCommand
import net.punisherx2.api.PunishmentRepository
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/**
 * Simple in-memory repository used for early development and unit tests.
 */
class InMemoryPunishmentRepository(
    private val executor: Executor,
    private val clock: Clock = Clock.systemUTC()
) : PunishmentRepository {

    private val storage = ConcurrentHashMap<UUID, MutableList<Punishment>>()
    private val punishmentIndex = ConcurrentHashMap<UUID, UUID>()

    override fun findActivePunishment(playerId: UUID): CompletableFuture<Punishment?> =
        supplyAsync {
            val now = clock.instant()
            storage[playerId]
                ?.asSequence()
                ?.sortedByDescending { it.issuedAt }
                ?.firstOrNull { it.isActive(now) }
        }

    override fun findPunishmentHistory(playerId: UUID): CompletableFuture<List<Punishment>> =
        supplyAsync {
            storage[playerId]
                ?.sortedByDescending { it.issuedAt }
                ?: emptyList()
        }

    override fun savePunishment(command: PunishmentCommand): CompletableFuture<Punishment> =
        supplyAsync {
            val issuedAt = clock.instant()
            val punishment = Punishment(
                id = UUID.randomUUID(),
                targetId = command.targetId,
                type = command.type,
                reason = command.reason,
                issuedAt = issuedAt,
                expiresAt = command.duration?.let { issuedAt.plus(it) },
                issuedBy = command.issuedBy
            )

            storage.compute(command.targetId) { _, existing ->
                val list = existing ?: mutableListOf()
                list.add(punishment)
                list
            }
            punishmentIndex[punishment.id] = command.targetId

            punishment
        }

    override fun revokePunishment(
        punishmentId: UUID,
        revokedBy: UUID?,
        reason: String?
    ): CompletableFuture<Punishment?> =
        supplyAsync {
            val targetId = punishmentIndex[punishmentId] ?: return@supplyAsync null
            var updatedPunishment: Punishment? = null

            storage.computeIfPresent(targetId) { _, list ->
                for (index in list.indices) {
                    val existing = list[index]
                    if (existing.id == punishmentId && existing.revokedAt == null) {
                        val revoked = existing.copy(
                            revokedAt = clock.instant(),
                            revokedBy = revokedBy,
                            revocationReason = reason
                        )
                        list[index] = revoked
                        updatedPunishment = revoked
                        break
                    }
                }
                list
            }

            updatedPunishment
        }

    private fun <T> supplyAsync(action: () -> T): CompletableFuture<T> =
        CompletableFuture.supplyAsync(action, executor)
}
