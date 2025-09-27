package pl.syntaxdevteam.punisher.metrics

import be.seeseemelk.mockbukkit.MockBukkit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.bukkit.plugin.java.JavaPlugin
import pl.syntaxdevteam.punisher.common.TaskDispatcher
import java.time.Duration
import java.util.concurrent.TimeUnit

class PerformanceProfileRepositoryTest {

    private lateinit var plugin: JavaPlugin
    private lateinit var dispatcher: TaskDispatcher
    private lateinit var repository: PerformanceProfileRepository

    @BeforeTest
    fun setUp() {
        MockBukkit.mock()
        plugin = MockBukkit.createMockPlugin()
        dispatcher = TaskDispatcher(plugin)
        repository = PerformanceProfileRepository(dispatcher, Duration.ofMillis(50))
    }

    @AfterTest
    fun tearDown() {
        dispatcher.close()
        MockBukkit.unmock()
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
}
