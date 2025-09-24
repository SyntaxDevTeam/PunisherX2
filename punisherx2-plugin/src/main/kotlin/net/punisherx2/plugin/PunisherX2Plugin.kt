package net.punisherx2.plugin

import net.punisherx2.api.PunisherX2Api
import net.punisherx2.api.PunisherX2ApiProvider
import net.punisherx2.api.PunishmentRepository
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin
import java.time.Clock
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class PunisherX2Plugin : JavaPlugin() {

    private lateinit var executor: ExecutorService
    private lateinit var repository: PunishmentRepository
    private var api: PunisherX2Api? = null

    override fun onEnable() {
        executor = Executors.newVirtualThreadPerTaskExecutor()
        val clock = Clock.systemUTC()
        repository = InMemoryPunishmentRepository(executor, clock)
        val api = DefaultPunisherX2Api(
            repository = repository,
            cacheConfig = PunishmentCacheConfig.DEFAULT,
            executor = executor,
            clock = clock
        )

        PunisherX2ApiProvider.set(api)
        server.servicesManager.register(PunisherX2Api::class.java, api, this, ServicePriority.Normal)
        this.api = api
        logger.info("PunisherX2 API initialised with asynchronous in-memory backend")
    }

    override fun onDisable() {
        server.servicesManager.unregisterAll(this)
        val activeApi = PunisherX2ApiProvider.clear()
        activeApi?.close()
        api = null
        executor.shutdown()
    }
}
