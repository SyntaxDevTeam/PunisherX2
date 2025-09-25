package pl.syntaxdevteam.punisher.services

import pl.syntaxdevteam.punisher.PunisherX
import pl.syntaxdevteam.punisher.common.TaskDispatcher
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

    private val activePunishmentsCache = ConcurrentHashMap<UUID, CacheEntry<List<PunishmentData>>>()

    @Volatile
    private var activePunishmentTtl = readCacheTtl()

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
    }

    fun removePunishment(identifier: String, type: String, removeAll: Boolean): CompletableFuture<Void> {
        return dispatcher.runAsync {
            databaseHandler.removePunishment(identifier, type, removeAll)
            runCatching { UUID.fromString(identifier) }.getOrNull()?.let { activePunishmentsCache.remove(it) }
        }
    }

    fun refreshConfiguration() {
        val newValue = readCacheTtl()
        if (newValue != activePunishmentTtl) {
            activePunishmentTtl = newValue
            activePunishmentsCache.clear()
        }
    }

    private fun readCacheTtl(): Long {
        val configured = plugin.config.getLong("performance.cache.active-punishments-ms")
        return if (configured > 0) configured else 5000L
    }
}
