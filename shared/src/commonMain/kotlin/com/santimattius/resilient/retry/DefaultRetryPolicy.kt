package com.santimattius.resilient.retry

import com.santimattius.resilient.RetrySnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
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
 * If an exception occurs, it consults [RetryPolicyConfig.shouldRetry] and, if a retry is warranted,
 * it calls [RetryPolicyConfig.onRetry], waits per [RetryPolicyConfig.backoffStrategy], and tries again.
 * If [block] completes successfully and [RetryPolicyConfig.shouldRetryResult] returns `true`, the same retry
 * path is used (with [RetryableResultException] passed to [RetryPolicyConfig.onRetry]).
 * If all attempts fail with exceptions, the last caught exception is rethrown.
 * If attempts are exhausted due to [RetryPolicyConfig.shouldRetryResult], the last returned value is returned.
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

    /**
     * Returns a point-in-time snapshot of the retry policy configuration.
     */
    fun snapshot(): RetrySnapshot = RetrySnapshot(maxAttempts = config.maxAttempts)

    override suspend fun <T> execute(block: suspend () -> T): T {
        var lastError: Throwable? = null
        var attempt = 0
        val timeout = config.perAttemptTimeout
        val shouldRetryResult = config.shouldRetryResult
        while (attempt < config.maxAttempts) {
            try {
                val result = if (timeout != null && timeout.isPositive()) {
                    withTimeout(timeout.inWholeMilliseconds) { block() }
                } else {
                    block()
                }

                if (shouldRetryResult == null || !shouldRetryResult(result as Any?)) {
                    return result
                }

                val synthetic = RetryableResultException(lastValue = result as Any?)
                lastError = synthetic
                attempt++
                if (attempt >= config.maxAttempts) {
                    return result
                }
                config.onRetry(attempt, synthetic)
                config.backoffStrategy.delay(attempt)
            } catch (e: Throwable) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
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

/**
 * A stateless implementation of the AWS decorrelated jitter backoff strategy.
 *
 * The delay for each attempt is computed as:
 * `min(cap, random(base, base * 3^(attempt-1)))`
 *
 * This formula approximates the AWS decorrelated jitter pattern without requiring
 * mutable per-instance state, making it safe to share across concurrent executions.
 *
 * @property base The minimum possible delay. Must be positive.
 * @property cap The maximum possible delay. Must be >= [base].
 * @throws IllegalArgumentException if [base] is not positive or if [cap] < [base].
 */
class DecorrelatedJitterBackoff(
    val base: Duration,
    val cap: Duration,
) : BackoffStrategy {

    init {
        require(base > Duration.ZERO) { "base must be positive, got $base" }
        require(cap >= base) { "cap must be >= base, but cap=$cap < base=$base" }
    }

    /**
     * Computes the delay in milliseconds for the given [attempt] (1-based).
     * The result is always in the range [[base], [cap]].
     * Exposed as an internal function to allow unit-testing the pure calculation.
     */
    internal fun computeDelayMs(attempt: Int): Long {
        val baseMs = base.inWholeMilliseconds
        val capMs = cap.inWholeMilliseconds
        val upperBound = (baseMs * JITTER_MULTIPLIER.powSafe(attempt - 1)).toLong().coerceIn(baseMs, capMs)
        return if (baseMs >= upperBound) baseMs else Random.nextLong(baseMs, upperBound + 1)
    }

    private companion object {
        /** AWS decorrelated-jitter multiplier: upper bound grows as 3^(attempt-1). */
        const val JITTER_MULTIPLIER = 3.0
    }

    override suspend fun delay(attempt: Int) {
        delay(computeDelayMs(attempt).milliseconds)
    }
}

private fun Double.powSafe(exp: Int): Double {
    var result = 1.0
    repeat(exp.coerceAtLeast(0)) { result *= this }
    return result
}

private fun Duration.jittered(enable: Boolean): Duration =
    if (!enable) this else (inWholeMilliseconds * (0.8 + Random.nextDouble(0.4))).milliseconds
