package net.punisherx2.api

import java.time.Duration
import java.util.UUID

/**
 * Declarative description of a punishment that should be applied to a target player.
 */
data class PunishmentCommand(
    val targetId: UUID,
    val type: PunishmentType,
    val reason: String,
    val issuedBy: UUID,
    val duration: Duration?
)
