package pl.syntaxdevteam.punisher.players

import org.bukkit.event.player.PlayerJoinEvent
import pl.syntaxdevteam.punisher.PunisherX
import java.io.File
import java.security.Key
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
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
    private val secretKey: Key = generateKey()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

    private val useDatabase = plugin.config.getString("playerCache.storage")
        ?.equals("database", ignoreCase = true) == true

    private val separator = "|"

    private val cache = ConcurrentHashMap<String, PlayerInfo>()
    private val pendingInsertions = ConcurrentLinkedQueue<PlayerInfo>()
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
            val country = geoIPHandler.getCountry(playerIP)
            val city = geoIPHandler.getCity(playerIP)
            val geoLocation = "$city, $country"
            val lastUpdated = dateFormat.format(Date())

            if (getPlayerInfo(playerName, playerUUID, playerIP) == null) {
                savePlayerInfo(playerName, playerUUID, playerIP, geoLocation, lastUpdated)
                plugin.logger.debug("Saved player info -> playerName: $playerName, playerUUID: $playerUUID, playerIP: $playerIP, geoLocation: $geoLocation, lastUpdated: $lastUpdated")
            } else {
                plugin.logger.debug("Player info already exists -> playerName: $playerName, playerUUID: $playerUUID, playerIP: $playerIP, geoLocation: $geoLocation, lastUpdated: $lastUpdated")
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
        val previous = cache.putIfAbsent(key, info)

        if (previous == null) {
            pendingInsertions.add(info)
            scheduleFlush()
        }

        plugin.logger.debug("Encrypted data saved -> ${serializePlain(info)}")
    }

    private fun generateKey(): Key {
        val keyString = System.getenv("AES_KEY") ?: "1234567890ABCDEF" // test fallback

        require(keyString.length == 16) { "AES key must be exactly 16 characters long" }
        return SecretKeySpec(keyString.toByteArray(UTF_8), "AES")
    }


    private fun encrypt(data: String): String {
        val cipher = Cipher.getInstance("AES")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        return cipher.doFinal(data.toByteArray(UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun decrypt(data: String): String {
        return try {
            val bytes = data.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
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
        return cache.values.map { it.copy() }
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
        return getAllDecryptedRecords()
            .filter { it.playerName.equals(playerName, ignoreCase = true) }
            .map { it.playerIP }
            .also { plugin.logger.debug("Found IPs for player $playerName: $it") }
    }

    fun getPlayerIPsByUUID(playerUUID: String): List<String> {
        plugin.logger.debug("Fetching all IPs for UUID: $playerUUID")
        return getAllDecryptedRecords()
            .filter { it.playerUUID.equals(playerUUID, ignoreCase = true) }
            .map { it.playerIP }
            .also { plugin.logger.debug("Found IPs for UUID $playerUUID: $it") }
    }

    fun deletePlayerInfo(playerUUID: UUID) {
        awaitCacheInitialized()
        val targetUuid = playerUUID.toString()
        var removed = false

        cache.entries.removeIf { entry ->
            val matches = entry.value.playerUUID.equals(targetUuid, ignoreCase = true)
            if (matches) {
                removed = true
            }
            matches
        }

        if (removed) {
            scheduleFlush(forceRewrite = true)
            plugin.logger.debug("Removed player info for UUID: $playerUUID")
        }
    }

    private fun searchCache(playerName: String, playerUUID: String, playerIP: String): PlayerInfo? {
        plugin.logger.debug("Searching cache")
        awaitCacheInitialized()
        val match = cache.values.firstOrNull { info ->
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
        return getAllDecryptedRecords().filter { it.playerIP.equals(targetIP, ignoreCase = true) }
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
        cache.clear()
        lines.forEach { line ->
            val decrypted = decrypt(line)
            val info = parsePlayerInfo(decrypted)
            if (info != null) {
                cache[cacheKey(info.playerUUID, info.playerIP)] = info
            }
        }
        plugin.logger.debug("Loaded ${cache.size} player cache entries into memory")
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
            val snapshot = cache.values.map { encrypt(serializePlain(it)) }
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
}
