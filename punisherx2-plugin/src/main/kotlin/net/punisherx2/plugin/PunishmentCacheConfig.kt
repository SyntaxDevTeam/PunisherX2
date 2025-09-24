package net.punisherx2.plugin

import java.time.Duration

/**
 * Declarative configuration of the punishment caches maintained by [DefaultPunisherX2Api].
 */
data class PunishmentCacheConfig(
    val activePunishmentTtl: Duration,
    val activePunishmentRefresh: Duration,
    val historyTtl: Duration,
    val historyRefresh: Duration,
    val maximumSize: Long
) {
    companion object {
        val DEFAULT = PunishmentCacheConfig(
            activePunishmentTtl = Duration.ofMinutes(5),
            activePunishmentRefresh = Duration.ofMinutes(1),
            historyTtl = Duration.ofMinutes(5),
            historyRefresh = Duration.ofMinutes(1),
            maximumSize = 5_000
        )
    }
}
