package com.santimattius.resilient.cache

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
    suspend fun <T> execute(block: suspend () -> T): T
}

/**
 * Configuration for a [CachePolicy].
 *
 * This class is used to configure the behavior of a cache policy, such as setting
 * a unique key for a cache entry and its time-to-live (TTL).
 *
 * @property key The unique identifier for a cache entry. Defaults to "default".
 * @property ttl The duration for which the cache entry is valid (time-to-live).
 *               After this period, the entry is considered expired. Defaults to 60 seconds.
 */
class CacheConfig {
    var key: String = "default"
    var ttl: Duration = 60.seconds
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
 * @param config The [CacheConfig] specifying the cache key and time-to-live (TTL).
 */
class InMemoryCachePolicy(
    private val config: CacheConfig
) : CachePolicy {

    private data class Entry(val value: Any?, val expiresAt: Long)
    private val store = mutableMapOf<String, Entry>()

    @OptIn(ExperimentalTime::class)
    @Suppress("UNCHECKED_CAST")
    override suspend fun <T> execute(block: suspend () -> T): T {
        val now = Clock.System.now().toEpochMilliseconds()
        val entry = store[config.key]
        if (entry != null && now < entry.expiresAt) {
            return entry.value as T
        }
        val result = block()
        store[config.key] = Entry(result, now + config.ttl.inWholeMilliseconds)
        return result
    }
}
