package pl.syntaxdevteam.punisher.common

import be.seeseemelk.mockbukkit.MockBukkit
import be.seeseemelk.mockbukkit.scheduler.BukkitSchedulerMock
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TaskDispatcherIntegrationTest {

    private lateinit var plugin: JavaPlugin
    private lateinit var dispatcher: TaskDispatcher

    @BeforeTest
    fun setUp() {
        MockBukkit.mock()
        plugin = MockBukkit.createMockPlugin()
        dispatcher = TaskDispatcher(plugin)
    }

    @AfterTest
    fun tearDown() {
        dispatcher.close()
        MockBukkit.unmock()
    }

    @Test
    fun `thenOnMainThread delivers callback on primary thread`() {
        val workerThread = mutableSetOf<String>()
        val latch = CountDownLatch(1)

        val future = dispatcher.supplyAsync {
            workerThread += Thread.currentThread().name
            "payload"
        }

        future.thenOnMainThread(dispatcher) { result ->
            assertEquals("payload", result)
            assertTrue(Bukkit.isPrimaryThread(), "Callback should run on the mocked main thread")
            latch.countDown()
        }

        future.join()
        (Bukkit.getScheduler() as BukkitSchedulerMock).performOneTick()

        assertTrue(latch.await(1, TimeUnit.SECONDS), "Main thread callback was not executed")
        assertTrue(workerThread.any { it.contains("PunisherX-Async") })
    }
}
