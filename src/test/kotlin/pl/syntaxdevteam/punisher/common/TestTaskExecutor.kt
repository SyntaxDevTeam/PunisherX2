package pl.syntaxdevteam.punisher.common

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TestTaskExecutor : TaskExecutor {
    private val asyncThreadCounter = AtomicInteger()
    private val mainThreadCounter = AtomicInteger()

    private val asyncExecutor: ExecutorService = Executors.newFixedThreadPool(2, threadFactory("test-async", asyncThreadCounter))
    private val mainExecutor: ExecutorService = Executors.newSingleThreadExecutor(threadFactory("test-main", mainThreadCounter))

    override fun runAsync(task: () -> Unit): CompletableFuture<Void> {
        return CompletableFuture.runAsync(task, asyncExecutor)
    }

    override fun <T> supplyAsync(task: () -> T): CompletableFuture<T> {
        return CompletableFuture.supplyAsync(task, asyncExecutor)
    }

    override fun runSync(task: () -> Unit) {
        mainExecutor.submit(task).get()
    }

    override fun close() {
        asyncExecutor.shutdownNow()
        mainExecutor.shutdownNow()
        asyncExecutor.awaitTermination(250, TimeUnit.MILLISECONDS)
        mainExecutor.awaitTermination(250, TimeUnit.MILLISECONDS)
    }

    private fun threadFactory(prefix: String, counter: AtomicInteger): ThreadFactory {
        return ThreadFactory { runnable ->
            Thread(runnable, "$prefix-${counter.incrementAndGet()}").apply { isDaemon = true }
        }
    }
}
