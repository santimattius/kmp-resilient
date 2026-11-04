package com.santimattius.resilient.ratelimiter

/**
 * Registry of named [KeyedRateLimiter] instances so several typed policies can **share** the same
 * per-key quotas (for example one limiter per downstream `"tenants"`).
 *
 * **Thread-safety:** like [RateLimiterRegistry], [getOrCreate] is **not** synchronized; build policies
 * on a single thread at startup.
 *
 * The first [getOrCreate] for a [name] wins; later calls return the same instance and ignore any
 * configuration differences. The context type `C` and key type `K` are not verified across calls:
 * use one name per context/key type.
 *
 * The registry owns the limiters it creates: closing a policy that uses one never removes or resets
 * it, and entries live as long as the registry. Limiters hold per-key buckets in memory, bounded by
 * [KeyedRateLimiterConfig.maxBuckets].
 */
class KeyedRateLimiterRegistry {

    private val instances = mutableMapOf<String, KeyedRateLimiter<*, *>>()

    /**
     * Returns the existing [KeyedRateLimiter] for [name], or creates one with [configure].
     *
     * @param name The name that identifies the shared limiter.
     * @param configure Configures the limiter; applied only when the entry is first created.
     * @param onRateLimited Optional callback invoked (without any key) when a bucket is rate limited;
     *   applied **only** at first creation, and invoked before the callback set in [configure].
     * @return The shared [KeyedRateLimiter] registered under [name].
     */
    @Suppress("UNCHECKED_CAST")
    fun <C, K> getOrCreate(
        name: String,
        configure: KeyedRateLimiterConfig<C, K>.() -> Unit,
        onRateLimited: (suspend () -> Unit)? = null,
    ): KeyedRateLimiter<C, K> {
        instances[name]?.let { return it as KeyedRateLimiter<C, K> }
        val config = KeyedRateLimiterConfig<C, K>().apply(configure)
        if (onRateLimited != null) {
            val configured = config.onRateLimited
            config.onRateLimited = {
                onRateLimited()
                configured()
            }
        }
        return KeyedRateLimiter(config).also { instances[name] = it }
    }
}
