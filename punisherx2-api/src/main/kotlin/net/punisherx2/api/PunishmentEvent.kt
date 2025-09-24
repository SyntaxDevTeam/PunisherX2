package net.punisherx2.api

import java.time.Instant

/**
 * Marker interface for domain events emitted by [PunisherX2Api.events].
 */
sealed interface PunishmentEvent {
    /**
     * Snapshot of the punishment involved in the event.
     */
    val punishment: Punishment

    /**
     * Logical timestamp associated with the event. Defaults to the punishment timestamp most relevant to the action.
     */
    val occurredAt: Instant
}

/**
 * Published whenever a new punishment is persisted and becomes visible through the API.
 */
data class PunishmentAppliedEvent(
    override val punishment: Punishment,
    override val occurredAt: Instant = punishment.issuedAt
) : PunishmentEvent

/**
 * Published after an existing punishment transitions into a revoked state.
 */
data class PunishmentRevokedEvent(
    override val punishment: Punishment,
    override val occurredAt: Instant = punishment.revokedAt
        ?: error("Revoked punishment is missing revocation timestamp")
) : PunishmentEvent
