package pl.syntaxdevteam.punisher.metrics

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import pl.syntaxdevteam.punisher.common.TaskExecutor
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Stores short lived performance profiles gathered before and after
 * optimisation stages.  The repository is intentionally lightweight and
 * optimised for the high churn nature of measurements created during load
 * testing sessions.
 */
class PerformanceProfileRepository(
    private val dispatcher: TaskExecutor,
    cacheTtl: Duration = Duration.ofMinutes(10),
    private val clock: Clock = Clock.systemUTC()
) {

    private val cache: Cache<CacheKey, PerformanceProfileSnapshot> = Caffeine
        .newBuilder()
        .expireAfterWrite(cacheTtl.toMillis(), TimeUnit.MILLISECONDS)
        .build()

    private val sequence = AtomicLong()

    private val sessionBuffer = Collections.synchronizedList(mutableListOf<PerformanceProfileSnapshot>())

    @Volatile
    private var sessionBufferingEnabled = false

    fun recordAsync(
        stage: String,
        captureType: CaptureType,
        tps: Double,
        commandLatencyMillis: Double,
        notes: String? = null
    ): CompletableFuture<PerformanceProfileSnapshot> {
        return dispatcher.supplyAsync {
            val snapshot = PerformanceProfileSnapshot(
                stage = stage,
                captureType = captureType,
                tps = tps,
                commandLatencyMillis = commandLatencyMillis,
                capturedAt = clock.instant(),
                notes = notes,
                sequence = sequence.incrementAndGet()
            )
            cache.put(CacheKey(stage, captureType), snapshot)
            if (sessionBufferingEnabled) {
                synchronized(sessionBuffer) {
                    sessionBuffer.add(snapshot)
                }
            }
            snapshot
        }
    }

    fun getSnapshot(stage: String, captureType: CaptureType): PerformanceProfileSnapshot? {
        return cache.getIfPresent(CacheKey(stage, captureType))
    }

    fun summarize(stage: String): PerformanceComparison? {
        val before = getSnapshot(stage, CaptureType.BEFORE) ?: return null
        val after = getSnapshot(stage, CaptureType.AFTER) ?: return null
        return PerformanceComparison(
            stage = stage,
            before = before,
            after = after,
            tpsGain = after.tps - before.tps,
            commandLatencyDelta = after.commandLatencyMillis - before.commandLatencyMillis
        )
    }

    fun cleanUp() {
        cache.cleanUp()
    }

    fun setSessionBufferingEnabled(enabled: Boolean) {
        sessionBufferingEnabled = enabled
        if (!enabled) {
            synchronized(sessionBuffer) {
                sessionBuffer.clear()
            }
        }
    }

    fun hasSessionEntries(): Boolean {
        if (!sessionBufferingEnabled) {
            return false
        }
        return synchronized(sessionBuffer) { sessionBuffer.isNotEmpty() }
    }

    fun peekSession(): List<PerformanceProfileSnapshot> {
        if (!sessionBufferingEnabled) {
            return emptyList()
        }
        return synchronized(sessionBuffer) { sessionBuffer.toList() }.sortedBy { it.sequence }
    }

    fun drainSession(): List<PerformanceProfileSnapshot> {
        if (!sessionBufferingEnabled) {
            return emptyList()
        }
        val drained = synchronized(sessionBuffer) {
            val copy = sessionBuffer.toList()
            sessionBuffer.clear()
            copy
        }
        return drained.sortedBy { it.sequence }
    }

    data class PerformanceProfileSnapshot(
        val stage: String,
        val captureType: CaptureType,
        val tps: Double,
        val commandLatencyMillis: Double,
        val capturedAt: Instant,
        val notes: String?,
        val sequence: Long
    )

    data class PerformanceComparison(
        val stage: String,
        val before: PerformanceProfileSnapshot,
        val after: PerformanceProfileSnapshot,
        val tpsGain: Double,
        val commandLatencyDelta: Double
    )

    private data class CacheKey(
        val stage: String,
        val captureType: CaptureType
    )

    enum class CaptureType {
        BEFORE,
        AFTER,
        RUNTIME
    }
}
