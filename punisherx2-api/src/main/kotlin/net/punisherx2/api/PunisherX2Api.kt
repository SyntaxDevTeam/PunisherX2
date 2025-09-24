package net.punisherx2.api

import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Flow

/**
 * Primary entry point to the asynchronous moderation capabilities of PunisherX2.
 */
interface PunisherX2Api : AutoCloseable {
    /**
     * Fetches the active punishment for the given [playerId]. Missing values are represented as `null`.
     */
    fun findActivePunishment(playerId: UUID): CompletableFuture<Punishment?>

    /**
     * Returns the chronological history of punishments applied to [playerId], including revoked entries.
     */
    fun fetchPunishmentHistory(playerId: UUID): CompletableFuture<List<Punishment>>

    /**
     * Applies a new punishment defined by [command].
     */
    fun applyPunishment(command: PunishmentCommand): CompletableFuture<Punishment>

    /**
     * Revokes the punishment identified by [punishmentId]. Returns `true` when the punishment existed and transitioned to
     * a revoked state.
     */
    fun revokePunishment(
        punishmentId: UUID,
        revokedBy: UUID?,
        reason: String?
    ): CompletableFuture<Boolean>

    /**
     * Asynchronous stream of domain events emitted whenever punishments are created or revoked.
     */
    fun events(): Flow.Publisher<PunishmentEvent>

    override fun close() {
        // Default implementation is a no-op; concrete implementations may override.
    }
}
