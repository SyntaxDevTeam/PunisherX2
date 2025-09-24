package net.punisherx2.api

import java.util.concurrent.atomic.AtomicReference

/**
 * Singleton registry used by the plugin runtime to expose the active [PunisherX2Api] implementation to external consumers.
 */
object PunisherX2ApiProvider {
    private val reference = AtomicReference<PunisherX2Api?>()

    fun get(): PunisherX2Api =
        reference.get() ?: error("PunisherX2 API has not been initialised yet.")

    fun set(api: PunisherX2Api) {
        if (!reference.compareAndSet(null, api)) {
            error("PunisherX2 API has already been initialised.")
        }
    }

    fun clear(): PunisherX2Api? = reference.getAndSet(null)
}
