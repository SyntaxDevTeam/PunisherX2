package net.punisherx2.api

import java.time.Instant
import java.util.UUID

/**
 * Immutable snapshot of a punishment applied to a player.
 */
data class Punishment(
    val id: UUID,
    val targetId: UUID,
    val type: PunishmentType,
    val reason: String,
    val issuedAt: Instant,
    val expiresAt: Instant?,
    val issuedBy: UUID,
    val revokedAt: Instant? = null,
    val revokedBy: UUID? = null,
    val revocationReason: String? = null
) {
    /**
     * Returns `true` when the punishment is still in effect for the provided [now] instant.
     */
    fun isActive(now: Instant = Instant.now()): Boolean {
        if (revokedAt != null) {
            return false
        }

        return expiresAt?.isAfter(now) ?: true
    }

    /**
     * Indicates whether the punishment has no scheduled expiry.
     */
    val isPermanent: Boolean
        get() = expiresAt == null
}
