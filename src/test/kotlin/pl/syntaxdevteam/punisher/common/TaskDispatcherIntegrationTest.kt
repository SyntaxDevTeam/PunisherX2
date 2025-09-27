package pl.syntaxdevteam.punisher.common

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class TaskDispatcherIntegrationTest {

    private lateinit var dispatcher: TestTaskExecutor

    @BeforeTest
    fun setUp() {
        dispatcher = TestTaskExecutor()
    }

    @AfterTest
    fun tearDown() {
        dispatcher.close()
    }

    @Test
    fun `thenOnMainThread delivers callback on dedicated sync executor`() {
        val asyncThreadName = AtomicReference<String>()
        val mainThreadName = AtomicReference<String>()
        val latch = CountDownLatch(1)

        dispatcher
            .supplyAsync {
                asyncThreadName.set(Thread.currentThread().name)
                "payload"
            }
            .thenOnMainThread(dispatcher) { result ->
                assertEquals("payload", result)
                mainThreadName.set(Thread.currentThread().name)
                latch.countDown()
            }

        assertTrue(latch.await(1, TimeUnit.SECONDS), "Main thread callback was not executed")
        assertTrue(asyncThreadName.get()?.startsWith("test-async") == true, "Expected async executor thread name")
        assertTrue(mainThreadName.get()?.startsWith("test-main") == true, "Expected sync executor thread name")
    }
}
