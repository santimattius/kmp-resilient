package com.santimattius.resilient.composition

import com.santimattius.resilient.ResilientPolicy
import com.santimattius.resilient.annotations.ResilientExperimentalApi
import com.santimattius.resilient.chaos.ChaosConfig
import com.santimattius.resilient.chaos.DefaultChaosPolicy
import com.santimattius.resilient.coalescing.CoalesceConfig
import com.santimattius.resilient.coalescing.DefaultCoalescingPolicy
import com.santimattius.resilient.bulkhead.BulkheadConfig
import com.santimattius.resilient.bulkhead.BulkheadRegistry
import com.santimattius.resilient.bulkhead.DefaultBulkhead
import com.santimattius.resilient.cache.CacheConfig
import com.santimattius.resilient.cache.CacheHandle
import com.santimattius.resilient.cache.InMemoryCachePolicy
import com.santimattius.resilient.circuitbreaker.CircuitBreakerConfig
import com.santimattius.resilient.circuitbreaker.CircuitBreakerRegistry
import com.santimattius.resilient.circuitbreaker.CircuitState
import com.santimattius.resilient.circuitbreaker.DefaultCircuitBreaker
import com.santimattius.resilient.deadline.ResilientDeadline
import com.santimattius.resilient.fallback.FallbackConfig
import com.santimattius.resilient.fallback.FallbackPolicy
import com.santimattius.resilient.hedging.DefaultHedgingPolicy
import com.santimattius.resilient.hedging.HedgingConfig
import com.santimattius.resilient.ratelimiter.DefaultRateLimiter
import com.santimattius.resilient.ratelimiter.KeyedRateLimiter
import com.santimattius.resilient.ratelimiter.KeyedRateLimiterSnapshot
import com.santimattius.resilient.ratelimiter.RateLimiterConfig
import com.santimattius.resilient.ratelimiter.RateLimiterRegistry
import com.santimattius.resilient.retry.DefaultRetryPolicy
import com.santimattius.resilient.retry.RetryPolicyConfig
import com.santimattius.resilient.PolicyHealthSnapshot
import com.santimattius.resilient.telemetry.ResilientEvent
import com.santimattius.resilient.timeout.DefaultTimeoutPolicy
import com.santimattius.resilient.timeout.TimeoutConfig
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource


/**
 * Single internal composition engine shared by [resilient] and [resilientFor].
 *
 * It owns all runtime state of one build (policies, events) and runs the composed pipeline.
 * Public policies are thin facades over one engine instance; engines are never nested.
 *
 * Mutable state is created per engine, so two builds never share it unless it comes from an explicit
 * named registry; [close] releases only engine-owned resources, never registry-owned state.
 */
@OptIn(ResilientExperimentalApi::class)
internal class ResilientEngine(
    private val builder: ResilientBuilder,
    resilientScope: ResilientScope,
    keyedRateLimiterFactory: (((suspend () -> Unit) -> KeyedRateLimiter<Any?, *>))? = null,
    private val cacheKeyFor: ((Any?) -> String)? = null,
    private val coalesceKeyFor: ((Any?) -> String)? = null
) {
    private val events = MutableSharedFlow<ResilientEvent>(extraBufferCapacity = 10)
    val eventFlow: SharedFlow<ResilientEvent> get() = events.asSharedFlow()


    private val cache = builder.cacheConfig?.let { cfg ->
        InMemoryCachePolicy(
            config = cfg,
            scope = resilientScope,
            onCacheHit = { key -> events.tryEmit(ResilientEvent.CacheHit(key)) },
            onCacheMiss = { key -> events.tryEmit(ResilientEvent.CacheMiss(key)) }
        )
    }
    private val timeoutPolicy = builder.timeoutConfig?.let { cfg ->
        val copy = TimeoutConfig().apply {
            timeout = cfg.timeout
            onTimeout = {
                events.emit(ResilientEvent.TimeoutTriggered(cfg.timeout))
                cfg.onTimeout()
            }
        }
        DefaultTimeoutPolicy(copy)
    }

    private val retryCfg = builder.retryConfig
    private val retry = retryCfg?.let { cfg ->
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

    // Named circuit breaker takes precedence over the inline config block.
    // When the named spec resolves to an existing registry entry, the onStateChanged callback
    // is set only at creation time — see CircuitBreakerRegistry.getOrCreate telemetry note.
    private val circuitBreaker: DefaultCircuitBreaker? = builder.circuitBreakerNamedSpec?.let { spec ->
        spec.registry.getOrCreate(spec.name, spec.configure) { new, old ->
            events.tryEmit(ResilientEvent.CircuitStateChanged(old, new))
        }
    } ?: builder.circuitBreakerConfig?.let { cfg ->
        // Clone config so the original is not mutated (avoids duplicate callbacks if config is reused)
        val copy = CircuitBreakerConfig().apply {
            failureThreshold = cfg.failureThreshold
            successThreshold = cfg.successThreshold
            timeout = cfg.timeout
            halfOpenMaxCalls = cfg.halfOpenMaxCalls
            shouldRecordFailure = cfg.shouldRecordFailure
            shouldRecordResult = cfg.shouldRecordResult
            slidingWindow = cfg.slidingWindow
            failureRateThreshold = cfg.failureRateThreshold
            minimumNumberOfCalls = cfg.minimumNumberOfCalls
            onStateChange = { state -> cfg.onStateChange(state) }
        }
        DefaultCircuitBreaker(copy) { new, old ->
            events.tryEmit(ResilientEvent.CircuitStateChanged(old, new))
        }
    }

    private val rateLimiter = builder.rateLimiterNamedSpec?.let { spec ->
        spec.registry.getOrCreate(spec.name, spec.configure) { wait ->
            events.emit(ResilientEvent.RateLimited(wait))
        }
    } ?: builder.rateLimiterConfig?.let { cfg ->
        DefaultRateLimiter(
            config = cfg,
            onRateLimited = { wait ->
                events.emit(ResilientEvent.RateLimited(wait))
            }
        )
    }

    // Keyed limiter telemetry carries no key or input; the factory receives only a keyless callback.
    private val keyedRateLimiter: KeyedRateLimiter<Any?, *>? = keyedRateLimiterFactory?.invoke {
        events.emit(ResilientEvent.KeyedRateLimited)
    }

    private val bulkhead = builder.bulkheadNamedSpec?.let { spec ->
        spec.registry.getOrCreate(spec.name, spec.configure) { reason ->
            events.tryEmit(ResilientEvent.BulkheadRejected(reason))
        }
    } ?: builder.bulkheadConfig?.let { cfg ->
        DefaultBulkhead(cfg) { reason ->
            events.tryEmit(ResilientEvent.BulkheadRejected(reason))
        }
    }
    private val hedging = builder.hedgingConfig?.let { cfg ->
        DefaultHedgingPolicy(cfg) { attemptIndex ->
            events.tryEmit(ResilientEvent.HedgingUsed(attemptIndex))
        }
    }

    private val coalescing = builder.coalesceConfig?.let { cfg ->
        DefaultCoalescingPolicy(cfg, resilientScope)
    }
    private val fallback = builder.fallbackConfig?.let { cfg ->
        FallbackPolicy(cfg) { t ->
            events.tryEmit(ResilientEvent.FallbackTriggered(t))
        }
    }

    private val chaos = builder.chaosConfig?.takeIf { it.enabled }?.let { cfg ->
        DefaultChaosPolicy(cfg)
    }

    val cacheHandle: CacheHandle? get() = cache

    fun healthSnapshot(): PolicyHealthSnapshot = PolicyHealthSnapshot(
        circuitBreaker = circuitBreaker?.snapshot(),
        bulkhead = bulkhead?.snapshot(),
        rateLimiter = rateLimiter?.snapshot(),
        retry = retry?.snapshot(),
        cache = cache?.snapshot()
    )

    fun keyedRateLimiterSnapshot(): KeyedRateLimiterSnapshot? = keyedRateLimiter?.snapshot()

    fun close() {
        cache?.close()
    }

    @OptIn(ResilientExperimentalApi::class)
    suspend fun <T> execute(block: Execute<T>): T = execute(null, block)

    /**
     * Runs [block] under the composed pipeline. [input] feeds the keyed rate limiter only; it is never
     * placed in events or exceptions by the engine.
     */
    suspend fun <T> execute(input: Any?, block: Execute<T>): T {
        // Deadline intercept: check for a ResilientDeadline in the coroutine context.
        // Must be read before compose() so we capture the context of the *caller*.
        val deadline = currentCoroutineContext()[ResilientDeadline.Key]
        val composed = buildComposed(input, block)

        val mark = TimeSource.Monotonic.markNow()
        return try {
            val result = if (deadline != null) {
                // Compute the effective timeout: min(configured timeout, remaining deadline).
                // When remaining <= 0, effectiveTimeoutMs is <= 0 — withTimeout(<=0L)
                // throws TimeoutCancellationException immediately without invoking the block.
                val remainingMs = deadline.remaining().inWholeMilliseconds
                val effectiveTimeoutMs = if (builder.timeoutConfig != null) {
                    minOf(builder.timeoutConfig!!.timeout.inWholeMilliseconds, remainingMs)
                } else {
                    remainingMs
                }
                withTimeout(effectiveTimeoutMs.milliseconds) { composed() }
            } else {
                composed()
            }
            // tryEmit never suspends: a slow events collector must not delay the caller's result.
            events.tryEmit(ResilientEvent.OperationSuccess(mark.elapsedNow()))
            result
        } catch (t: Throwable) {
            // tryEmit never suspends or throws, so the original exception (including a
            // CancellationException) always propagates unchanged, even if the buffer is full.
            events.tryEmit(ResilientEvent.OperationFailure(t, mark.elapsedNow()))
            throw t
        }
    }

    private fun <T> buildComposed(input: Any?, block: Execute<T>): Execute<T> = compose(
        input = input,
        cache = cache,
        coalescing = coalescing,
        timeout = timeoutPolicy,
        retry = retry,
        circuitBreaker = circuitBreaker,
        rateLimiter = rateLimiter,
        keyedRateLimiter = keyedRateLimiter,
        bulkhead = bulkhead,
        hedging = hedging,
        chaos = chaos,
        fallback = fallback,
        compositionOrder = builder.compositionOrder,
        block = block
    )

    // Typed policies derive the key from the execution input; legacy policies use the static config.
    private fun <T> cached(policy: InMemoryCachePolicy, input: Any?, next: Execute<T>): Execute<T> {
        val keyFor = cacheKeyFor ?: return { policy.execute { next() } }
        return { policy.executeWithKey(keyFor(input), redactEvents = true) { next() } }
    }

    private fun <T> coalesced(policy: DefaultCoalescingPolicy, input: Any?, next: Execute<T>): Execute<T> {
        val keyFor = coalesceKeyFor ?: return { policy.execute { next() } }
        return { policy.executeWithKey(keyFor(input)) { next() } }
    }

    @OptIn(ResilientExperimentalApi::class)
    private fun <T> compose(
        input: Any?,
        cache: InMemoryCachePolicy?,
        coalescing: DefaultCoalescingPolicy?,
        timeout: DefaultTimeoutPolicy?,
        retry: DefaultRetryPolicy?,
        circuitBreaker: DefaultCircuitBreaker?,
        rateLimiter: DefaultRateLimiter?,
        keyedRateLimiter: KeyedRateLimiter<Any?, *>?,
        bulkhead: DefaultBulkhead?,
        hedging: DefaultHedgingPolicy?,
        chaos: DefaultChaosPolicy?,
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
                        { next: Execute<T> -> cached(policy, input, next) }
                    }

                    PolicyType.COALESCE -> coalescing?.let { policy ->
                        { next: Execute<T> -> coalesced(policy, input, next) }
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

                    PolicyType.KEYED_RATE_LIMITER -> keyedRateLimiter?.let { policy ->
                        { next: Execute<T> -> suspend { policy.execute(input) { next() } } }
                    }

                    PolicyType.BULKHEAD -> bulkhead?.let { policy ->
                        { next: Execute<T> -> suspend { policy.execute { next() } } }
                    }

                    PolicyType.HEDGING -> hedging?.let { policy ->
                        { next: Execute<T> -> suspend { policy.execute { next() } } }
                    }

                    PolicyType.CHAOS -> chaos?.let { policy ->
                        { next: Execute<T> -> suspend { policy.execute { next() } } }
                    }
                }
            }
        } else {
            buildList {
                if (chaos != null) add { next -> { chaos.execute { next() } } }
                if (hedging != null) add { next -> { hedging.execute { next() } } }
                if (bulkhead != null) add { next -> { bulkhead.execute { next() } } }
                if (keyedRateLimiter != null) add { next -> { keyedRateLimiter.execute(input) { next() } } }
                if (rateLimiter != null) add { next -> { rateLimiter.execute { next() } } }
                if (circuitBreaker != null) add { next -> { circuitBreaker.execute { next() } } }
                if (retry != null) add { next -> { retry.execute { next() } } }
                if (timeout != null) add { next -> { timeout.execute { next() } } }
                if (coalescing != null) add { next -> coalesced(coalescing, input, next) }
                if (cache != null) add { next -> cached(cache, input, next) }
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
