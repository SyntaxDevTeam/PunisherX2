package net.punisherx2.plugin

import net.punisherx2.api.PunishmentAppliedEvent
import net.punisherx2.api.PunishmentCommand
import net.punisherx2.api.PunishmentEvent
import net.punisherx2.api.PunishmentRevokedEvent
import net.punisherx2.api.PunishmentType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Flow
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class DefaultPunisherX2ApiTest {

    private lateinit var clock: Clock
    private lateinit var executor: ExecutorService
    private lateinit var repository: InMemoryPunishmentRepository
    private lateinit var api: DefaultPunisherX2Api

    @BeforeEach
    fun setUp() {
        clock = IncrementingClock(Instant.parse("2024-01-01T00:00:00Z"))
        executor = Executors.newFixedThreadPool(4)
        repository = InMemoryPunishmentRepository(executor, clock)
        api = DefaultPunisherX2Api(
            repository = repository,
            cacheConfig = PunishmentCacheConfig.DEFAULT,
            executor = executor,
            clock = clock
        )
    }

    @AfterEach
    fun tearDown() {
        api.close()
        executor.shutdownNow()
    }

    @Test
    fun `active punishment is cached and invalidated after revocation`() {
        val targetId = UUID.randomUUID()
        val staffId = UUID.randomUUID()

        val command = PunishmentCommand(
            targetId = targetId,
            type = PunishmentType.BAN,
            reason = "Griefing",
            issuedBy = staffId,
            duration = Duration.ofHours(1)
        )

        val created = api.applyPunishment(command).join()
        assertEquals(targetId, created.targetId)

        val cached = api.findActivePunishment(targetId).join()
        assertNotNull(cached)
        assertEquals(created.id, cached!!.id)

        val revoked = api.revokePunishment(created.id, staffId, "Appealed").join()
        assertTrue(revoked)

        val afterRevocation = api.findActivePunishment(targetId).join()
        assertNull(afterRevocation)
    }

    @Test
    fun `history cache returns chronological records`() {
        val targetId = UUID.randomUUID()
        val staffId = UUID.randomUUID()

        val tempBan = PunishmentCommand(
            targetId = targetId,
            type = PunishmentType.BAN,
            reason = "Exploiting",
            issuedBy = staffId,
            duration = Duration.ofMinutes(30)
        )
        val warning = PunishmentCommand(
            targetId = targetId,
            type = PunishmentType.WARNING,
            reason = "Disrespect",
            issuedBy = staffId,
            duration = null
        )

        api.applyPunishment(tempBan).join()
        api.applyPunishment(warning).join()

        val history = api.fetchPunishmentHistory(targetId).join()
        assertEquals(2, history.size)
        assertEquals(PunishmentType.WARNING, history[0].type)
        assertEquals(PunishmentType.BAN, history[1].type)
    }

    @Test
    fun `events stream emits applied punishments`() {
        val subscriber = TestSubscriber(expectedEvents = 1)
        api.events().subscribe(subscriber)

        val targetId = UUID.randomUUID()
        val staffId = UUID.randomUUID()

        val command = PunishmentCommand(
            targetId = targetId,
            type = PunishmentType.MUTE,
            reason = "Spam",
            issuedBy = staffId,
            duration = Duration.ofMinutes(15)
        )

        val created = api.applyPunishment(command).join()

        val events = subscriber.await()
        assertEquals(1, events.size)
        val event = events.first()
        assertTrue(event is PunishmentAppliedEvent)
        assertEquals(created.id, event.punishment.id)
    }

    @Test
    fun `events stream emits revocations`() {
        val subscriber = TestSubscriber(expectedEvents = 2)
        api.events().subscribe(subscriber)

        val targetId = UUID.randomUUID()
        val staffId = UUID.randomUUID()

        val command = PunishmentCommand(
            targetId = targetId,
            type = PunishmentType.BAN,
            reason = "Cheating",
            issuedBy = staffId,
            duration = Duration.ofHours(2)
        )

        val created = api.applyPunishment(command).join()
        api.revokePunishment(created.id, staffId, "Apologised").join()

        val events = subscriber.await()
        assertEquals(2, events.size)
        val revoked = events.last()
        assertTrue(revoked is PunishmentRevokedEvent)
        assertEquals(created.id, revoked.punishment.id)
    }

    private class IncrementingClock(start: Instant) : Clock() {
        private val zone: ZoneId = ZoneOffset.UTC
        private val counter = AtomicLong(0)
        private val origin = start

        override fun withZone(zone: ZoneId): Clock = this

        override fun getZone(): ZoneId = zone

        override fun instant(): Instant = origin.plusMillis(counter.getAndIncrement())
    }

    private class TestSubscriber(private val expectedEvents: Int) : Flow.Subscriber<PunishmentEvent> {
        private val received = LinkedBlockingQueue<PunishmentEvent>()
        private var subscription: Flow.Subscription? = null

        override fun onSubscribe(subscription: Flow.Subscription) {
            this.subscription = subscription
            subscription.request(Long.MAX_VALUE)
        }

        override fun onNext(item: PunishmentEvent) {
            received.add(item)
        }

        override fun onError(throwable: Throwable) {
            throw AssertionError("Unexpected error from event stream", throwable)
        }

        override fun onComplete() {
            // no-op
        }

        fun await(timeout: Duration = Duration.ofSeconds(2)): List<PunishmentEvent> {
            subscription ?: error("Subscriber not registered")
            val events = mutableListOf<PunishmentEvent>()
            repeat(expectedEvents) {
                val event = received.poll(timeout.toMillis(), TimeUnit.MILLISECONDS)
                requireNotNull(event) { "Timed out waiting for punishment events" }
                events += event
            }
            return events
        }
    }
}
