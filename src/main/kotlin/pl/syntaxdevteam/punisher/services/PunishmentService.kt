package pl.syntaxdevteam.punisher.services

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.scheduler.BukkitTask
import pl.syntaxdevteam.punisher.PunisherX
import pl.syntaxdevteam.punisher.common.TaskDispatcher
import pl.syntaxdevteam.punisher.common.logOnError
import pl.syntaxdevteam.punisher.databases.DatabaseHandler
import pl.syntaxdevteam.punisher.databases.PunishmentData
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

class PunishmentService(
    private val plugin: PunisherX,
    private val databaseHandler: DatabaseHandler,
    private val dispatcher: TaskDispatcher
) {

    private data class CacheEntry<T>(val value: T, val expiresAt: Long)

    private enum class ListingType {
        HISTORY,
        BAN_ACTIVE,
        BAN_HISTORY
    }

    private data class ListingCacheKey(
        val type: ListingType,
        val identifier: String?,
        val limit: Int,
        val offset: Int
    )

    private val activePunishmentsCache = ConcurrentHashMap<UUID, CacheEntry<List<PunishmentData>>>()
    private val listingCache = ConcurrentHashMap<ListingCacheKey, CacheEntry<List<PunishmentData>>>()

    @Volatile
    private var activePunishmentTtl = readCacheTtl()
    @Volatile
    private var listingCacheTtl = readListingCacheTtl()
    @Volatile
    private var cleanupIntervalTicks = readCleanupIntervalTicks()

    private var cleanupTask: BukkitTask? = null
    private var foliaCleanupTask: ScheduledTask? = null

    fun warmup(uuid: UUID): CompletableFuture<List<PunishmentData>> {
        return getActivePunishments(uuid, forceRefresh = true)
    }

    fun getActivePunishments(uuid: UUID, forceRefresh: Boolean = false): CompletableFuture<List<PunishmentData>> {
        val cached = activePunishmentsCache[uuid]
        val now = System.currentTimeMillis()
        if (!forceRefresh && cached != null && cached.expiresAt >= now) {
            return CompletableFuture.completedFuture(cached.value)
        }

        return dispatcher.supplyAsync {
            val punishments = databaseHandler.getPunishments(uuid.toString())
                .filter { plugin.punishmentManager.isPunishmentActive(it) }
            activePunishmentsCache[uuid] = CacheEntry(punishments, System.currentTimeMillis() + activePunishmentTtl)
            punishments
        }
    }

    fun getPunishmentHistory(
        uuid: UUID,
        limit: Int,
        offset: Int,
        forceRefresh: Boolean = false
    ): CompletableFuture<List<PunishmentData>> {
        val key = ListingCacheKey(ListingType.HISTORY, uuid.toString(), limit, offset)
        return getListing(key, forceRefresh) {
            databaseHandler.getPunishmentHistory(uuid.toString(), limit, offset).toList()
        }
    }

    fun getBanList(
        historyMode: Boolean,
        limit: Int,
        offset: Int,
        forceRefresh: Boolean = false
    ): CompletableFuture<List<PunishmentData>> {
        val type = if (historyMode) ListingType.BAN_HISTORY else ListingType.BAN_ACTIVE
        val key = ListingCacheKey(type, null, limit, offset)
        return getListing(key, forceRefresh) {
            val punishments = if (historyMode) {
                databaseHandler.getHistoryBannedPlayers(limit, offset)
            } else {
                databaseHandler.getBannedPlayers(limit, offset)
            }
            punishments.toList()
        }
    }

    fun getCachedActivePunishments(uuid: UUID): List<PunishmentData>? {
        val now = System.currentTimeMillis()
        val cached = activePunishmentsCache[uuid] ?: return null
        return if (cached.expiresAt >= now) cached.value else null
    }

    fun invalidate(uuid: UUID) {
        activePunishmentsCache.remove(uuid)
    }

    fun invalidateAll() {
        activePunishmentsCache.clear()
        listingCache.clear()
    }

    fun removePunishment(identifier: String, type: String, removeAll: Boolean): CompletableFuture<Void> {
        return dispatcher.runAsync {
            databaseHandler.removePunishment(identifier, type, removeAll)
            runCatching { UUID.fromString(identifier) }.getOrNull()?.let { activePunishmentsCache.remove(it) }
            listingCache.clear()
        }
    }

    fun refreshConfiguration() {
        val newActiveTtl = readCacheTtl()
        if (newActiveTtl != activePunishmentTtl) {
            activePunishmentTtl = newActiveTtl
            activePunishmentsCache.clear()
        }

        val newListingTtl = readListingCacheTtl()
        if (newListingTtl != listingCacheTtl) {
            listingCacheTtl = newListingTtl
            listingCache.clear()
        }

        val newCleanupInterval = readCleanupIntervalTicks()
        if (newCleanupInterval != cleanupIntervalTicks) {
            cleanupIntervalTicks = newCleanupInterval
        }

        restartCleanupTask()
    }

    fun shutdown() {
        stopCleanupTask()
    }

    private fun readCacheTtl(): Long {
        val configured = plugin.config.getLong("performance.cache.active-punishments-ms")
        return if (configured > 0) configured else 5000L
    }

    private fun readListingCacheTtl(): Long {
        val configured = plugin.config.getLong("performance.cache.listings-ms")
        return if (configured > 0) configured else 2000L
    }

    private fun readCleanupIntervalTicks(): Long {
        val configured = plugin.config.getLong("performance.cleanup.expired-punishments-interval-ticks")
        return if (configured > 0) configured else 20L * 60L
    }

    private fun getListing(
        key: ListingCacheKey,
        forceRefresh: Boolean,
        loader: () -> List<PunishmentData>
    ): CompletableFuture<List<PunishmentData>> {
        val cached = listingCache[key]
        val now = System.currentTimeMillis()
        if (!forceRefresh && cached != null && cached.expiresAt >= now) {
            return CompletableFuture.completedFuture(cached.value)
        }

        return dispatcher.supplyAsync {
            val snapshot = loader().toList()
            listingCache[key] = CacheEntry(snapshot, System.currentTimeMillis() + listingCacheTtl)
            snapshot
        }
    }

    private fun restartCleanupTask() {
        stopCleanupTask()
        if (cleanupIntervalTicks <= 0) {
            return
        }

        if (plugin.server.name.contains("Folia", ignoreCase = true)) {
            foliaCleanupTask = plugin.server.globalRegionScheduler.runAtFixedRate(
                plugin,
                { cleanupExpiredPunishments() },
                cleanupIntervalTicks,
                cleanupIntervalTicks
            )
        } else {
            cleanupTask = plugin.server.scheduler.runTaskTimerAsynchronously(
                plugin,
                Runnable { cleanupExpiredPunishments() },
                cleanupIntervalTicks,
                cleanupIntervalTicks
            )
        }
    }

    private fun stopCleanupTask() {
        cleanupTask?.cancel()
        cleanupTask = null
        foliaCleanupTask?.cancel()
        foliaCleanupTask = null
    }

    private fun cleanupExpiredPunishments() {
        dispatcher.runAsync {
            databaseHandler.purgeExpiredPunishments(System.currentTimeMillis())
        }.logOnError { throwable ->
            plugin.logger.warning("Failed to purge expired punishments: ${throwable.message}")
        }
    }
}
