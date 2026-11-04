package com.santimattius.resilient.composition

import com.santimattius.resilient.PolicyHealthSnapshot
import com.santimattius.resilient.annotations.ResilientExperimentalApi
import com.santimattius.resilient.bulkhead.BulkheadConfig
import com.santimattius.resilient.bulkhead.BulkheadRegistry
import com.santimattius.resilient.cache.CacheConfig
import com.santimattius.resilient.cache.CacheHandle
import com.santimattius.resilient.chaos.ChaosConfig
import com.santimattius.resilient.circuitbreaker.CircuitBreakerConfig
import com.santimattius.resilient.circuitbreaker.CircuitBreakerRegistry
import com.santimattius.resilient.coalescing.CoalesceConfig
import com.santimattius.resilient.fallback.FallbackConfig
import com.santimattius.resilient.hedging.HedgingConfig
import com.santimattius.resilient.ratelimiter.KeyedRateLimiter
import com.santimattius.resilient.ratelimiter.KeyedRateLimiterConfig
import com.santimattius.resilient.ratelimiter.KeyedRateLimiterRegistry
import com.santimattius.resilient.ratelimiter.KeyedRateLimiterSnapshot
import com.santimattius.resilient.ratelimiter.RateLimiterConfig
import com.santimattius.resilient.ratelimiter.RateLimiterRegistry
import com.santimattius.resilient.retry.RetryPolicyConfig
import com.santimattius.resilient.telemetry.ResilientEvent
import com.santimattius.resilient.timeout.TimeoutConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow

/**
 * A resilience policy whose executions receive an explicit, typed [Input].
 *
 * `Input` is plain execution data handed to the protected block; it is unrelated to
 * [ResilientScope], which stays reserved for coroutine lifecycle. A typed policy is backed by the
 * same internal composition engine as [com.santimattius.resilient.ResilientPolicy], so behavior
 * (ordering, retry, timeout, fallback, cache, telemetry, cancellation) is identical, and one
 * execution emits exactly one operation success/failure event.
 *
 * ```kotlin
 * val baseline = resilienceTemplate { timeout { timeout = 2.seconds } }
 * val users = resilientFor<UserId>(scope) { include(baseline) }
 * val name = users.execute(UserId("u-1")) { id -> api.loadName(id) }
 * ```
 *
 * @see resilientFor
 * @see ResilienceTemplate
 */
class TypedResilientPolicy<Input> internal constructor(
    private val engine: ResilientEngine
) : AutoCloseable {

    /**
     * Operational events of this policy. Keyed rate limiting is reported as the keyless
     * [ResilientEvent.KeyedRateLimited]; cache events carry a redacted key. Neither the input nor
     * an input-derived key is emitted.
     */
    val events: SharedFlow<ResilientEvent>
        get() = engine.eventFlow

    /**
     * Handle to the policy-owned cache, or `null` when no cache is configured.
     *
     * Entries are stored under the keys produced by [TypedResilientBuilder.cacheKeyFor], so
     * invalidation uses those same keys.
     */
    val cacheHandle: CacheHandle?
        get() = engine.cacheHandle

    /**
     * Returns a point-in-time health snapshot of the configured policies. It reports the global rate
     * limiter only; use [getKeyedRateLimiterSnapshot] for the keyed one.
     */
    fun getHealthSnapshot(): PolicyHealthSnapshot = engine.healthSnapshot()

    /**
     * Returns a privacy-safe snapshot of the keyed rate limiter (aggregate counts only, never keys
     * or input), or `null` when no keyed rate limiter is configured. The global rate limiter is
     * reported separately by [getHealthSnapshot].
     */
    fun getKeyedRateLimiterSnapshot(): KeyedRateLimiterSnapshot? = engine.keyedRateLimiterSnapshot()

    /**
     * Executes [block] with [input] under the configured resilience policies.
     *
     * The same [input] is passed to every attempt of [block] (retries, hedged attempts). It is used
     * to derive the keyed rate limiter, cache and coalescing keys, and is never placed in events,
     * snapshots or exceptions raised by the library.
     *
     * @param input Explicit execution data handed to [block].
     * @param block The suspendable operation to protect.
     * @return The result of [block], or the value produced by a cache or fallback policy.
     * @throws Exception Any failure not handled by the configured policies, as for
     *   [com.santimattius.resilient.ResilientPolicy.execute]. A keyed limiter at bucket capacity throws
     *   [com.santimattius.resilient.ratelimiter.KeyedRateLimiterCapacityExceededException].
     */
    suspend fun <T> execute(input: Input, block: suspend (Input) -> T): T =
        engine.execute(input) { block(input) }

    /**
     * Releases policy-owned resources (for example the cache cleanup job). State owned by named
     * registries (rate limiters, circuit breakers, bulkheads, keyed rate limiters) is never destroyed,
     * and the outer coroutine scope is not cancelled.
     */
    override fun close() = engine.close()
}

/**
 * DSL receiver of [resilientFor]. Offers the same policy DSL as [ResilientBuilder], plus the
 * input-typed [keyedRateLimiter], [cacheKeyFor] and [coalesceKeyFor], and can [include]
 * context-free [ResilienceTemplate]s regardless of [Input].
 *
 * Template overlay, duplicate named policies and [compositionOrder] follow the rules of
 * [ResilientBuilder.include].
 *
 * @param Input The type of the execution data passed to [TypedResilientPolicy.execute].
 */
@OptIn(ResilientExperimentalApi::class)
class TypedResilientBuilder<Input> internal constructor() {
    internal val delegate = ResilientBuilder()
    internal var cacheKeyFor: ((Input) -> String)? = null
    internal var coalesceKeyFor: ((Input) -> String)? = null
    internal var keyedRateLimiterFactory: ((suspend () -> Unit) -> KeyedRateLimiter<Input, *>)? = null

    /** @see ResilientBuilder.retry */
    fun retry(config: RetryPolicyConfig.() -> Unit) = delegate.retry(config)

    /** @see ResilientBuilder.circuitBreaker */
    fun circuitBreaker(config: CircuitBreakerConfig.() -> Unit) = delegate.circuitBreaker(config)

    /** @see ResilientBuilder.circuitBreakerNamed */
    fun circuitBreakerNamed(
        registry: CircuitBreakerRegistry,
        name: String,
        config: CircuitBreakerConfig.() -> Unit
    ) = delegate.circuitBreakerNamed(registry, name, config)

    /** @see ResilientBuilder.rateLimiter */
    fun rateLimiter(config: RateLimiterConfig.() -> Unit) = delegate.rateLimiter(config)

    /** @see ResilientBuilder.rateLimiterNamed */
    fun rateLimiterNamed(
        registry: RateLimiterRegistry,
        name: String,
        configure: RateLimiterConfig.() -> Unit
    ) = delegate.rateLimiterNamed(registry, name, configure)

    /**
     * Adds a keyed rate limiter whose bucket key is derived from [Input] by
     * [KeyedRateLimiterConfig.keyProvider]. It composes with the global [rateLimiter] in a single
     * `execute(input)` call; their relative order is controlled with [compositionOrder] using
     * [OrderablePolicyType.RATE_LIMITER] and [OrderablePolicyType.KEYED_RATE_LIMITER]
     * (default: global outside keyed). Neither the input nor the key appears in events, snapshots
     * or exceptions.
     *
     * Keyed configuration is input-typed, so it belongs to the typed builder and cannot be part of
     * a context-free [ResilienceTemplate]. Each build creates its own limiter with independent
     * buckets. Cannot be combined with [keyedRateLimiterNamed], and can be configured only once.
     *
     * @param K The type of the bucket key.
     * @param config Configures the [KeyedRateLimiterConfig]; its `keyProvider` is required.
     * @throws IllegalArgumentException if a keyed rate limiter was already configured.
     */
    fun <K> keyedRateLimiter(config: KeyedRateLimiterConfig<Input, K>.() -> Unit) {
        requireKeyedNotConfigured()
        keyedRateLimiterFactory = { onRateLimited ->
            val cfg = KeyedRateLimiterConfig<Input, K>().apply(config)
            val configured = cfg.onRateLimited
            cfg.onRateLimited = {
                onRateLimited()
                configured()
            }
            KeyedRateLimiter(cfg)
        }
    }

    /**
     * Like [keyedRateLimiter] but resolves the limiter from [registry] by [name], so its per-key
     * quotas are shared by every build using the same registry and name. Closing a policy never
     * destroys registry-owned state. The first build that creates the entry wins: later builds reuse
     * it and their [config] differences are ignored, and the [ResilientEvent.KeyedRateLimited]
     * telemetry is wired only by the build that first creates the entry.
     *
     * @param K The type of the bucket key; use one name per input/key type.
     * @param registry The registry that owns the limiter.
     * @param name The name that identifies the shared limiter within [registry].
     * @param config Configures the [KeyedRateLimiterConfig] used when the entry is created.
     * @throws IllegalArgumentException if a keyed rate limiter was already configured.
     */
    fun <K> keyedRateLimiterNamed(
        registry: KeyedRateLimiterRegistry,
        name: String,
        config: KeyedRateLimiterConfig<Input, K>.() -> Unit
    ) {
        requireKeyedNotConfigured()
        keyedRateLimiterFactory = { onRateLimited -> registry.getOrCreate(name, config, onRateLimited) }
    }

    private fun requireKeyedNotConfigured() {
        require(keyedRateLimiterFactory == null) {
            "A keyed rate limiter can only be configured once per policy; choose keyedRateLimiter or keyedRateLimiterNamed."
        }
    }

    /** @see ResilientBuilder.bulkhead */
    fun bulkhead(config: BulkheadConfig.() -> Unit) = delegate.bulkhead(config)

    /** @see ResilientBuilder.bulkheadNamed */
    fun bulkheadNamed(registry: BulkheadRegistry, name: String, config: BulkheadConfig.() -> Unit) =
        delegate.bulkheadNamed(registry, name, config)

    /** @see ResilientBuilder.timeout */
    fun timeout(config: TimeoutConfig.() -> Unit) = delegate.timeout(config)

    /** @see ResilientBuilder.cache */
    fun cache(config: CacheConfig.() -> Unit) = delegate.cache(config)

    /** @see ResilientBuilder.coalesce */
    fun coalesce(config: CoalesceConfig.() -> Unit) = delegate.coalesce(config)

    /**
     * Derives the cache key from the execution [Input]. Required when a [cache] is configured in a
     * typed policy (directly or through an included [ResilienceTemplate]): building without it
     * throws, because the static [CacheConfig.key] would make different inputs share one entry.
     * The derived key is used only internally; cache events report a redacted key.
     *
     * @throws IllegalArgumentException from [resilientFor] when this is set but no [cache] is
     *   configured, or when [cache] is configured without it.
     */
    fun cacheKeyFor(keyFor: (Input) -> String) {
        cacheKeyFor = keyFor
    }

    /**
     * Derives the coalescing key from the execution [Input]. Required when [coalesce] is configured
     * in a typed policy (directly or through an included template); concurrent executions with the
     * same key share one in-flight call, different keys run separately. The key is used only
     * internally and never appears in events.
     *
     * @throws IllegalArgumentException from [resilientFor] when [coalesce] is not configured.
     */
    fun coalesceKeyFor(keyFor: (Input) -> String) {
        coalesceKeyFor = keyFor
    }

    /** @see ResilientBuilder.fallback */
    fun fallback(config: FallbackConfig<Any?>) = delegate.fallback(config)

    /** @see ResilientBuilder.hedging */
    fun hedging(config: HedgingConfig.() -> Unit) = delegate.hedging(config)

    /** @see ResilientBuilder.chaos */
    @ResilientExperimentalApi
    fun chaos(block: ChaosConfig.() -> Unit) = delegate.chaos(block)

    /** @see ResilientBuilder.compositionOrder */
    @ResilientExperimentalApi
    fun compositionOrder(order: List<OrderablePolicyType>) = delegate.compositionOrder(order)

    /**
     * Includes a context-free [ResilienceTemplate] with lexical overlay semantics, exactly like
     * [ResilientBuilder.include]. The template is not tied to [Input], so one template can be
     * included in typed policies of unrelated input types. Included configurations are cloned, later
     * blocks win property by property, and the last explicit [compositionOrder] wins.
     */
    fun include(template: ResilienceTemplate) = delegate.include(template)
}

/**
 * Builds a [TypedResilientPolicy] for executions that carry an explicit [Input].
 *
 * Uses the same composition engine and default ordering as [resilient]. The `resilientScope`
 * governs coroutine lifecycle only; the execution data is the [Input] passed to
 * [TypedResilientPolicy.execute].
 *
 * @param resilientScope The [ResilientScope] in which background jobs of the policies run.
 * @param block Configures the policies of this typed policy.
 * @return A new [TypedResilientPolicy] with its own runtime state, except for registry-owned state.
 * @throws IllegalArgumentException if [TypedResilientBuilder.cache] or [TypedResilientBuilder.coalesce]
 *   is configured without the matching `cacheKeyFor`/`coalesceKeyFor`, if a key function is set without
 *   its policy, or if the configuration is otherwise invalid.
 */
fun <Input> resilientFor(
    resilientScope: ResilientScope,
    block: TypedResilientBuilder<Input>.() -> Unit
): TypedResilientPolicy<Input> {
    val builder = TypedResilientBuilder<Input>().apply(block)
    val delegate = builder.delegate
    require(delegate.cacheConfig == null || builder.cacheKeyFor != null) {
        "A typed policy with cache { } must derive the key from its input: call cacheKeyFor { input -> ... } " +
            "in the resilientFor block. A static key would share one entry across different inputs."
    }
    require(delegate.coalesceConfig == null || builder.coalesceKeyFor != null) {
        "A typed policy with coalesce { } must derive the key from its input: call coalesceKeyFor { input -> ... } " +
            "in the resilientFor block. A static key would merge in-flight calls of different inputs."
    }
    require(builder.cacheKeyFor == null || delegate.cacheConfig != null) {
        "cacheKeyFor was set but no cache { } is configured."
    }
    require(builder.coalesceKeyFor == null || delegate.coalesceConfig != null) {
        "coalesceKeyFor was set but no coalesce { } is configured."
    }
    @Suppress("UNCHECKED_CAST")
    val cacheKeyFor = builder.cacheKeyFor as ((Any?) -> String)?
    @Suppress("UNCHECKED_CAST")
    val coalesceKeyFor = builder.coalesceKeyFor as ((Any?) -> String)?
    @Suppress("UNCHECKED_CAST")
    val keyedFactory = builder.keyedRateLimiterFactory
        as ((suspend () -> Unit) -> KeyedRateLimiter<Any?, *>)?
    return TypedResilientPolicy(ResilientEngine(builder.delegate, resilientScope, keyedFactory, cacheKeyFor, coalesceKeyFor))
}

/**
 * Builds a [TypedResilientPolicy] bound to the lifecycle of this [CoroutineScope].
 * Closing the policy does not cancel this scope; cancelling this scope stops the policy's jobs.
 *
 * @param block Configures the policies of this typed policy.
 * @return A new [TypedResilientPolicy]; see the [ResilientScope] overload for the validation rules.
 */
fun <Input> CoroutineScope.resilientFor(
    block: TypedResilientBuilder<Input>.() -> Unit
): TypedResilientPolicy<Input> = resilientFor(this.asResilientScope(), block)
