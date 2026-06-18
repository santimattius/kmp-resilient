package com.santimattius.resilient.chaos

import com.santimattius.resilient.annotations.ResilientExperimentalApi
import kotlin.time.Duration

/**
 * Configuration for the chaos policy, used to inject faults, latency, or overridden results
 * into an operation for testing and resilience validation.
 *
 * **Production safeguard**: [enabled] defaults to `false`. The chaos wrapper is only installed
 * when [enabled] is explicitly set to `true`. This ensures zero overhead in production.
 *
 * Example:
 * ```kotlin
 * resilient(scope) {
 *     chaos {
 *         enabled = true
 *         failureRate = 0.3            // 30% of calls throw
 *         latency = 200.milliseconds   // inject 200ms delay before every call
 *         exception = { IOException("chaos: simulated network failure") }
 *     }
 * }
 * ```
 *
 * @see DefaultChaosPolicy
 */
@ResilientExperimentalApi
class ChaosConfig {
    /**
     * Whether the chaos policy is active.
     *
     * Defaults to `false` — when `false`, no wrapper is installed and the block executes normally.
     * MUST be set to `true` explicitly to activate any chaos behaviour.
     */
    var enabled: Boolean = false

    /**
     * Probability of injecting a fault on each call. Range: `0.0` (never) to `1.0` (always).
     *
     * When a fault fires, [exception] is thrown and the user block is NOT executed.
     */
    var failureRate: Double = 0.0

    /**
     * Optional delay to inject before executing the block. `null` means no delay.
     *
     * The delay is applied regardless of whether a fault fires.
     */
    var latency: Duration? = null

    /**
     * Factory for the exception to throw when a fault is injected.
     *
     * Defaults to `null`, which causes a [RuntimeException] with the message
     * `"Chaos fault injected"` to be thrown.
     */
    var exception: (() -> Throwable)? = null

    /**
     * If set, overrides the block's return value when no fault fires.
     *
     * The user block is still called (to allow side-effects if needed), but its result is
     * discarded and the value produced by this factory is returned instead.
     * `null` means no result override.
     */
    var injectResult: (() -> Any?)? = null
}
