package pl.syntaxdevteam.punisher.metrics

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.scheduler.BukkitTask
import pl.syntaxdevteam.punisher.PunisherX
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

class PerformanceMonitor(
    private val plugin: PunisherX
) : AutoCloseable {

    private data class MetricAccumulator(
        val totalNanos: LongAdder = LongAdder(),
        val invocations: LongAdder = LongAdder()
    )

    private val metrics = ConcurrentHashMap<String, MetricAccumulator>()

    @Volatile
    private var metricsEnabled = true

    @Volatile
    private var flushIntervalTicks: Long = DEFAULT_FLUSH_INTERVAL_TICKS

    private var bukkitTask: BukkitTask? = null
    private var foliaTask: ScheduledTask? = null

    init {
        refreshFromConfig()
    }

    fun record(metricName: String, durationNanos: Long) {
        if (!metricsEnabled) {
            return
        }
        val accumulator = metrics.computeIfAbsent(metricName) { MetricAccumulator() }
        accumulator.totalNanos.add(durationNanos)
        accumulator.invocations.increment()
    }

    fun refreshFromConfig() {
        metricsEnabled = plugin.config.getBoolean(
            "performance.metrics.enabled",
            plugin.config.getBoolean("stats.enabled", true)
        )

        val configured = plugin.config.getLong(
            "performance.metrics.flush-interval-ticks",
            DEFAULT_FLUSH_INTERVAL_TICKS
        )
        val normalized = if (configured > 0) configured else DEFAULT_FLUSH_INTERVAL_TICKS
        val shouldRestart = normalized != flushIntervalTicks
        flushIntervalTicks = normalized

        if (!metricsEnabled) {
            cancelFlushTask()
            clearAccumulators()
            return
        }

        if (shouldRestart || (bukkitTask == null && foliaTask == null)) {
            restartFlushTask()
        }
    }

    private fun restartFlushTask() {
        cancelFlushTask()
        if (!metricsEnabled || flushIntervalTicks <= 0) {
            return
        }

        if (plugin.server.name.contains("Folia", ignoreCase = true)) {
            foliaTask = plugin.server.globalRegionScheduler.runAtFixedRate(
                plugin,
                { flushMetrics() },
                flushIntervalTicks,
                flushIntervalTicks
            )
        } else {
            bukkitTask = plugin.server.scheduler.runTaskTimerAsynchronously(
                plugin,
                Runnable { flushMetrics() },
                flushIntervalTicks,
                flushIntervalTicks
            )
        }
    }

    private fun cancelFlushTask() {
        bukkitTask?.cancel()
        bukkitTask = null
        foliaTask?.cancel()
        foliaTask = null
    }

    private fun flushMetrics() {
        if (!metricsEnabled) {
            return
        }
        val snapshots = snapshotAndReset()
        if (snapshots.isEmpty()) {
            return
        }

        snapshots.forEach { snapshot -> logSnapshot(snapshot) }
    }

    private fun snapshotAndReset(): List<MetricSnapshot> {
        val snapshot = mutableListOf<MetricSnapshot>()
        metrics.forEach { (name, accumulator) ->
            val total = accumulator.totalNanos.sumThenReset()
            val count = accumulator.invocations.sumThenReset()
            if (count > 0) {
                snapshot += MetricSnapshot(name, total, count)
            }
        }
        return snapshot
    }

    private fun clearAccumulators() {
        metrics.values.forEach { accumulator ->
            accumulator.totalNanos.reset()
            accumulator.invocations.reset()
        }
    }

    private fun logSnapshot(snapshot: MetricSnapshot) {
        if (snapshot.invocations <= 0) {
            return
        }

        val averageMillis = snapshot.totalNanos.toDouble() / snapshot.invocations / 1_000_000.0
        val formatted = String.format(Locale.ROOT, "%.3f", averageMillis)
        plugin.logger.debug("[Performance] ${snapshot.metricName} avg=${formatted}ms calls=${snapshot.invocations}")
    }

    override fun close() {
        cancelFlushTask()
        metrics.clear()
    }

    data class MetricSnapshot(
        val metricName: String,
        val totalNanos: Long,
        val invocations: Long
    )

    companion object {
        private const val DEFAULT_FLUSH_INTERVAL_TICKS = 20L * 60L
    }
}

inline fun <T> PerformanceMonitor.measure(metricName: String, block: () -> T): T {
    val start = System.nanoTime()
    return try {
        block()
    } finally {
        val duration = System.nanoTime() - start
        record(metricName, duration)
    }
}

