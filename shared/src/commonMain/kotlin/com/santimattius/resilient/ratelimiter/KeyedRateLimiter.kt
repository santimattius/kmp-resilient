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
     * @throws KeyedRateLimiterCapacityExceededException when [KeyedRateLimiterConfig.maxBuckets] is reached
     * and every bucket is active or waiting. The exception never includes a raw key.
     */
    suspend fun <T> execute(
        context: C,
        block: suspend () -> T,
    ): T {
        val key = settings.keyProvider(context)
        val bucket = acquireBucket(key)
        try {
            return bucket.limiter.execute(block)
        } finally {
            releaseBucket(bucket)
        }
    }

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
                    .filter { it.activeExecutions == 0 }
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
            bucket.activeExecutions == 0 && now - bucket.lastUsedAtMs >= settings.bucketIdleTtlMs
        }
    }

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
    /** Produces the bucket key for one [KeyedRateLimiter.execute] call. */
    lateinit var keyProvider: (C) -> K

    /** Maximum number of resident buckets. */
    var maxBuckets: Int = 1_000

    /** Idle duration after which an inactive bucket is discarded. */
    var bucketIdleTtl: Duration = 5.minutes

    /** Maximum permits available independently in each bucket per [period]. */
    var maxCalls: Int = 100

    /** Fixed-window duration used by each bucket. */
    var period: Duration = 1.seconds

    /** Optional maximum wait for a bucket permit; null rejects immediately when its quota is exhausted. */
    var timeoutWhenLimited: Duration? = null

    /** Called when a bucket is rate limited, without exposing its key. */
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

/** Creates a typed [KeyedRateLimiter] from [configure]. */
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
