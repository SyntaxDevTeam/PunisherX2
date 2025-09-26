package pl.syntaxdevteam.punisher.services

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.scheduler.BukkitTask
import pl.syntaxdevteam.punisher.PunisherX
import pl.syntaxdevteam.punisher.common.TaskDispatcher
import pl.syntaxdevteam.punisher.common.logOnError
import pl.syntaxdevteam.punisher.databases.DatabaseHandler
import pl.syntaxdevteam.punisher.databases.PunishmentData
import pl.syntaxdevteam.punisher.metrics.measure
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class PunishmentService(
    private val plugin: PunisherX,
    private val databaseHandler: DatabaseHandler,
    private val dispatcher: TaskDispatcher
) {

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

    @Volatile
    private var activePunishmentTtl = readCacheTtl()
    @Volatile
    private var listingCacheTtl = readListingCacheTtl()
    @Volatile
    private var cleanupIntervalTicks = readCleanupIntervalTicks()

    @Volatile
    private var activePunishmentsCache: Cache<UUID, List<PunishmentData>> =
        createActivePunishmentsCache(activePunishmentTtl)
    @Volatile
    private var listingCache: Cache<ListingCacheKey, List<PunishmentData>> =
        createListingCache(listingCacheTtl)

    private var cleanupTask: BukkitTask? = null
    private var foliaCleanupTask: ScheduledTask? = null

    fun warmup(uuid: UUID): CompletableFuture<List<PunishmentData>> {
        return getActivePunishments(uuid, forceRefresh = true)
    }

    fun getActivePunishments(uuid: UUID, forceRefresh: Boolean = false): CompletableFuture<List<PunishmentData>> {
        val cached = activePunishmentsCache.getIfPresent(uuid)
        if (!forceRefresh && cached != null) {
            return CompletableFuture.completedFuture(cached)
        }

        return dispatcher.supplyAsync {
            plugin.performanceMonitor.measure("punishments.active.fetch") {
                val punishments = databaseHandler.getPunishments(uuid.toString())
                    .filter { plugin.punishmentManager.isPunishmentActive(it) }
                activePunishmentsCache.put(uuid, punishments)
                punishments
            }
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

    fun getCachedActivePunishments(uuid: UUID): List<PunishmentData>? =
        activePunishmentsCache.getIfPresent(uuid)

    fun invalidate(uuid: UUID) {
        activePunishmentsCache.invalidate(uuid)
    }

    fun invalidateAll() {
        activePunishmentsCache.invalidateAll()
        listingCache.invalidateAll()
    }

    fun removePunishment(identifier: String, type: String, removeAll: Boolean): CompletableFuture<Void> {
        return dispatcher.runAsync {
            databaseHandler.removePunishment(identifier, type, removeAll)
            runCatching { UUID.fromString(identifier) }.getOrNull()?.let { activePunishmentsCache.invalidate(it) }
            listingCache.invalidateAll()
        }
    }

    fun refreshConfiguration() {
        val newActiveTtl = readCacheTtl()
        if (newActiveTtl != activePunishmentTtl) {
            activePunishmentTtl = newActiveTtl
            activePunishmentsCache = createActivePunishmentsCache(activePunishmentTtl)
        }

        val newListingTtl = readListingCacheTtl()
        if (newListingTtl != listingCacheTtl) {
            listingCacheTtl = newListingTtl
            listingCache = createListingCache(listingCacheTtl)
        }

        val newCleanupInterval = readCleanupIntervalTicks()
        if (newCleanupInterval != cleanupIntervalTicks) {
            cleanupIntervalTicks = newCleanupInterval
        }

        restartCleanupTask()
        plugin.performanceMonitor.refreshFromConfig()
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
        val cached = listingCache.getIfPresent(key)
        if (!forceRefresh && cached != null) {
            return CompletableFuture.completedFuture(cached)
        }

        val metricName = when (key.type) {
            ListingType.HISTORY -> "punishments.history.list.fetch"
            ListingType.BAN_ACTIVE -> "punishments.banlist.active.fetch"
            ListingType.BAN_HISTORY -> "punishments.banlist.history.fetch"
        }

        return dispatcher.supplyAsync {
            val loaded = plugin.performanceMonitor.measure(metricName) { loader() }
            val snapshot = loaded.toList()
            listingCache.put(key, snapshot)
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
            plugin.performanceMonitor.measure("punishments.cleanup.expired") {
                databaseHandler.purgeExpiredPunishments(System.currentTimeMillis())
            }
        }.logOnError { throwable ->
            plugin.logger.warning("Failed to purge expired punishments: ${throwable.message}")
        }
    }

    private fun createActivePunishmentsCache(ttlMillis: Long): Cache<UUID, List<PunishmentData>> =
        Caffeine.newBuilder()
            .expireAfterWrite(ttlMillis, TimeUnit.MILLISECONDS)
            .build()

    private fun createListingCache(ttlMillis: Long): Cache<ListingCacheKey, List<PunishmentData>> =
        Caffeine.newBuilder()
            .expireAfterWrite(ttlMillis, TimeUnit.MILLISECONDS)
            .build()
}
