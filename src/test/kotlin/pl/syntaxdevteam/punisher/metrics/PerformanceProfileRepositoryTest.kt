package pl.syntaxdevteam.punisher.metrics

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import pl.syntaxdevteam.punisher.common.TestTaskExecutor
import java.time.Duration
import java.util.concurrent.TimeUnit

class PerformanceProfileRepositoryTest {

    private lateinit var dispatcher: TestTaskExecutor
    private lateinit var repository: PerformanceProfileRepository

    @BeforeTest
    fun setUp() {
        dispatcher = TestTaskExecutor()
        repository = PerformanceProfileRepository(dispatcher, Duration.ofMillis(50))
    }

    @AfterTest
    fun tearDown() {
        dispatcher.close()
    }

    @Test
    fun `recordAsync stores snapshot for stage`() {
        val snapshot = repository
            .recordAsync("stage-1", PerformanceProfileRepository.CaptureType.BEFORE, 19.7, 72.0)
            .get(1, TimeUnit.SECONDS)

        assertEquals("stage-1", snapshot.stage)
        assertEquals(PerformanceProfileRepository.CaptureType.BEFORE, snapshot.captureType)
        assertEquals(19.7, snapshot.tps)
        assertEquals(72.0, snapshot.commandLatencyMillis)
        assertNotNull(repository.getSnapshot("stage-1", PerformanceProfileRepository.CaptureType.BEFORE))
    }

    @Test
    fun `summarize returns delta between before and after`() {
        repository.recordAsync("stage-2", PerformanceProfileRepository.CaptureType.BEFORE, 19.0, 110.0)
            .get(1, TimeUnit.SECONDS)
        repository.recordAsync("stage-2", PerformanceProfileRepository.CaptureType.AFTER, 20.0, 60.0)
            .get(1, TimeUnit.SECONDS)

        val summary = repository.summarize("stage-2")
        assertNotNull(summary)
        assertEquals(1.0, summary.tpsGain)
        assertEquals(-50.0, summary.commandLatencyDelta)
    }

    @Test
    fun `snapshots expire after ttl`() {
        repository.recordAsync("stage-3", PerformanceProfileRepository.CaptureType.BEFORE, 20.0, 40.0)
            .get(1, TimeUnit.SECONDS)

        Thread.sleep(120)
        repository.cleanUp()

        assertNull(repository.getSnapshot("stage-3", PerformanceProfileRepository.CaptureType.BEFORE))
    }

    @Test
    fun `session buffer collects snapshots only when enabled`() {
        repository.recordAsync("stage-4", PerformanceProfileRepository.CaptureType.RUNTIME, 19.9, 41.0)
            .get(1, TimeUnit.SECONDS)

        assertFalse(repository.hasSessionEntries())

        repository.setSessionBufferingEnabled(true)

        repository.recordAsync("stage-4", PerformanceProfileRepository.CaptureType.RUNTIME, 20.0, 39.0)
            .get(1, TimeUnit.SECONDS)
        repository.recordAsync("stage-4", PerformanceProfileRepository.CaptureType.RUNTIME, 20.0, 38.5, "invocations=3")
            .get(1, TimeUnit.SECONDS)

        assertTrue(repository.hasSessionEntries())
        val snapshots = repository.peekSession()
        assertEquals(2, snapshots.size)
        assertEquals("stage-4", snapshots.first().stage)
        assertEquals("invocations=3", snapshots.last().notes)

        val drained = repository.drainSession()
        assertEquals(2, drained.size)
        assertFalse(repository.hasSessionEntries())
    }

    @Test
    fun `disabling session buffer clears collected snapshots`() {
        repository.setSessionBufferingEnabled(true)

        repository.recordAsync("stage-5", PerformanceProfileRepository.CaptureType.BEFORE, 19.5, 44.0)
            .get(1, TimeUnit.SECONDS)

        assertTrue(repository.hasSessionEntries())

        repository.setSessionBufferingEnabled(false)

        assertFalse(repository.hasSessionEntries())
        assertTrue(repository.peekSession().isEmpty())
    }
}
