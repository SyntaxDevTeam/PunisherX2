package net.punisherx2.api

import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Asynchronous gateway responsible for persisting and retrieving punishment information.
 */
interface PunishmentRepository {
    fun findActivePunishment(playerId: UUID): CompletableFuture<Punishment?>

    fun findPunishmentHistory(playerId: UUID): CompletableFuture<List<Punishment>>

    fun savePunishment(command: PunishmentCommand): CompletableFuture<Punishment>

    fun revokePunishment(
        punishmentId: UUID,
        revokedBy: UUID?,
        reason: String?
    ): CompletableFuture<Punishment?>
}
