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
 *
 * **Custom implementations:** You can provide your own [CachePolicy] for persistent
 * storage (e.g. multiplatform settings, SQLDelight), or external caches (e.g. Redis).
 * Implement [execute] to resolve the key (from [CacheConfig.key] or [CacheConfig.keyProvider]),
 * look up the cache, and on miss run [block] and store the result. For invalidation support,
 * implement [CacheHandle] as well and expose it via your builder or policy.
 *
 * **Key resolution:** If the config uses [CacheConfig.keyProvider], invoke it at the start of
 * [execute] to get the key for this request; otherwise use [CacheConfig.key].
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
 * Optional capability for cache policies that support invalidation by key or prefix.
 *
 * Use [ResilientPolicy.cacheHandle] to obtain this when the policy was built with cache enabled.
 * [InMemoryCachePolicy] implements this interface.
 */
interface CacheHandle {
    /**
     * Removes the cache entry for [key], if present.
     */
    suspend fun invalidate(key: String)

    /**
     * Removes all cache entries whose key starts with [prefix].
     * No-op if no keys match.
     */
    suspend fun invalidatePrefix(prefix: String)
}

/**
 * Configuration for a [CachePolicy].
 *
 * This class is used to configure the behavior of a cache policy, such as setting
 * a unique key for a cache entry, its time-to-live (TTL), and periodic cleanup parameters.
 *
 * **Cache key:** Use [key] for a fixed key, or [keyProvider] for a key derived at execution time
 * (e.g. from request context, user id, or arguments captured in the lambda). If [keyProvider] is
 * set, it is invoked on each [CachePolicy.execute] and its result is used as the cache key;
 * otherwise [key] is used. This allows reusing the same cache policy for multiple logical
 * resources (e.g. `keyProvider = { "user:${userId}" }`).
 *
 * @property key The unique identifier for a cache entry when [keyProvider] is null. Defaults to "default".
 * @property keyProvider Optional suspend lambda that returns the cache key at execution time.
 *                       If set, takes precedence over [key]. Use for dynamic keys (e.g. per-request, per-tenant).
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
    var keyProvider: (suspend () -> String)? = null
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
 * @param onCacheHit Optional callback invoked when a cache hit occurs; receives the resolved cache key (optional telemetry).
 * @param onCacheMiss Optional callback invoked when the block is executed due to a cache miss; receives the resolved cache key (optional telemetry).
 */
internal class InMemoryCachePolicy(
    private val config: CacheConfig,
    scope: ResilientScope? = null,
    private val onCacheHit: ((key: String) -> Unit)? = null,
    private val onCacheMiss: ((key: String) -> Unit)? = null
) : CachePolicy, CacheHandle, AutoCloseable {

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
        val key = config.keyProvider?.invoke() ?: config.key
        val now = currentTimeMillis()

        mutex.withLock {
            store[key]?.let { entry ->
                if (now < entry.expiresAt) {
                    onCacheHit?.invoke(key)
                    return@coroutineScope entry.value as T
                }
                store.remove(key)
            }
        }

        val deferred: Deferred<Any?> = mutex.withLock {
            ongoing.getOrPut(key) {
                async {
                    try {
                        onCacheMiss?.invoke(key)
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

    override suspend fun invalidate(key: String) {
        mutex.withLock {
            store.remove(key)
        }
    }

    override suspend fun invalidatePrefix(prefix: String) {
        mutex.withLock {
            val keysToRemove = store.keys.filter { it.startsWith(prefix) }
            keysToRemove.forEach { store.remove(it) }
        }
    }

    override fun close() {
        sweeperJob?.cancel()
        sweeperJob = null
    }
}