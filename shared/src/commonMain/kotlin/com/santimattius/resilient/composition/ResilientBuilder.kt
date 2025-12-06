package com.santimattius.resilient.composition

import com.santimattius.resilient.ResilientPolicy
import com.santimattius.resilient.bulkhead.BulkheadConfig
import com.santimattius.resilient.bulkhead.DefaultBulkhead
import com.santimattius.resilient.circuitbreaker.CircuitBreakerConfig
import com.santimattius.resilient.circuitbreaker.DefaultCircuitBreaker
import com.santimattius.resilient.ratelimiter.RateLimiterConfig
import com.santimattius.resilient.ratelimiter.DefaultRateLimiter
import com.santimattius.resilient.retry.RetryPolicyConfig
import com.santimattius.resilient.retry.DefaultRetryPolicy
import com.santimattius.resilient.timeout.TimeoutConfig
import com.santimattius.resilient.timeout.DefaultTimeoutPolicy
import com.santimattius.resilient.telemetry.ResilientEvent
import com.santimattius.resilient.cache.CacheConfig
import com.santimattius.resilient.cache.InMemoryCachePolicy
import com.santimattius.resilient.fallback.FallbackConfig
import com.santimattius.resilient.fallback.FallbackPolicy
import com.santimattius.resilient.hedging.DefaultHedgingPolicy
import com.santimattius.resilient.hedging.HedgingConfig
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.time.TimeSource


typealias Execute<T> = suspend () -> T

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
    internal var timeoutConfig: TimeoutConfig? = null
    internal var cacheConfig: CacheConfig? = null
    internal var fallbackConfig: FallbackConfig<Any?>? = null
    internal var hedgingConfig: HedgingConfig? = null

    fun retry(config: RetryPolicyConfig.() -> Unit) {
        retryConfig = (retryConfig ?: RetryPolicyConfig()).apply(config)
    }

    fun circuitBreaker(config: CircuitBreakerConfig.() -> Unit) {
        circuitBreakerConfig = (circuitBreakerConfig ?: CircuitBreakerConfig()).apply(config)
    }

    fun rateLimiter(config: RateLimiterConfig.() -> Unit) {
        rateLimiterConfig = (rateLimiterConfig ?: RateLimiterConfig()).apply(config)
    }

    fun bulkhead(config: BulkheadConfig.() -> Unit) {
        bulkheadConfig = (bulkheadConfig ?: BulkheadConfig()).apply(config)
    }

    fun timeout(config: TimeoutConfig.() -> Unit) {
        timeoutConfig = (timeoutConfig ?: TimeoutConfig()).apply(config)
    }

    fun cache(config: CacheConfig.() -> Unit) {
        cacheConfig = (cacheConfig ?: CacheConfig()).apply(config)
    }

    fun fallback(config: FallbackConfig<Any?>) {
        fallbackConfig = config
    }

    fun hedging(config: HedgingConfig.() -> Unit) {
        hedgingConfig = (hedgingConfig ?: HedgingConfig()).apply(config)
    }
}

/**
 * Builds a [ResilientPolicy] by composing configured policies.
 *
 * The policies are applied in a specific order, wrapping the execution block (`block`).
 * The order of execution starts from the innermost policy and proceeds outwards.
 *
 * Execution Order (from inner to outer):
 * 1. `hedging`
 * 2. `bulkhead`
 * 3. `rateLimiter`
 * 4. `circuitBreaker`
 * 5. `retry`
 * 6. `timeout`
 * 7. `cache`
 * 8. `fallback`
 *
 * This means a request will first pass through the `fallback` and `cache` policies, then `timeout`, and so on, until it reaches the core execution block.
 * The `fallback` policy is the last line of defense, catching any exceptions that bubble up through all other policies.
 *
 * Example: `fallback` -> `cache` -> `timeout` -> `retry` -> `...` -> `hedging` -> `block()`
 *
 * @param block A lambda with a [ResilientBuilder] receiver to configure the desired resilience policies.
 * @return A [ResilientPolicy] instance that can be used to execute operations with the configured policies.
 */
fun resilient(block: ResilientBuilder.() -> Unit): ResilientPolicy {
    val builder = ResilientBuilder().apply(block)
    val events = MutableSharedFlow<ResilientEvent>(extraBufferCapacity = 0)

    val cache = builder.cacheConfig?.let { InMemoryCachePolicy(it) }
    val timeout = builder.timeoutConfig?.let { DefaultTimeoutPolicy(it) }

    val retryCfg = builder.retryConfig
    val retry = retryCfg?.let {
        // wire onRetry to emit event
        val original = it.onRetry
        it.onRetry = { attempt, error ->
            events.emit(ResilientEvent.RetryAttempt(attempt, error))
            original(attempt, error)
        }
        DefaultRetryPolicy(it)
    }

    val circuitBreaker = builder.circuitBreakerConfig?.let { cfg ->
        val original = cfg.onStateChange
        cfg.onStateChange = { state ->
            original(state)
            // emitting only state-to-state transitions requires knowledge of previous; handled inside impl
        }
        DefaultCircuitBreaker(cfg) { new ->
            // best effort: we do not have previous here; emit change to new with placeholder
            events.tryEmit(ResilientEvent.CircuitStateChanged(new, new))
        }
    }

    val rateLimiter = builder.rateLimiterConfig?.let { cfg ->
        DefaultRateLimiter(cfg) { wait ->
            events.emit(ResilientEvent.RateLimited(wait))
        }
    }

    val bulkhead = builder.bulkheadConfig?.let { DefaultBulkhead(it) }
    val hedging = builder.hedgingConfig?.let { DefaultHedgingPolicy(it) }
    val fallback = builder.fallbackConfig?.let { FallbackPolicy(it) }

    return object : ResilientPolicy {
        override val events: SharedFlow<ResilientEvent>
            get() = events.asSharedFlow()

        override suspend fun <T> execute(block: Execute<T>): T {
            val mark = TimeSource.Monotonic.markNow()
            return try {
                val composed = compose(
                    cache = cache,
                    timeout = timeout,
                    retry = retry,
                    circuitBreaker = circuitBreaker,
                    rateLimiter = rateLimiter,
                    bulkhead = bulkhead,
                    hedging = hedging,
                    fallback = fallback,
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

        private fun <T> compose(
            cache: InMemoryCachePolicy?,
            timeout: DefaultTimeoutPolicy?,
            retry: DefaultRetryPolicy?,
            circuitBreaker: DefaultCircuitBreaker?,
            rateLimiter: DefaultRateLimiter?,
            bulkhead: DefaultBulkhead?,
            hedging: DefaultHedgingPolicy?,
            fallback: FallbackPolicy?,
            block: Execute<T>
        ): Execute<T> {
            val wrappers = buildList<(Execute<T>) -> Execute<T>> {
                if (hedging != null) add { next -> { hedging.execute { next() } } }
                if (bulkhead != null) add { next -> { bulkhead.execute { next() } } }
                if (rateLimiter != null) add { next -> { rateLimiter.execute { next() } } }
                if (circuitBreaker != null) add { next -> { circuitBreaker.execute { next() } } }
                if (retry != null) add { next -> { retry.execute { next() } } }
                if (timeout != null) add { next -> { timeout.execute { next() } } }
                if (cache != null) add { next -> { cache.execute { next() } } }
                if (fallback != null) add { next -> { fallback.execute { next() } } }
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
