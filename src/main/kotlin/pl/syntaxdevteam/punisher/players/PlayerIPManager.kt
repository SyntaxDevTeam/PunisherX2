package pl.syntaxdevteam.punisher.players

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import org.bukkit.event.player.PlayerJoinEvent
import pl.syntaxdevteam.punisher.PunisherX
import pl.syntaxdevteam.punisher.common.thenOnMainThread
import pl.syntaxdevteam.punisher.players.GeoIPHandler.GeoLocation
import java.io.File
import java.security.Key
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.text.Charsets.UTF_8

class PlayerIPManager(private val plugin: PunisherX, val geoIPHandler: GeoIPHandler) {

    data class PlayerInfo(
        val playerName: String,
        val playerUUID: String,
        val playerIP: String,
        val geoLocation: String,
        val lastUpdated: String
    )

    private val cacheFile = File(plugin.dataFolder, "cache")
    private val keyFile = File(plugin.dataFolder, "player-cache.key")
    private val secretKey: Key = loadOrCreateKey()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

    private val useDatabase = plugin.config.getString("playerCache.storage")
        ?.equals("database", ignoreCase = true) == true

    private val separator = "|"

    private val cache: Cache<String, PlayerInfo> = Caffeine.newBuilder().build()
    private val pendingInsertions = ConcurrentLinkedQueue<PlayerInfo>()
    private val nameIndex: Cache<String, MutableSet<String>> = Caffeine.newBuilder().build()
    private val uuidIndex: Cache<String, MutableSet<String>> = Caffeine.newBuilder().build()
    private val ipIndex: Cache<String, MutableSet<String>> = Caffeine.newBuilder().build()
    private val rewriteRequested = AtomicBoolean(false)
    private val flushScheduled = AtomicBoolean(false)

    private val cacheLoadFuture: CompletableFuture<Unit>

    init {
        if (!useDatabase && !cacheFile.exists()) {
            cacheFile.parentFile.mkdirs()
            cacheFile.createNewFile()
        }

        cacheLoadFuture = plugin.taskDispatcher
            .supplyAsync {
                loadCacheFromStorage()
            }
            .exceptionally { throwable ->
                plugin.logger.err("Failed to load player cache: ${throwable.message}")
                Unit
            }
    }

    fun handlePlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        val playerName = player.name
        val playerUUID = player.uniqueId.toString()
        val playerIP = player.address?.address?.hostAddress

        if (playerIP != null) {
            awaitCacheInitialized()

            val dispatcher = plugin.taskDispatcher
            val lookupFuture = if (geoIPHandler.isEnabled()) {
                dispatcher
                    .supplyAsync { geoIPHandler.lookup(playerIP) }
                    .orTimeout(1500, TimeUnit.MILLISECONDS)
                    .exceptionally { throwable ->
                        plugin.logger.debug("GeoIP lookup failed for $playerIP: ${throwable.message}")
                        GeoLocation.UNKNOWN
                    }
            } else {
                CompletableFuture.completedFuture(GeoLocation.UNKNOWN)
            }

            lookupFuture.thenOnMainThread(dispatcher) { location ->
                val geoLocation = location.asDisplay()
                val lastUpdated = dateFormat.format(Date())

                if (getPlayerInfo(playerName, playerUUID, playerIP) == null) {
                    savePlayerInfo(playerName, playerUUID, playerIP, geoLocation, lastUpdated)
                    plugin.logger.debug("Saved player info -> playerName: $playerName, playerUUID: $playerUUID, playerIP: $playerIP, geoLocation: $geoLocation, lastUpdated: $lastUpdated")
                } else {
                    plugin.logger.debug("Player info already exists -> playerName: $playerName, playerUUID: $playerUUID, playerIP: $playerIP, geoLocation: $geoLocation, lastUpdated: $lastUpdated")
                }
            }
        }
    }

    private fun getPlayerInfo(playerName: String, playerUUID: String, playerIP: String): PlayerInfo? {
        return searchCache(playerName, playerUUID, playerIP)
    }

    private fun savePlayerInfo(playerName: String, playerUUID: String, playerIP: String, geoLocation: String, lastUpdated: String) {
        val info = PlayerInfo(
            playerName = playerName,
            playerUUID = playerUUID,
            playerIP = playerIP,
            geoLocation = geoLocation,
            lastUpdated = lastUpdated
        )

        val key = cacheKey(playerUUID, playerIP)
        val previous = cache.asMap().putIfAbsent(key, info)

        if (previous == null) {
            indexRecord(key, info)
            pendingInsertions.add(info)
            scheduleFlush()
        }

        plugin.logger.debug("Encrypted data saved -> ${serializePlain(info)}")
    }

    private fun loadOrCreateKey(): Key {
        val envKey = validatedKeyValue(System.getenv("AES_KEY")?.trim(), "environment variable AES_KEY")
        if (envKey != null) {
            return toSecretKey(envKey)
        }

        val configKey = validatedKeyValue(plugin.config.getString("playerCache.secretKey")?.trim(), "playerCache.secretKey")
        if (configKey != null) {
            persistKey(configKey)
            return toSecretKey(configKey)
        }

        val persistedKey = loadPersistedKey()
        if (persistedKey != null) {
            return toSecretKey(persistedKey)
        }

        persistKey(DEFAULT_KEY)
        return toSecretKey(DEFAULT_KEY)
    }

    private fun validatedKeyValue(rawKey: String?, source: String): String? {
        if (rawKey.isNullOrBlank()) {
            return null
        }

        val value = rawKey.takeIf { it.length == REQUIRED_KEY_LENGTH }
        if (value == null) {
            plugin.logger.err("Ignoring $source because it is not exactly $REQUIRED_KEY_LENGTH characters long")
            return null
        }

        return value
    }

    private fun loadPersistedKey(): String? {
        if (!keyFile.exists()) {
            return null
        }

        val rawKey = keyFile.readText().trim()
        val persisted = validatedKeyValue(rawKey, keyFile.name)
        if (persisted == null) {
            plugin.logger.err("Stored AES key at ${keyFile.absolutePath} is invalid and will be regenerated")
            keyFile.delete()
        }
        return persisted
    }

    private fun persistKey(secret: String) {
        if (System.getenv("AES_KEY")?.isNotBlank() == true) {
            return
        }

        keyFile.parentFile?.let { parent ->
            if (!parent.exists()) {
                parent.mkdirs()
            }
        }
        if (!keyFile.exists() || keyFile.readText() != secret) {
            keyFile.writeText(secret)
        }
    }

    private fun toSecretKey(value: String): Key = SecretKeySpec(value.toByteArray(UTF_8), "AES")

    private companion object {
        private const val REQUIRED_KEY_LENGTH = 16
        private const val DEFAULT_KEY = "1234567890ABCDEF"
    }


    private fun encrypt(data: String): String {
        val cipher = Cipher.getInstance("AES")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        return cipher.doFinal(data.toByteArray(UTF_8)).toHexString()
    }

    private fun decrypt(data: String): String {
        return try {
            val bytes = hexStringToByteArray(data)
            val cipher = Cipher.getInstance("AES")
            cipher.init(Cipher.DECRYPT_MODE, secretKey)
            String(cipher.doFinal(bytes), UTF_8)
        } catch (e: Exception) {
            plugin.logger.err("Failed to decrypt data: $data -> $e")
            ""
        }
    }

    fun getAllDecryptedRecords(): List<PlayerInfo> {
        awaitCacheInitialized()
        return cache.asMap().values.map { it.copy() }
    }

    fun getPlayerIPByName(playerName: String): String? {
        plugin.logger.debug("Fetching IP for player: $playerName")
        val info = searchCache(playerName, "", "")
        val ip = info?.playerIP
        plugin.logger.debug("Found IP for player $playerName: $ip")
        return ip
    }

    fun getPlayerIPByUUID(playerUUID: String): String? {
        plugin.logger.debug("Fetching IP for UUID: $playerUUID")
        val info = searchCache("", playerUUID, "")
        val ip = info?.playerIP
        plugin.logger.debug("Found IP for UUID $playerUUID: $ip")
        return ip
    }

    fun getPlayerIPsByName(playerName: String): List<String> {
        plugin.logger.debug("Fetching all IPs for player: $playerName")
        awaitCacheInitialized()
        val keys = nameIndex.getIfPresent(playerName.lowercase(Locale.ROOT)) ?: emptySet()
        return keys.mapNotNull { cache.asMap()[it]?.playerIP }
            .also { plugin.logger.debug("Found IPs for player $playerName: $it") }
    }

    fun getPlayerIPsByUUID(playerUUID: String): List<String> {
        plugin.logger.debug("Fetching all IPs for UUID: $playerUUID")
        awaitCacheInitialized()
        val keys = uuidIndex.getIfPresent(playerUUID.lowercase(Locale.ROOT)) ?: emptySet()
        return keys.mapNotNull { cache.asMap()[it]?.playerIP }
            .also { plugin.logger.debug("Found IPs for UUID $playerUUID: $it") }
    }

    fun deletePlayerInfo(playerUUID: UUID) {
        awaitCacheInitialized()
        val targetUuid = playerUUID.toString()
        var removed = false

        val keysToRemove = mutableListOf<Pair<String, PlayerInfo>>()
        cache.asMap().forEach { (key, info) ->
            if (info.playerUUID.equals(targetUuid, ignoreCase = true)) {
                removed = true
                keysToRemove += key to info
            }
        }

        if (keysToRemove.isNotEmpty()) {
            keysToRemove.forEach { (key, info) ->
                cache.invalidate(key)
                unregisterIndexes(key, info)
            }
        }

        if (removed) {
            scheduleFlush(forceRewrite = true)
            plugin.logger.debug("Removed player info for UUID: $playerUUID")
        }
    }

    private fun searchCache(playerName: String, playerUUID: String, playerIP: String): PlayerInfo? {
        plugin.logger.debug("Searching cache")
        awaitCacheInitialized()
        val candidateKeys = resolveCandidateKeys(playerName, playerUUID, playerIP)
        val match = candidateKeys.asSequence()
            .mapNotNull { cache.asMap()[it] }
            .firstOrNull { info ->
                (playerName.isEmpty() || info.playerName.equals(playerName, ignoreCase = true)) &&
                    (playerUUID.isEmpty() || info.playerUUID.equals(playerUUID, ignoreCase = true)) &&
                    (playerIP.isEmpty() || info.playerIP.equals(playerIP, ignoreCase = true))
            }
        return match?.also {
            plugin.logger.debug("Match found: ${serializePlain(it)}")
        } ?: run {
            plugin.logger.debug("No match found in cache")
            null
        }
    }


    fun getPlayersByIP(targetIP: String): List<PlayerInfo> {
        awaitCacheInitialized()
        val keys = ipIndex.getIfPresent(targetIP.lowercase(Locale.ROOT)) ?: return emptyList()
        return keys.mapNotNull { cache.asMap()[it]?.copy() }
    }

    private fun parsePlayerInfo(decryptedLine: String): PlayerInfo? {
        val parts = decryptedLine.split(separator).map { it.trim() }
        return if (parts.size >= 5) {
            PlayerInfo(
                playerName = parts[0],
                playerUUID = parts[1],
                playerIP = parts[2],
                geoLocation = parts[3],
                lastUpdated = parts[4]
            )
        } else {
            plugin.logger.err("Invalid record format: $decryptedLine")
            null
        }
    }

    private fun loadCacheFromStorage() {
        val lines = readLines()
        cache.invalidateAll()
        clearIndexes()
        lines.forEach { line ->
            val decrypted = decrypt(line)
            val info = parsePlayerInfo(decrypted)
            if (info != null) {
                val key = cacheKey(info.playerUUID, info.playerIP)
                cache.put(key, info)
                indexRecord(key, info)
            }
        }
        plugin.logger.debug("Loaded ${cache.asMap().size} player cache entries into memory")
    }

    private fun scheduleFlush(forceRewrite: Boolean = false) {
        if (forceRewrite) {
            rewriteRequested.set(true)
        }

        if (flushScheduled.compareAndSet(false, true)) {
            plugin.taskDispatcher.runAsync {
                try {
                    flushPendingWrites()
                } finally {
                    flushScheduled.set(false)
                    if (rewriteRequested.get() || pendingInsertions.isNotEmpty()) {
                        scheduleFlush()
                    }
                }
            }
        }
    }

    private fun flushPendingWrites() {
        if (rewriteRequested.getAndSet(false)) {
            val snapshot = cache.asMap().values.map { encrypt(serializePlain(it)) }
            pendingInsertions.clear()
            overwriteLines(snapshot)
            return
        }

        val batch = mutableListOf<String>()
        while (true) {
            val info = pendingInsertions.poll() ?: break
            batch += encrypt(serializePlain(info))
        }

        if (batch.isNotEmpty()) {
            appendLines(batch)
        }
    }

    private fun readLines(): List<String> =
        if (useDatabase) plugin.databaseHandler.getPlayerCacheLines() else cacheFile.readLines()

    private fun appendLines(encryptedData: List<String>) {
        if (useDatabase) {
            encryptedData.forEach { plugin.databaseHandler.savePlayerCacheLine(it) }
        } else if (encryptedData.isNotEmpty()) {
            val text = buildString {
                encryptedData.forEach { line ->
                    append(line)
                    append('\n')
                }
            }
            cacheFile.appendText(text)
        }
    }

    private fun overwriteLines(lines: List<String>) {
        if (useDatabase) plugin.databaseHandler.overwritePlayerCache(lines)
        else {
            cacheFile.writeText("")
            appendLines(lines)
        }
    }

    private fun cacheKey(playerUUID: String, playerIP: String): String {
        return playerUUID.lowercase(Locale.ROOT) + separator + playerIP.lowercase(Locale.ROOT)
    }

    private fun serializePlain(info: PlayerInfo): String {
        return listOf(
            info.playerName,
            info.playerUUID,
            info.playerIP,
            info.geoLocation,
            info.lastUpdated
        ).joinToString(separator)
    }

    private fun awaitCacheInitialized() {
        try {
            cacheLoadFuture.join()
        } catch (throwable: Throwable) {
            plugin.logger.err("Player cache initialization failed: ${throwable.message}")
        }
    }

    private fun resolveCandidateKeys(playerName: String, playerUUID: String, playerIP: String): Collection<String> {
        val normalizedUuid = playerUUID.takeIf { it.isNotEmpty() }?.lowercase(Locale.ROOT)
        if (normalizedUuid != null) {
            uuidIndex.getIfPresent(normalizedUuid)?.let { return it }
        }

        val normalizedIp = playerIP.takeIf { it.isNotEmpty() }?.lowercase(Locale.ROOT)
        if (normalizedIp != null) {
            ipIndex.getIfPresent(normalizedIp)?.let { return it }
        }

        val normalizedName = playerName.takeIf { it.isNotEmpty() }?.lowercase(Locale.ROOT)
        if (normalizedName != null) {
            nameIndex.getIfPresent(normalizedName)?.let { return it }
        }

        return cache.asMap().keys
    }

    private fun indexRecord(key: String, info: PlayerInfo) {
        addIndexEntry(nameIndex, info.playerName, key)
        addIndexEntry(uuidIndex, info.playerUUID, key)
        addIndexEntry(ipIndex, info.playerIP, key)
    }

    private fun unregisterIndexes(key: String, info: PlayerInfo) {
        removeIndexEntry(nameIndex, info.playerName, key)
        removeIndexEntry(uuidIndex, info.playerUUID, key)
        removeIndexEntry(ipIndex, info.playerIP, key)
    }

    private fun addIndexEntry(
        index: Cache<String, MutableSet<String>>,
        attribute: String,
        key: String
    ) {
        val normalized = attribute.lowercase(Locale.ROOT)
        index.get(normalized) { ConcurrentHashMap.newKeySet() }.add(key)
    }

    private fun removeIndexEntry(
        index: Cache<String, MutableSet<String>>,
        attribute: String,
        key: String
    ) {
        val normalized = attribute.lowercase(Locale.ROOT)
        val set = index.getIfPresent(normalized) ?: return
        set.remove(key)
        if (set.isEmpty()) {
            index.invalidate(normalized)
        }
    }

    private fun clearIndexes() {
        nameIndex.invalidateAll()
        uuidIndex.invalidateAll()
        ipIndex.invalidateAll()
    }
}
