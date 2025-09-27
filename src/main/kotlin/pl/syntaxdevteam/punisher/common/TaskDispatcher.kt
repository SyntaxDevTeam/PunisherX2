package pl.syntaxdevteam.punisher.common

import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Coordinates asynchronous work for the plugin while respecting the Bukkit
 * threading model and Folia's schedulers.
 */
interface TaskExecutor : AutoCloseable {
    fun runAsync(task: () -> Unit): CompletableFuture<Void>
    fun <T> supplyAsync(task: () -> T): CompletableFuture<T>
    fun runSync(task: () -> Unit)
}

class TaskDispatcher(private val plugin: Plugin) : TaskExecutor {

    private val asyncExecutor: ExecutorService = Executors.newFixedThreadPool(
        determinePoolSize(),
        { runnable ->
            val thread = Executors.defaultThreadFactory().newThread(runnable)
            thread.name = "PunisherX-Async-${thread.threadId()}"
            thread.isDaemon = true
            thread
        }
    )

    private val syncExecutor = Executor { runnable ->
        if (Bukkit.isPrimaryThread()) {
            runnable.run()
        } else {
            TeleportUtils.runSync(plugin, Runnable { runnable.run() })
        }
    }

    private fun determinePoolSize(): Int {
        val processors = Runtime.getRuntime().availableProcessors()
        return when {
            processors <= 2 -> 2
            processors >= 8 -> processors / 2
            else -> processors - 1
        }
    }

    override fun runAsync(task: () -> Unit): CompletableFuture<Void> {
        return CompletableFuture.runAsync(task, asyncExecutor)
    }

    override fun <T> supplyAsync(task: () -> T): CompletableFuture<T> {
        return CompletableFuture.supplyAsync(task, asyncExecutor)
    }

    override fun runSync(task: () -> Unit) {
        syncExecutor.execute(task)
    }

    override fun close() {
        asyncExecutor.shutdown()
        if (!asyncExecutor.awaitTermination(250, TimeUnit.MILLISECONDS)) {
            asyncExecutor.shutdownNow()
        }
    }
}

fun <T> CompletableFuture<T>.thenOnMainThread(dispatcher: TaskExecutor, consumer: (T) -> Unit): CompletableFuture<Void> {
    return this.thenAccept { result ->
        dispatcher.runSync { consumer(result) }
    }
}

fun CompletableFuture<*>.logOnError(logger: (Throwable) -> Unit): CompletableFuture<*> {
    return this.exceptionally { throwable ->
        logger(throwable)
        null
    }
}
