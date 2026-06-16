package com.santimattius.resilient.ratelimiter

import kotlin.time.Duration

/**
 * Registry of named [DefaultRateLimiter] instances so multiple [com.santimattius.resilient.ResilientPolicy]
 * configurations can **share** the same rate-limiter quota (e.g. one token bucket per downstream `"payments"`, `"db"`).
 *
 * **Thread-safety:** [getOrCreate] is **not** synchronized across platforms. Typical use is to build policies
 * on a single thread at startup; do not call [getOrCreate] concurrently for the same registry without external
 * synchronization.
 *
 * The first successful [getOrCreate] for a given [name] wins; subsequent calls return the same instance and
 * ignore configuration differences (document your convention: one name, one config).
 */
class RateLimiterRegistry {

    private val instances = mutableMapOf<String, DefaultRateLimiter>()

    /**
     * Returns an existing [DefaultRateLimiter] for [name], or creates one with [configure].
     *
     * @param name Logical name identifying the shared limiter (e.g. `"payments"`, `"db"`).
     * @param configure Lambda to configure [RateLimiterConfig] — only applied on first creation.
     * @param onRateLimited Optional suspendable callback forwarded to [DefaultRateLimiter] **only** when the
     *   instance is first created. Receives the wait [Duration] before the rate-limited call is allowed or rejected.
     */
    fun getOrCreate(
        name: String,
        configure: RateLimiterConfig.() -> Unit,
        onRateLimited: (suspend (Duration) -> Unit)? = null
    ): DefaultRateLimiter {
        instances[name]?.let { return it }
        val created = DefaultRateLimiter(
            config = RateLimiterConfig().apply(configure),
            onRateLimited = onRateLimited ?: {}
        )
        instances[name] = created
        return created
    }
}
