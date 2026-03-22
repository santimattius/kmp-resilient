package com.santimattius.resilient.composition

import com.santimattius.resilient.ResilientPolicy
import com.santimattius.resilient.annotations.ResilientExperimentalApi
import com.santimattius.resilient.coalescing.CoalesceConfig
import com.santimattius.resilient.coalescing.DefaultCoalescingPolicy
import com.santimattius.resilient.bulkhead.BulkheadConfig
import com.santimattius.resilient.bulkhead.BulkheadRegistry
import com.santimattius.resilient.bulkhead.DefaultBulkhead
import com.santimattius.resilient.cache.CacheConfig
import com.santimattius.resilient.cache.CacheHandle
import com.santimattius.resilient.cache.InMemoryCachePolicy
import com.santimattius.resilient.circuitbreaker.CircuitBreakerConfig
import com.santimattius.resilient.circuitbreaker.DefaultCircuitBreaker
import com.santimattius.resilient.fallback.FallbackConfig
import com.santimattius.resilient.fallback.FallbackPolicy
import com.santimattius.resilient.hedging.DefaultHedgingPolicy
import com.santimattius.resilient.hedging.HedgingConfig
import com.santimattius.resilient.ratelimiter.DefaultRateLimiter
import com.santimattius.resilient.ratelimiter.RateLimiterConfig
import com.santimattius.resilient.retry.DefaultRetryPolicy
import com.santimattius.resilient.retry.RetryPolicyConfig
import com.santimattius.resilient.PolicyHealthSnapshot
import com.santimattius.resilient.telemetry.ResilientEvent
import com.santimattius.resilient.timeout.DefaultTimeoutPolicy
import com.santimattius.resilient.timeout.TimeoutConfig
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.time.TimeSource

/**
 * Type alias for a suspendable block that produces a value of type [T].
 * Used internally when composing resilience policy wrappers.
 */
typealias Execute<T> = suspend () -> T

/**
 * Named bulkhead request: resolved at [resilient] build time via [BulkheadRegistry.getOrCreate].
 */
internal data class BulkheadNamedSpec(
    val registry: BulkheadRegistry,
    val name: String,
    val configure: BulkheadConfig.() -> Unit
)

/**
 * A DSL builder for creating and configuring a composition of resilience policies.
 *
 * This builder is used within the `resilient { ... }` block to define which
 * resilience strategies to apply to a given operation. Each method corresponds
 * to a specific policy and accepts a lambda with a receiver to configure it.
 *
 * Example of usage:
 * ```kotlin
 * val policy = resilient {
 *     retry {
 *         maxAttempts = 3
 *     }
 *     timeout {
 *         duration = 5.seconds
 *     }
 *     circuitBreaker {
 *         failureRateThreshold = 50.0
 *     }
 * }
 * ```
 *
 * The policies are composed in a specific, predefined order to ensure predictable
 * behavior. See the `resilient` function documentation for the exact composition order.
 *
 * @see resilient
 * @see retry
 * @see circuitBreaker
 * @see rateLimiter
 * @see bulkhead
 * @see timeout
 * @see cache
 * @see fallback
 * @see hedging
 */
class ResilientBuilder {
    internal var retryConfig: RetryPolicyConfig? = null
    internal var circuitBreakerConfig: CircuitBreakerConfig? = null
    internal var rateLimiterConfig: RateLimiterConfig? = null
    internal var bulkheadConfig: BulkheadConfig? = null
    internal var bulkheadNamedSpec: BulkheadNamedSpec? = null
    internal var timeoutConfig: TimeoutConfig? = null
    internal var cacheConfig: CacheConfig? = null
    internal var coalesceConfig: CoalesceConfig? = null
    internal var fallbackConfig: FallbackConfig<Any?>? = null
    internal var hedgingConfig: HedgingConfig? = null
    internal var compositionOrder: CompositionOrder = CompositionOrder.DEFAULT
    internal var compositionOrderExplicitlySet: Boolean = false

    /**
     * Configures a retry policy. The block will be retried on failure according to [RetryPolicyConfig].
     * @param config Lambda to configure [RetryPolicyConfig] (e.g. maxAttempts, backoffStrategy).
     */
    fun retry(config: RetryPolicyConfig.() -> Unit) {
        retryConfig = (retryConfig ?: RetryPolicyConfig()).apply(config)
    }

    /**
     * Configures a circuit breaker. Execution is rejected when the circuit is open.
     * @param config Lambda to configure [CircuitBreakerConfig] (e.g. failureThreshold, timeout).
     */
    fun circuitBreaker(config: CircuitBreakerConfig.() -> Unit) {
        circuitBreakerConfig = (circuitBreakerConfig ?: CircuitBreakerConfig()).apply(config)
    }

    /**
     * Configures a rate limiter. Execution may be delayed or rejected when the rate limit is exceeded.
     * @param config Lambda to configure [RateLimiterConfig] (e.g. maxCalls, period).
     */
    fun rateLimiter(config: RateLimiterConfig.() -> Unit) {
        rateLimiterConfig = (rateLimiterConfig ?: RateLimiterConfig()).apply(config)
    }

    /**
     * Configures a bulkhead. Limits the number of concurrent executions and optional waiting queue.
     * @param config Lambda to configure [BulkheadConfig] (e.g. maxConcurrentCalls, maxWaitingCalls).
     */
    fun bulkhead(config: BulkheadConfig.() -> Unit) {
        require(bulkheadNamedSpec == null) {
            "Cannot use bulkhead { } together with bulkheadNamed(...); choose one."
        }
        bulkheadConfig = (bulkheadConfig ?: BulkheadConfig()).apply(config)
    }

    /**
     * Uses a shared [DefaultBulkhead] from [registry] keyed by [name].
     * Multiple policies can pass the same [BulkheadRegistry] and name to enforce a **global** limit
     * across those policies.
     *
     * Cannot be combined with [bulkhead] in the same builder.
     */
    fun bulkheadNamed(registry: BulkheadRegistry, name: String, config: BulkheadConfig.() -> Unit) {
        require(bulkheadConfig == null) {
            "Cannot use bulkheadNamed(...) together with bulkhead { }; choose one."
        }
        require(bulkheadNamedSpec == null) {
            "bulkheadNamed(...) can only be configured once per policy."
        }
        bulkheadNamedSpec = BulkheadNamedSpec(registry, name, config)
    }

    /**
     * Configures a timeout policy. Execution is cancelled if it exceeds the configured duration.
     * @param config Lambda to configure [TimeoutConfig] (e.g. timeout, onTimeout).
     */
    fun timeout(config: TimeoutConfig.() -> Unit) {
        timeoutConfig = (timeoutConfig ?: TimeoutConfig()).apply(config)
    }

    /**
     * Configures an in-memory cache. Results are cached by key and TTL; cache is checked before executing the block.
     * @param config Lambda to configure [CacheConfig] (e.g. key, ttl, cleanupInterval).
     */
    fun cache(config: CacheConfig.() -> Unit) {
        cacheConfig = (cacheConfig ?: CacheConfig()).apply(config)
    }

    /**
     * Configures request coalescing.
     *
     * Concurrent executions that resolve to the same key will share a single in-flight
     * execution and all callers will receive the same result (or error).
     *
     * This policy does not cache completed results.
     */
    fun coalesce(config: CoalesceConfig.() -> Unit) {
        coalesceConfig = (coalesceConfig ?: CoalesceConfig()).apply(config)
    }

    /**
     * Configures a fallback policy. When the block fails (excluding cancellation), [FallbackConfig.onFallback] is invoked.
     * @param config The fallback configuration providing the alternative result or logic.
     */
    fun fallback(config: FallbackConfig<Any?>) {
        fallbackConfig = config
    }

    /**
     * Configures a hedging policy. Multiple parallel attempts are launched; the first success is returned.
     * @param config Lambda to configure [HedgingConfig] (e.g. attempts, stagger).
     */
    fun hedging(config: HedgingConfig.() -> Unit) {
        hedgingConfig = (hedgingConfig ?: HedgingConfig()).apply(config)
    }

    /**
     * Configures the order in which policies are composed.
     * Policies are applied from outermost (first in list) to innermost (last in list).
     *
     * **Important**: Fallback is always positioned outermost and cannot be included in the order.
     * It will be automatically prepended to ensure it catches all failures from other policies.
     *
     * @param order The list of orderable policy types in the desired composition order (outermost to innermost).
     *              Optional: may be empty or a subset. Types in the list are ordered as given; any type not in the list
     *              is appended in the default order. Duplicates are removed.
     *
     * Example:
     * ```kotlin
     * import com.santimattius.resilient.composition.OrderablePolicyType
     *
     * resilient(scope) {
     *     compositionOrder(listOf(
     *         OrderablePolicyType.CACHE,        // Check cache first (after Fallback)
     *         OrderablePolicyType.TIMEOUT,       // Then apply timeout
     *         OrderablePolicyType.RETRY,         // Retry on failures
     *         OrderablePolicyType.CIRCUIT_BREAKER,
     *         OrderablePolicyType.RATE_LIMITER,
     *         OrderablePolicyType.BULKHEAD,
     *         OrderablePolicyType.HEDGING
     *     ))
     *     // Fallback is automatically added as the outermost policy
     *     // ... configure policies
     * }
     * ```
     */

    @ResilientExperimentalApi
    fun compositionOrder(order: List<OrderablePolicyType>) {
        compositionOrder = CompositionOrder(order)
        compositionOrderExplicitlySet = true
    }
}

/**
 * Builds a [ResilientPolicy] by composing configured policies.
 *
 * The policies are applied in a specific order, wrapping the execution block (`block`).
 * The order of execution starts from the outermost policy and proceeds inwards.
 *
 * Default Execution Order (from outer to inner):
 * 1. `fallback`
 * 2. `cache`
 * 3. `coalesce`
 * 4. `timeout`
 * 5. `retry`
 * 6. `circuitBreaker`
 * 7. `rateLimiter`
 * 8. `bulkhead`
 * 9. `hedging`
 *
 * This means a request will first pass through the `fallback`, then `cache`, then `coalesce`, and then `timeout`, and so on, until it reaches the core execution block, which is wrapped by the `hedging` policy.
 * The `fallback` policy is the last line of defense, catching any exceptions that bubble up through all other policies.
 *
 * Example: `fallback` -> `cache` -> `coalesce` -> `timeout` -> `retry` -> `...` -> `hedging` -> `block()`
 *
 * The composition order can be customized using [ResilientBuilder.compositionOrder]. This allows you to
 * change the order in which policies are applied, which can be useful for specific use cases or performance tuning.
 * Note that `fallback` is always positioned outermost automatically and cannot be configured, ensuring it
 * can catch all failures from other policies.
 *
 * @param resilientScope The [ResilientScope] in which the policies will operate, typically tied to a coroutine scope.
 * @param block A lambda with a [ResilientBuilder] receiver to configure the desired resilience policies.
 * @return A [ResilientPolicy] instance that can be used to execute operations with the configured policies.
 */
fun resilient(
    resilientScope: ResilientScope,
    block: ResilientBuilder.() -> Unit
): ResilientPolicy {
    val builder = ResilientBuilder().apply(block)
    val events = MutableSharedFlow<ResilientEvent>(extraBufferCapacity = 10)

    val cache = builder.cacheConfig?.let { cfg ->
        InMemoryCachePolicy(
            config = cfg,
            scope = resilientScope,
            onCacheHit = { key -> events.tryEmit(ResilientEvent.CacheHit(key)) },
            onCacheMiss = { key -> events.tryEmit(ResilientEvent.CacheMiss(key)) }
        )
    }
    val timeoutPolicy = builder.timeoutConfig?.let { cfg ->
        val copy = TimeoutConfig().apply {
            timeout = cfg.timeout
            onTimeout = {
                events.emit(ResilientEvent.TimeoutTriggered(cfg.timeout))
                cfg.onTimeout()
            }
        }
        DefaultTimeoutPolicy(copy)
    }

    val retryCfg = builder.retryConfig
    val retry = retryCfg?.let { cfg ->
        // Clone config so the original is not mutated (avoids duplicate callbacks if config is reused)
        val copy = RetryPolicyConfig().apply {
            maxAttempts = cfg.maxAttempts
            backoffStrategy = cfg.backoffStrategy
            shouldRetry = cfg.shouldRetry
            shouldRetryResult = cfg.shouldRetryResult
            perAttemptTimeout = cfg.perAttemptTimeout
            onRetry = { attempt, error ->
                events.emit(ResilientEvent.RetryAttempt(attempt, error))
                cfg.onRetry(attempt, error)
            }
        }
        DefaultRetryPolicy(copy)
    }

    val circuitBreaker = builder.circuitBreakerConfig?.let { cfg ->
        // Clone config so the original is not mutated (avoids duplicate callbacks if config is reused)
        val copy = CircuitBreakerConfig().apply {
            failureThreshold = cfg.failureThreshold
            successThreshold = cfg.successThreshold
            timeout = cfg.timeout
            halfOpenMaxCalls = cfg.halfOpenMaxCalls
            shouldRecordFailure = cfg.shouldRecordFailure
            slidingWindow = cfg.slidingWindow
            onStateChange = { state -> cfg.onStateChange(state) }
        }
        DefaultCircuitBreaker(copy) { new, old ->
            events.tryEmit(ResilientEvent.CircuitStateChanged(old, new))
        }
    }

    val rateLimiter = builder.rateLimiterConfig?.let { cfg ->
        DefaultRateLimiter(
            config = cfg,
            onRateLimited = { wait ->
                events.emit(ResilientEvent.RateLimited(wait))
            }
        )
    }

    val bulkhead = builder.bulkheadNamedSpec?.let { spec ->
        spec.registry.getOrCreate(spec.name, spec.configure) { reason ->
            events.tryEmit(ResilientEvent.BulkheadRejected(reason))
        }
    } ?: builder.bulkheadConfig?.let { cfg ->
        DefaultBulkhead(cfg) { reason ->
            events.tryEmit(ResilientEvent.BulkheadRejected(reason))
        }
    }
    val hedging = builder.hedgingConfig?.let { cfg ->
        DefaultHedgingPolicy(cfg) { attemptIndex ->
            events.tryEmit(ResilientEvent.HedgingUsed(attemptIndex))
        }
    }

    val coalescing = builder.coalesceConfig?.let { cfg ->
        DefaultCoalescingPolicy(cfg, resilientScope)
    }
    val fallback = builder.fallbackConfig?.let { cfg ->
        FallbackPolicy(cfg) { t ->
            events.tryEmit(ResilientEvent.FallbackTriggered(t))
        }
    }

    return object : ResilientPolicy {
        override val events: SharedFlow<ResilientEvent>
            get() = events.asSharedFlow()

        override val cacheHandle: CacheHandle?
            get() = cache

        override fun getHealthSnapshot(): PolicyHealthSnapshot = PolicyHealthSnapshot(
            circuitBreaker = circuitBreaker?.snapshot(),
            bulkhead = bulkhead?.snapshot()
        )

        override suspend fun <T> execute(block: Execute<T>): T {
            val mark = TimeSource.Monotonic.markNow()
            return try {
                val composed = compose(
                    cache = cache,
                    coalescing = coalescing,
                    timeout = timeoutPolicy,
                    retry = retry,
                    circuitBreaker = circuitBreaker,
                    rateLimiter = rateLimiter,
                    bulkhead = bulkhead,
                    hedging = hedging,
                    fallback = fallback,
                    compositionOrder = builder.compositionOrder,
                    block = block
                )
                val result = composed()
                events.emit(ResilientEvent.OperationSuccess(mark.elapsedNow()))
                result
            } catch (t: Throwable) {
                events.emit(ResilientEvent.OperationFailure(t, mark.elapsedNow()))
                throw t
            }
        }

        override fun close() {
            cache?.close()
        }

        private fun <T> compose(
            cache: InMemoryCachePolicy?,
            coalescing: DefaultCoalescingPolicy?,
            timeout: DefaultTimeoutPolicy?,
            retry: DefaultRetryPolicy?,
            circuitBreaker: DefaultCircuitBreaker?,
            rateLimiter: DefaultRateLimiter?,
            bulkhead: DefaultBulkhead?,
            hedging: DefaultHedgingPolicy?,
            fallback: FallbackPolicy?,
            compositionOrder: CompositionOrder,
            block: Execute<T>
        ): Execute<T> {
            // Build wrappers in the configured order (outermost to innermost)
            // Note: We reverse the order because we wrap from innermost to outermost
            val wrappers = if (builder.compositionOrderExplicitlySet) {
                compositionOrder.order.reversed().mapNotNull { policyType ->
                    when (policyType) {
                        PolicyType.FALLBACK -> fallback?.let { policy ->
                            { next: Execute<T> -> suspend { policy.execute { next() } } }
                        }

                        PolicyType.CACHE -> cache?.let { policy ->
                            { next: Execute<T> -> suspend { policy.execute { next() } } }
                        }

                        PolicyType.COALESCE -> coalescing?.let { policy ->
                            { next: Execute<T> -> suspend { policy.execute { next() } } }
                        }

                        PolicyType.TIMEOUT -> timeoutPolicy?.let { policy ->
                            { next: Execute<T> -> suspend { policy.execute { next() } } }
                        }

                        PolicyType.RETRY -> retry?.let { policy ->
                            { next: Execute<T> -> suspend { policy.execute { next() } } }
                        }

                        PolicyType.CIRCUIT_BREAKER -> circuitBreaker?.let { policy ->
                            { next: Execute<T> -> suspend { policy.execute { next() } } }
                        }

                        PolicyType.RATE_LIMITER -> rateLimiter?.let { policy ->
                            { next: Execute<T> -> suspend { policy.execute { next() } } }
                        }

                        PolicyType.BULKHEAD -> bulkhead?.let { policy ->
                            { next: Execute<T> -> suspend { policy.execute { next() } } }
                        }

                        PolicyType.HEDGING -> hedging?.let { policy ->
                            { next: Execute<T> -> suspend { policy.execute { next() } } }
                        }
                    }
                }
            } else {
                buildList {
                    if (hedging != null) add { next -> { hedging.execute { next() } } }
                    if (bulkhead != null) add { next -> { bulkhead.execute { next() } } }
                    if (rateLimiter != null) add { next -> { rateLimiter.execute { next() } } }
                    if (circuitBreaker != null) add { next -> { circuitBreaker.execute { next() } } }
                    if (retry != null) add { next -> { retry.execute { next() } } }
                    if (timeout != null) add { next -> { timeout.execute { next() } } }
                    if (coalescing != null) add { next -> { coalescing.execute { next() } } }
                    if (cache != null) add { next -> { cache.execute { next() } } }
                    if (fallback != null) add { next -> { fallback.execute { next() } } }
                }
            }


            var composed: Execute<T> = block
            for (wrap in wrappers) {
                val next = composed
                composed = wrap(next)
            }
            return composed
        }
    }
}
