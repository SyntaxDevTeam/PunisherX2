package pl.syntaxdevteam.punisher.players

import com.maxmind.geoip2.DatabaseReader
import com.maxmind.geoip2.exception.AddressNotFoundException
import org.apache.tools.tar.TarEntry
import org.apache.tools.tar.TarInputStream
import pl.syntaxdevteam.punisher.PunisherX
import pl.syntaxdevteam.punisher.common.TaskDispatcher
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

class GeoIPHandler(private val plugin: PunisherX) {

    data class GeoLocation(val city: String, val country: String) {
        fun asDisplay(): String = "$city, $country"

        companion object {
            val UNKNOWN = GeoLocation("Unknown city", "Unknown country")
        }
    }

    private val licenseKey: String? = plugin.config.getString("geoDatabase.licenseKey")?.trim()?.takeIf { it.isNotEmpty() }
    private val pluginFolder = File(plugin.dataFolder, "geodata")
    private val cityDatabaseFile = File(pluginFolder, "GeoLite2-City.mmdb")

    @Volatile
    private var databaseReader: DatabaseReader? = null

    @Volatile
    private var geoIpEnabled: Boolean = licenseKey != null

    @Volatile
    private var initializationFuture: CompletableFuture<Unit>? = if (geoIpEnabled) null else CompletableFuture.completedFuture(Unit)

    init {
        if (!geoIpEnabled) {
            plugin.logger.warning("GeoIP functionality disabled: MaxMind license key not found in config.yml")
        }
    }

    fun initializeAsync(dispatcher: TaskDispatcher): CompletableFuture<Unit> {
        if (!geoIpEnabled) {
            return initializationFuture ?: CompletableFuture.completedFuture(Unit).also { initializationFuture = it }
        }

        initializationFuture?.let { return it }

        val future = dispatcher
            .supplyAsync {
                initializeBlocking()
            }
            .handle { _, throwable ->
                if (throwable != null) {
                    disableGeoIp("GeoIP initialization failed", throwable)
                }
                Unit
            }

        initializationFuture = future
        return future
    }

    fun isEnabled(): Boolean = geoIpEnabled

    fun isReady(): Boolean = geoIpEnabled && databaseReader != null

    fun getCountry(ip: String): String? = lookup(ip).country

    fun getCity(ip: String): String? = lookup(ip).city

    fun lookup(ip: String): GeoLocation {
        if (!geoIpEnabled) {
            return GeoLocation.UNKNOWN
        }

        val reader = databaseReader ?: run {
            plugin.logger.debug("GeoIP lookup skipped for $ip: database not initialized")
            return GeoLocation.UNKNOWN
        }

        return try {
            val response = reader.city(InetAddress.getByName(ip))
            val city = response.city?.name ?: "Unknown city"
            val country = response.country?.name ?: "Unknown country"
            GeoLocation(city, country)
        } catch (ignored: AddressNotFoundException) {
            GeoLocation.UNKNOWN
        } catch (e: UnknownHostException) {
            plugin.logger.severe("Failed to resolve host for IP $ip: ${e.message} [UnknownHostException]")
            GeoLocation.UNKNOWN
        } catch (e: Exception) {
            plugin.logger.severe("Failed to get GeoIP data for $ip: ${e.message} [Exception]")
            GeoLocation.UNKNOWN
        }
    }

    private fun initializeBlocking() {
        val key = licenseKey
        if (key.isNullOrEmpty()) {
            disableGeoIp("GeoIP initialization skipped: license key not configured")
            return
        }

        if (!pluginFolder.exists() && !pluginFolder.mkdirs()) {
            throw IOException("Could not create GeoIP data directory at ${pluginFolder.absolutePath}")
        }

        plugin.logger.info("Starting GeoIP database initialization…")

        if (!cityDatabaseFile.exists()) {
            plugin.logger.info("Downloading GeoLite2-City database from MaxMind…")
            downloadAndExtractDatabase(key)
        } else {
            plugin.logger.debug("GeoIP database already exists. Skipping download.")
        }

        if (!cityDatabaseFile.exists()) {
            throw IOException("GeoIP database file missing after download attempt")
        }

        try {
            databaseReader?.close()
        } catch (ignored: Exception) {
        }

        databaseReader = DatabaseReader.Builder(cityDatabaseFile).build()
        plugin.logger.info("GeoIP database initialized successfully.")
    }

    private fun downloadAndExtractDatabase(licenseKey: String) {
        val cityUri = URI("https://download.maxmind.com/app/geoip_download?edition_id=GeoLite2-City&license_key=$licenseKey&suffix=tar.gz")
        val connection = cityUri.toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = TimeUnit.SECONDS.toMillis(10).toInt()
        connection.readTimeout = TimeUnit.SECONDS.toMillis(30).toInt()

        try {
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                throw IllegalStateException("[GeoLite2] Unauthorized access. Please check your license key.")
            }

            connection.inputStream.use { input ->
                GZIPInputStream(input).use { gzip ->
                    TarInputStream(gzip).use { tar ->
                        var entry: TarEntry? = tar.nextEntry
                        var extracted = false
                        while (entry != null) {
                            if (entry.name.endsWith(".mmdb")) {
                                plugin.logger.debug("Extracting MMDB file: ${entry.name}")
                                if (!cityDatabaseFile.parentFile.exists() && !cityDatabaseFile.parentFile.mkdirs()) {
                                    throw IOException("Failed to create directory for GeoIP database at ${cityDatabaseFile.parentFile.absolutePath}")
                                }

                                FileOutputStream(cityDatabaseFile).use { output ->
                                    tar.copyTo(output)
                                }
                                extracted = true
                                break
                            }
                            entry = tar.nextEntry
                        }

                        if (!extracted) {
                            throw IOException("GeoIP archive did not contain an MMDB file")
                        }
                    }
                }
            }

            if (cityDatabaseFile.length() == 0L) {
                throw IOException("[GeoLite2] Extracted MMDB file is empty")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun disableGeoIp(message: String, throwable: Throwable? = null) {
        if (!geoIpEnabled) {
            throwable?.let { plugin.logger.severe("$message: ${it.message}") }
            return
        }

        geoIpEnabled = false
        initializationFuture = CompletableFuture.completedFuture(Unit)

        val fullMessage = throwable?.message?.let { "$message: $it" } ?: message
        plugin.logger.severe(fullMessage)

        try {
            databaseReader?.close()
        } catch (ignored: Exception) {
        }

        databaseReader = null
    }
}
