package com.santimattius.resilient.chaos

import com.santimattius.resilient.annotations.ResilientExperimentalApi
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Default implementation of the chaos policy.
 *
 * Behaviour (in order):
 * 1. If [ChaosConfig.latency] is not `null`, suspends for that duration.
 * 2. If [ChaosConfig.failureRate] `> 0`, rolls a random double; if `< failureRate`, throws the
 *    configured [ChaosConfig.exception] (defaults to `RuntimeException("Chaos fault injected")`).
 * 3. If [ChaosConfig.injectResult] is set and no fault fired, executes the block (for side-effects),
 *    discards its result, and returns [ChaosConfig.injectResult] instead.
 * 4. Otherwise executes the block normally and returns its result.
 *
 * When [ChaosConfig.enabled] is `false`, this policy must NOT be installed — the [resilient] builder
 * handles this guard and will not instantiate [DefaultChaosPolicy] for disabled configs.
 *
 * @param config The [ChaosConfig] driving this policy's behaviour.
 */
@ResilientExperimentalApi
class DefaultChaosPolicy(private val config: ChaosConfig) {

    /**
     * Executes [block], potentially injecting latency, a fault, or an overridden result.
     *
     * @param T The return type of [block].
     * @param block The suspendable operation to execute.
     * @return The result of [block], or the [ChaosConfig.injectResult] override.
     * @throws Throwable The configured [ChaosConfig.exception] when a fault is injected.
     */
    suspend fun <T> execute(block: suspend () -> T): T {
        // Step 1: inject latency if configured
        val latency = config.latency
        if (latency != null && latency.isPositive()) {
            delay(latency)
        }

        // Step 2: inject fault if failureRate > 0 and random roll fires
        if (config.failureRate > 0.0 && Random.nextDouble() < config.failureRate) {
            throw config.exception?.invoke() ?: RuntimeException("Chaos fault injected")
        }

        // Step 3: execute block; optionally override result
        val blockResult = block()
        val override = config.injectResult
        @Suppress("UNCHECKED_CAST")
        return if (override != null) override() as T else blockResult
    }
}
