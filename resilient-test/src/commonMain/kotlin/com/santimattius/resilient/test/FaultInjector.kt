package com.santimattius.resilient.test

import kotlinx.coroutines.delay
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Fault injection helper for testing resilience policies.
 *
 * Use this in tests to inject artificial delays, failures, or intermittent behavior
 * to validate that your policies (retry, circuit breaker, timeout, etc.) handle them correctly.
 *
 * **Example:**
 * ```kotlin
 * val injector = FaultInjector.builder()
 *     .failureRate(0.3)
 *     .delay(50.milliseconds)
 *     .build()
 *
 * val result = injector.execute {
 *     fetchData() // may throw or delay
 * }
 * ```
 *
 * @property failureRate Probability of throwing [exception] on each call (0.0 = never, 1.0 = always).
 * @property exception The exception to throw when a failure is injected.
 * @property delay Fixed delay added before executing [block] (simulates slow network/DB).
 * @property delayJitter If `true`, actual delay is randomized ±20% around [delay].
 */
class FaultInjector(
    private val failureRate: Double = 0.0,
    private val exception: () -> Throwable = { FaultInjectedException() },
    private val delay: Duration = Duration.ZERO,
    private val delayJitter: Boolean = false
) {
    init {
        require(failureRate in 0.0..1.0) {
            "failureRate must be in [0.0, 1.0], got $failureRate"
        }
    }

    /**
     * Executes [block], potentially injecting a delay and/or failure according to configuration.
     *
     * @param T The return type of [block].
     * @param block The suspendable operation to execute (may be wrapped with injected faults).
     * @return The result of [block] if no failure is injected.
     * @throws Throwable The configured [exception] if a failure is injected, or any exception from [block].
     */
    suspend fun <T> execute(block: suspend () -> T): T {
        if (delay.isPositive()) {
            val actualDelay = if (delayJitter) {
                (delay.inWholeMilliseconds * (0.8 + Random.nextDouble(0.4))).toLong().milliseconds
            } else {
                delay
            }
            delay(actualDelay)
        }

        if (failureRate > 0.0 && Random.nextDouble() < failureRate) {
            throw exception()
        }

        return block()
    }

    companion object {
        /**
         * Returns a builder for configuring a [FaultInjector].
         */
        fun builder(): Builder = Builder()
    }

    /**
     * Builder for [FaultInjector].
     */
    class Builder {
        private var failureRate: Double = 0.0
        private var exception: () -> Throwable = { FaultInjectedException() }
        private var delay: Duration = Duration.ZERO
        private var delayJitter: Boolean = false

        /**
         * Sets the probability of throwing an exception on each call.
         * @param rate Value in [0.0, 1.0]. Default is 0.0 (no failures).
         */
        fun failureRate(rate: Double) = apply { this.failureRate = rate }

        /**
         * Sets the exception factory to use when a failure is injected.
         * @param block Lambda that produces the exception to throw.
         */
        fun exception(block: () -> Throwable) = apply { this.exception = block }

        /**
         * Sets a fixed delay added before executing the block.
         * @param duration The delay duration. Default is [Duration.ZERO].
         */
        fun delay(duration: Duration) = apply { this.delay = duration }

        /**
         * Enables or disables jitter on the delay (±20% randomization).
         * @param enable `true` to add jitter, `false` for fixed delay. Default is `false`.
         */
        fun delayJitter(enable: Boolean) = apply { this.delayJitter = enable }

        /**
         * Builds the [FaultInjector] with the configured settings.
         */
        fun build(): FaultInjector = FaultInjector(failureRate, exception, delay, delayJitter)
    }
}

/**
 * Exception thrown by [FaultInjector] when a failure is injected.
 */
class FaultInjectedException(message: String = "Fault injected by FaultInjector") : Exception(message)
