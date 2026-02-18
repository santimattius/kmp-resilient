package com.santimattius.resilient.timeout

import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException


/**
 * A [TimeoutPolicy] that executes a given block of code within a specified time limit.
 * If the execution time exceeds the configured timeout, it triggers a timeout event and
 * re-throws a [TimeoutCancellationException].
 *
 * This implementation uses Kotlin Coroutines' `withTimeout` to enforce the time limit.
 *
 * @param config The [TimeoutConfig] containing the timeout duration and the action to perform on timeout.
 */
class DefaultTimeoutPolicy(
    private val config: TimeoutConfig
) : TimeoutPolicy {

    init {
        require(config.timeout.isPositive()) {
            "timeout must be positive, got ${config.timeout}"
        }
    }

    override suspend fun <T> execute(block: suspend () -> T): T {
        return try {
            withTimeout(config.timeout.inWholeMilliseconds) { block() }
        } catch (e: TimeoutCancellationException) {
            config.onTimeout()
            throw e
        }
    }
}
