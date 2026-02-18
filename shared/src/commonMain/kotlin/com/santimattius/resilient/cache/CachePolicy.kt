package com.santimattius.resilient.cache

import com.santimattius.resilient.composition.ResilientScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

/**
 * A policy that adds a caching layer to an operation.
 *
 * Implementations of this interface define how and where data is cached.
 * The `execute` method will first attempt to retrieve a result from the cache.
 * If the data is not found or has expired, it will execute the provided [block],
 * store its result in the cache, and then return it.
 */
interface CachePolicy {
    /**
     * Returns a cached result for the configured key if valid and non-expired; otherwise executes [block], caches the result, and returns it.
     * @param T The return type of the block and cached value.
     * @param block The suspendable operation to execute on cache miss or expiry.
     * @return The cached value or the result of [block] (after caching).
     */
    suspend fun <T> execute(block: suspend () -> T): T
}

/**
 * Configuration for a [CachePolicy].
 *
 * This class is used to configure the behavior of a cache policy, such as setting
 * a unique key for a cache entry, its time-to-live (TTL), and periodic cleanup parameters.
 *
 * @property key The unique identifier for a cache entry. Defaults to "default".
 * @property ttl The duration for which a cache entry is valid (time-to-live).
 *               After this period, the entry is considered expired. Defaults to 60 seconds.
 * @property cleanupInterval The interval at which expired cache entries are automatically removed.
 *                           If `null`, automatic cleanup is disabled. Defaults to `null`.
 *                           This requires a [ResilientScope] to be provided to the cache policy.
 * @property cleanupBatch The maximum number of expired entries to remove in a single cleanup run.
 *                        This helps to avoid long-running cleanup tasks. Defaults to 100.
 */
class CacheConfig {
    var key: String = "default"
    var ttl: Duration = 60.seconds
    var cleanupInterval: Duration? = null
    var cleanupBatch: Int = 100
}

/**
 * A [CachePolicy] implementation that stores values in memory.
 *
 * This policy uses a simple in-memory map to cache results. Each cached entry is associated
 * with a Time-To-Live (TTL) defined in the [CacheConfig]. When [execute] is called, it first
 * checks for a valid, non-expired entry in the cache using the configured key.
 *
 * - If a valid entry is found, it is returned immediately without executing the block.
 * - If no entry is found or the existing entry has expired, the block is executed,
 *   its result is stored in the cache, and then the result is returned.
 *
 * This implementation is resilient to the "thundering herd" problem by ensuring that
 * for a given key, the expensive operation is executed only once even when multiple
 * requests arrive concurrently.
 *
 * @param config The [CacheConfig] specifying the cache key and time-to-live (TTL).
 * @param scope Optional [ResilientScope] used to launch the cleanup job when [CacheConfig.cleanupInterval] is set.
 */
internal class InMemoryCachePolicy(
    private val config: CacheConfig,
    scope: ResilientScope? = null
) : CachePolicy, AutoCloseable {

    private data class Entry(val value: Any?, val expiresAt: Long)

    private val store = mutableMapOf<String, Entry>()
    private val ongoing = mutableMapOf<String, Deferred<Any?>>()
    private val mutex = Mutex()
    private var sweeperJob: Job? = null

    init {
        val interval = config.cleanupInterval
        if (interval != null && scope != null) {
            sweeperJob = scope.launch {
                while (isActive) {
                    delay(interval)
                    purgeExpiredNow(config.cleanupBatch)
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T> execute(block: suspend () -> T): T = coroutineScope {
        val key = config.key
        val now = currentTimeMillis()

        mutex.withLock {
            store[key]?.let { entry ->
                if (now < entry.expiresAt) {
                    return@coroutineScope entry.value as T
                }
                store.remove(key)
            }
        }

        val deferred: Deferred<Any?> = mutex.withLock {
            ongoing.getOrPut(key) {
                async {
                    try {
                        val result = block()
                        val expiresAt = currentTimeMillis() + config.ttl.inWholeMilliseconds
                        mutex.withLock {
                            store[key] = Entry(result, expiresAt)
                        }
                        result
                    } finally {
                        mutex.withLock {
                            ongoing.remove(key)
                        }
                    }
                }
            }
        }

        deferred.await() as T
    }

    @OptIn(ExperimentalTime::class)
    private fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()

    private suspend fun purgeExpiredNow(maxBatch: Int = Int.MAX_VALUE) {
        val now = currentTimeMillis()
        var removed = 0
        mutex.withLock {
            val it = store.iterator()
            while (it.hasNext() && removed < maxBatch) {
                val cacheEntry = it.next()
                if (now >= cacheEntry.value.expiresAt) {
                    it.remove()
                    removed++
                }
            }
        }
    }

    override fun close() {
        sweeperJob?.cancel()
        sweeperJob = null
    }
}