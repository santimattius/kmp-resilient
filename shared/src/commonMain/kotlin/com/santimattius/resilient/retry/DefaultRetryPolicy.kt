package com.santimattius.resilient.retry

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A default implementation of the [RetryPolicy] interface.
 *
 * This class orchestrates the execution of a code block with retry logic based on the provided
 * [RetryPolicyConfig]. It will attempt to execute the block up to `maxAttempts` times.
 * If an exception occurs, it consults the `shouldRetry` predicate and, if a retry is warranted,
 * it calls `onRetry`, waits for a duration determined by the `backoffStrategy`, and then tries again.
 * If all attempts fail, the last caught exception is rethrown.
 *
 * @param config The configuration that defines the retry behavior, including the number of attempts,
 *               the backoff strategy, and conditions for retrying.
 * @see RetryPolicy
 * @see RetryPolicyConfig
 */
class DefaultRetryPolicy(
    private val config: RetryPolicyConfig
) : RetryPolicy {

    init {
        require(config.maxAttempts >= 1) {
            "maxAttempts must be >= 1, got ${config.maxAttempts}"
        }
    }

    override suspend fun <T> execute(block: suspend () -> T): T {
        var lastError: Throwable? = null
        var attempt = 0
        while (attempt < config.maxAttempts) {
            try {
                return block()
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                if (!config.shouldRetry(e)) throw e
                lastError = e
                attempt++
                if (attempt >= config.maxAttempts) break
                config.onRetry(attempt, e)
                config.backoffStrategy.delay(attempt)
            }
        }
        throw lastError ?: IllegalStateException("RetryPolicy failed with unknown error")
    }
}

/**
 * A backoff strategy that increases the delay between retries exponentially.
 *
 * This strategy calculates the delay for each attempt using the formula:
 * `delay = min(maxDelay, initialDelay * factor^(attempt - 1))`
 *
 * If jitter is enabled, a random variance is added to the delay to prevent
 * multiple clients from retrying simultaneously (the "thundering herd" problem).
 *
 * @property initialDelay The base delay for the first retry attempt.
 * @property maxDelay The maximum delay that will be used, capping the exponential growth.
 * @property factor The multiplier for each subsequent delay. Must be >= 1.0.
 * @property jitter If true, adds a random +/- 20% variance to the delay duration.
 */
class ExponentialBackoff(
    val initialDelay: Duration = 100.milliseconds,
    val maxDelay: Duration = 10.seconds,
    val factor: Double = 2.0,
    val jitter: Boolean = true
) : BackoffStrategy {
    override suspend fun delay(attempt: Int) {
        val factorPow = factor.powSafe(attempt - 1)
        val rawMs = (initialDelay.inWholeMilliseconds * factorPow).toLong()
        val boundedMs = min(rawMs, maxDelay.inWholeMilliseconds)
        delay(boundedMs.milliseconds.jittered(jitter))
    }
}

/**
 * A backoff strategy that waits a constant, linear amount of time between each retry attempt.
 * The delay is the same for every attempt, regardless of the attempt number.
 *
 * @property delay The fixed duration to wait before the next retry attempt. Defaults to 1 second.
 */
class LinearBackoff(
    val delay: Duration = 1.seconds
) : BackoffStrategy {
    override suspend fun delay(attempt: Int) {
        delay(delay.inWholeMilliseconds)
    }
}

/**
 * A backoff strategy that waits for a fixed amount of time between retry attempts.
 * The delay is the same regardless of the attempt number.
 *
 * @property delay The fixed duration to wait before the next retry attempt. Defaults to 1 second.
 */
class FixedBackoff(
    val delay: Duration = 1.seconds
) : BackoffStrategy {
    override suspend fun delay(attempt: Int) {
        delay(delay.inWholeMilliseconds)
    }
}

private fun Double.powSafe(exp: Int): Double {
    var result = 1.0
    repeat(exp.coerceAtLeast(0)) { result *= this }
    return result
}

private fun Duration.jittered(enable: Boolean): Duration =
    if (!enable) this else (inWholeMilliseconds * (0.8 + Random.nextDouble(0.4))).milliseconds
