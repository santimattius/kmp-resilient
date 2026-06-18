package com.santimattius.resilient.ktor

import com.santimattius.resilient.ResilientPolicy
import com.santimattius.resilient.bulkhead.BulkheadConfig
import com.santimattius.resilient.circuitbreaker.CircuitBreakerConfig
import com.santimattius.resilient.composition.ResilientScope
import com.santimattius.resilient.ratelimiter.RateLimiterConfig
import com.santimattius.resilient.retry.RetryPolicyConfig
import com.santimattius.resilient.timeout.TimeoutConfig
import io.ktor.http.HttpStatusCode

/**
 * Configuration for [ResilientPlugin].
 *
 * Choose exactly ONE of the two policy-source paths:
 *
 * **Path A — Bring-Your-Own policy:**
 * ```kotlin
 * install(ResilientPlugin) {
 *     policy = myExistingPolicy
 * }
 * ```
 * The plugin uses the provided policy and does NOT close it when the client closes.
 * Note: [shouldRetryOnStatus] is inert in this path; configure status-based retry
 * inside the policy's own `RetryPolicyConfig.shouldRetryResult`.
 *
 * **Path B — Inline DSL:**
 * ```kotlin
 * install(ResilientPlugin) {
 *     scope = appScope.asResilientScope()
 *     retry { maxAttempts = 3 }
 *     shouldRetryOnStatus = { it.value >= 500 }
 * }
 * ```
 * The plugin builds an internal [ResilientPolicy] and closes it when the client closes.
 * [shouldRetryOnStatus] is bridged into `RetryPolicyConfig.shouldRetryResult` automatically.
 *
 * Setting both `policy` and inline DSL blocks, or neither, fails with [IllegalStateException]
 * at client construction time.
 */
class ResilientPluginConfig {

    // ── Path A: bring-your-own policy ────────────────────────────────────────

    /**
     * Provide a pre-built [ResilientPolicy]. The plugin will NOT close this policy on client close.
     * Mutually exclusive with inline DSL blocks ([retry], [circuitBreaker], etc.).
     */
    var policy: ResilientPolicy? = null

    // ── Path B: inline DSL ────────────────────────────────────────────────────

    /**
     * The [ResilientScope] used to build the policy inline.
     * Required when using inline DSL blocks ([retry], [circuitBreaker], etc.).
     * Ignored when [policy] is set.
     */
    var scope: ResilientScope? = null

    // ── HTTP-aware predicates (Path B only) ───────────────────────────────────

    /**
     * Determines whether a response status code should trigger a retry.
     * Defaults to `{ it.value >= 500 }` (any 5xx is retryable).
     * Only applied in the inline DSL path; BYO policy users must configure this inside their own policy.
     */
    var shouldRetryOnStatus: (HttpStatusCode) -> Boolean = { it.value >= 500 }

    /**
     * When `true` (default), POST and PATCH requests bypass the policy entirely — no retry,
     * no circuit breaker, no timeout — preserving at-most-once semantics for non-idempotent methods.
     * Set to `false` to allow the policy to wrap all HTTP methods.
     *
     * See ADR-3 in the design document for the accepted tradeoff: this flag also skips circuit
     * breaker and timeout for POST/PATCH, not just retry.
     */
    var retryOnlyIdempotent: Boolean = true

    // ── Inline DSL block captures ─────────────────────────────────────────────

    internal var retryBlock: (RetryPolicyConfig.() -> Unit)? = null
    internal var circuitBreakerBlock: (CircuitBreakerConfig.() -> Unit)? = null
    internal var rateLimiterBlock: (RateLimiterConfig.() -> Unit)? = null
    internal var bulkheadBlock: (BulkheadConfig.() -> Unit)? = null
    internal var timeoutBlock: (TimeoutConfig.() -> Unit)? = null

    /** Configures a retry policy when using the inline DSL path. */
    fun retry(block: RetryPolicyConfig.() -> Unit) {
        retryBlock = block
    }

    /** Configures a circuit breaker when using the inline DSL path. */
    fun circuitBreaker(block: CircuitBreakerConfig.() -> Unit) {
        circuitBreakerBlock = block
    }

    /** Configures a rate limiter when using the inline DSL path. */
    fun rateLimiter(block: RateLimiterConfig.() -> Unit) {
        rateLimiterBlock = block
    }

    /** Configures a bulkhead when using the inline DSL path. */
    fun bulkhead(block: BulkheadConfig.() -> Unit) {
        bulkheadBlock = block
    }

    /** Configures a timeout policy when using the inline DSL path. */
    fun timeout(block: TimeoutConfig.() -> Unit) {
        timeoutBlock = block
    }

    // NOTE v2: hedging is intentionally absent. Hedging requires launching parallel in-flight
    // requests and collecting the first successful response. Ktor's on(Send) hook provides a
    // single proceed(request) call per request — it cannot fork parallel sends. A hedging
    // integration would require a dedicated Ktor SendPipeline phase or a wrapper at the
    // HttpClient level rather than a client plugin hook.

    /**
     * Returns `true` when at least one inline DSL block has been configured.
     * Used to determine whether the inline-DSL path is active.
     */
    internal val hasInlineConfig: Boolean
        get() = retryBlock != null
            || circuitBreakerBlock != null
            || rateLimiterBlock != null
            || bulkheadBlock != null
            || timeoutBlock != null
}
