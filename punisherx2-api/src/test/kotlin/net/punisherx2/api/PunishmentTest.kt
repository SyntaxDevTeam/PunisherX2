package net.punisherx2.api

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class PunishmentTest {
    private val playerId = UUID.randomUUID()
    private val staffId = UUID.randomUUID()

    @Test
    fun `punishment without expiry remains active until revoked`() {
        val punishment = Punishment(
            id = UUID.randomUUID(),
            targetId = playerId,
            type = PunishmentType.BAN,
            reason = "Misconduct",
            issuedAt = Instant.parse("2024-01-01T00:00:00Z"),
            expiresAt = null,
            issuedBy = staffId
        )

        assertTrue(punishment.isActive(Instant.parse("2024-06-01T00:00:00Z")))
        assertTrue(punishment.isPermanent)
    }

    @Test
    fun `revoked punishment is never reported as active`() {
        val punishment = Punishment(
            id = UUID.randomUUID(),
            targetId = playerId,
            type = PunishmentType.MUTE,
            reason = "Spam",
            issuedAt = Instant.parse("2024-01-01T00:00:00Z"),
            expiresAt = Instant.parse("2024-01-02T00:00:00Z"),
            issuedBy = staffId,
            revokedAt = Instant.parse("2024-01-01T12:00:00Z"),
            revokedBy = staffId
        )

        assertFalse(punishment.isActive(Instant.parse("2024-01-01T13:00:00Z")))
    }

    @Test
    fun `punishment expires at configured instant`() {
        val punishment = Punishment(
            id = UUID.randomUUID(),
            targetId = playerId,
            type = PunishmentType.WARNING,
            reason = "Language",
            issuedAt = Instant.parse("2024-01-01T00:00:00Z"),
            expiresAt = Instant.parse("2024-01-01T01:00:00Z"),
            issuedBy = staffId
        )

        assertTrue(punishment.isActive(punishment.issuedAt.plus(30, ChronoUnit.MINUTES)))
        assertFalse(punishment.isActive(punishment.expiresAt!!.plusSeconds(1)))
    }
}
