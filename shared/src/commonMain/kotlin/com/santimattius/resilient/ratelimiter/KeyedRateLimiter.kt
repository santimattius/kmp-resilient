package com.santimattius.resilient.ratelimiter

import com.santimattius.resilient.circuitbreaker.SystemTimeSource
import com.santimattius.resilient.circuitbreaker.TimeSource
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * A bounded, per-key rate limiter.
 *
 * [execute] resolves its bucket from a typed [KeyedRateLimiterConfig.keyProvider]. Every key has an
 * independent [DefaultRateLimiter] quota. Buckets are removed after [KeyedRateLimiterConfig.bucketIdleTtl]
 * of inactivity or, at capacity, by least-recently-used order among inactive buckets. An active execution
 * or one waiting for a rate-limit permit keeps its bucket resident.
 *
 * The caller owns the coroutine that invokes [execute]. Cancellation is propagated and releases the
 * bucket's active-execution reservation in `finally`.
 *
 * Keys are never exposed: the limiter's telemetry callback, [snapshot] and its exceptions carry no
 * raw context and no derived key. Inside a typed policy, use [com.santimattius.resilient.composition.resilientFor]
 * with `keyedRateLimiter` rather than instantiating this class directly.
 *
 * @param C The type of the execution context from which the key is derived.
 * @param K The type of the bucket key; must have stable `equals`/`hashCode`.
 */
class KeyedRateLimiter<C, K>(
    config: KeyedRateLimiterConfig<C, K>,
) {
    private val settings = config.snapshot()
    private val mutex = Mutex()
    private val buckets = mutableMapOf<K, Bucket>()

    /**
     * Runs [block] after obtaining a permit from the bucket selected by [context].
     *
     * Key extraction happens before this limiter changes bucket state. If [KeyedRateLimiterConfig.keyProvider]
     * throws, no bucket is created and no permit is consumed.
     *
     * @param context The execution context from which the bucket key is derived.
     * @param block The suspendable operation to run after a permit is acquired.
     * @return The result of [block].
     * @throws RateLimitExceededException when the bucket's quota is exhausted and no permit is obtained
     * within [KeyedRateLimiterConfig.timeoutWhenLimited].
     * @throws KeyedRateLimiterCapacityExceededException when [KeyedRateLimiterConfig.maxBuckets] is reached
     * and no bucket can be discarded: every bucket is active, waiting, or still inside its window.
     * Buckets are never discarded before their window ends, so this limiter fails closed instead of
     * resetting a key's quota. The exception never includes a raw key.
     */
    suspend fun <T> execute(
        context: C,
        block: suspend () -> T,
    ): T = executeObserved(context, null, block)

    /**
     * Like [execute], but also invokes [observer] when this call's bucket is rate limited. The observer is
     * scoped to this call, so a limiter shared through a [KeyedRateLimiterRegistry] reports to the caller
     * that was limited and never retains a reference to any caller. It receives no key.
     */
    internal suspend fun <T> executeObserved(
        context: C,
        observer: (suspend () -> Unit)?,
        block: suspend () -> T,
    ): T {
        val key = settings.keyProvider(context)
        val bucket = acquireBucket(key)
        try {
            return bucket.limiter.executeObserved(observer, block)
        } finally {
            releaseBucket(bucket)
        }
    }

    /**
     * Returns a privacy-safe, point-in-time view of this limiter: aggregate counts only, never keys.
     *
     * The resident count is read without holding the internal lock, so it is an approximation
     * intended for health checks and dashboards.
     */
    fun snapshot(): KeyedRateLimiterSnapshot =
        KeyedRateLimiterSnapshot(
            residentBuckets = buckets.size,
            maxBuckets = settings.maxBuckets,
        )

    private suspend fun acquireBucket(key: K): Bucket =
        mutex.withLock {
            val now = settings.timeSource.currentTimeMillis()
            removeExpiredInactiveBuckets(now)

            val bucket = buckets[key] ?: createBucket(key, now)
            bucket.activeExecutions++
            bucket
        }

    private fun createBucket(
        key: K,
        now: Long,
    ): Bucket {
        if (buckets.size >= settings.maxBuckets) {
            val leastRecentlyUsed =
                buckets.values
                    .asSequence()
                    .filter { it.isEvictable(now) }
                    .minByOrNull { it.lastUsedAtMs }
                    ?: throw KeyedRateLimiterCapacityExceededException()
            buckets.entries.removeAll { it.value === leastRecentlyUsed }
        }

        return Bucket(
            limiter =
                DefaultRateLimiter(
                    config =
                        RateLimiterConfig().apply {
                            maxCalls = settings.maxCalls
                            period = settings.period
                            timeoutWhenLimited = settings.timeoutWhenLimited
                            onRateLimited = settings.onRateLimited
                        },
                    timeSource = settings.timeSource,
                ),
            lastUsedAtMs = now,
        ).also { buckets[key] = it }
    }

    private suspend fun releaseBucket(bucket: Bucket) {
        withContext(NonCancellable) {
            mutex.withLock {
                bucket.activeExecutions--
                bucket.lastUsedAtMs = settings.timeSource.currentTimeMillis()
            }
        }
    }

    private fun removeExpiredInactiveBuckets(now: Long) {
        buckets.entries.removeAll { (_, bucket) ->
            bucket.isEvictable(now) && now - bucket.lastUsedAtMs >= settings.bucketIdleTtlMs
        }
    }

    /**
     * A bucket may be discarded only when no execution uses it and its fixed window has ended, so
     * discarding it can never grant a key more than [KeyedRateLimiterConfig.maxCalls] per period.
     * The bucket's window started no later than its last use, so `period` elapsed since then is a
     * conservative proof that the window has closed.
     */
    private fun Bucket.isEvictable(now: Long): Boolean =
        activeExecutions == 0 && now - lastUsedAtMs >= settings.period.inWholeMilliseconds

    private class Bucket(
        val limiter: DefaultRateLimiter,
        var lastUsedAtMs: Long,
        var activeExecutions: Int = 0,
    )
}

/**
 * Configuration for [KeyedRateLimiter].
 *
 * [keyProvider] receives the typed execution context and returns the key for its independent quota.
 * Configure [maxBuckets] and [bucketIdleTtl] to bound key cardinality. [onRateLimited] deliberately
 * receives no key: do not add raw tenant or user keys to telemetry; use a separately approved, bounded
 * or hashed dimension if observability needs key grouping.
 */
class KeyedRateLimiterConfig<C, K> {
    /** Produces the bucket key for one [KeyedRateLimiter.execute] call. Required; must not throw for valid input. */
    lateinit var keyProvider: (C) -> K

    /**
     * Maximum number of resident buckets; must be at least 1. Defaults to 1000.
     *
     * A bucket is only discarded when it is inactive and its [period] window has ended. When the limit
     * is reached and no bucket qualifies, new keys are rejected with
     * [KeyedRateLimiterCapacityExceededException] instead of evicting a bucket and resetting its quota.
     */
    var maxBuckets: Int = 1_000

    /**
     * Idle duration after which an inactive bucket is discarded; must be greater than zero. Defaults to 5 minutes.
     *
     * A bucket is never discarded before its [period] window ends, so the effective idle time is
     * `max(bucketIdleTtl, period)`. This keeps a key from regaining quota early.
     */
    var bucketIdleTtl: Duration = 5.minutes

    /** Maximum permits available independently in each bucket per [period]; must be at least 1. Defaults to 100. */
    var maxCalls: Int = 100

    /** Fixed-window duration used by each bucket; must be greater than zero. Defaults to 1 second. */
    var period: Duration = 1.seconds

    /** Optional maximum wait for a bucket permit; null rejects immediately when its quota is exhausted. Must not be negative. */
    var timeoutWhenLimited: Duration? = null

    /**
     * Called when a bucket is rate limited, without exposing its key. Inside a typed policy, the
     * telemetry callback that emits [com.santimattius.resilient.telemetry.ResilientEvent.KeyedRateLimited]
     * runs first, then this callback.
     */
    var onRateLimited: suspend () -> Unit = { }

    /** Clock for fixed-window refills and idle expiration; inject a deterministic one in tests. */
    var timeSource: TimeSource = SystemTimeSource

    internal fun snapshot(): Settings<C, K> {
        require(::keyProvider.isInitialized) { "keyProvider must be configured" }
        require(maxBuckets >= 1) { "maxBuckets must be >= 1, got $maxBuckets" }
        require(bucketIdleTtl.inWholeMilliseconds > 0) {
            "bucketIdleTtl must be greater than zero, got $bucketIdleTtl"
        }
        require(maxCalls >= 1) { "maxCalls must be >= 1, got $maxCalls" }
        require(period.inWholeMilliseconds > 0) { "period must be greater than zero, got $period" }
        require(timeoutWhenLimited == null || timeoutWhenLimited!! >= Duration.ZERO) {
            "timeoutWhenLimited must not be negative, got $timeoutWhenLimited"
        }
        return Settings(
            keyProvider = keyProvider,
            maxBuckets = maxBuckets,
            bucketIdleTtlMs = bucketIdleTtl.inWholeMilliseconds,
            maxCalls = maxCalls,
            period = period,
            timeoutWhenLimited = timeoutWhenLimited,
            onRateLimited = onRateLimited,
            timeSource = timeSource,
        )
    }
}

/**
 * Point-in-time, privacy-safe snapshot of a [KeyedRateLimiter].
 *
 * Contains aggregate counts only; it never carries keys or execution context. Obtain it from
 * [KeyedRateLimiter.snapshot] or `TypedResilientPolicy.getKeyedRateLimiterSnapshot`.
 *
 * @property residentBuckets Number of per-key buckets currently resident.
 * @property maxBuckets Configured upper bound for resident buckets.
 */
data class KeyedRateLimiterSnapshot(
    val residentBuckets: Int,
    val maxBuckets: Int,
)

/**
 * Creates a typed [KeyedRateLimiter] from [configure].
 *
 * @param configure Configures the [KeyedRateLimiterConfig]; its `keyProvider` is required.
 * @return A standalone limiter with its own buckets (not shared through any registry).
 * @throws IllegalArgumentException if the configuration is invalid (for example a missing `keyProvider`).
 */
fun <C, K> keyedRateLimiter(configure: KeyedRateLimiterConfig<C, K>.() -> Unit): KeyedRateLimiter<C, K> =
    KeyedRateLimiter(KeyedRateLimiterConfig<C, K>().apply(configure))

/**
 * Thrown when a new key cannot get a bucket because all resident buckets are active or waiting.
 *
 * Its message intentionally contains no key material.
 */
class KeyedRateLimiterCapacityExceededException :
    Exception(
        "The keyed rate limiter is at bucket capacity and all buckets are busy.",
    )

internal data class Settings<C, K>(
    val keyProvider: (C) -> K,
    val maxBuckets: Int,
    val bucketIdleTtlMs: Long,
    val maxCalls: Int,
    val period: Duration,
    val timeoutWhenLimited: Duration?,
    val onRateLimited: suspend () -> Unit,
    val timeSource: TimeSource,
)
