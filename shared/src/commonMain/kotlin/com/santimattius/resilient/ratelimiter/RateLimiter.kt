package com.santimattius.resilient.ratelimiter

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * An interface that provides rate-limiting functionality for executing operations.
 *
 * Implementations of this interface decorate a suspend function call, controlling the rate at which
 * it can be executed. If the call rate exceeds the configured limit, the `execute` method
 * will either suspend until permission is granted or throw a [RateLimitExceededException].
 */
interface RateLimiter {
    /**
     * Executes [block] after obtaining a permit from the rate limiter; may suspend or throw if the limit is exceeded.
     * @param T The return type of the block.
     * @param block The suspendable operation to run after a permit is acquired.
     * @return The result of [block].
     * @throws RateLimitExceededException When the rate limit is exceeded and no permit is obtained (e.g. no timeout configured or wait exceeded timeout).
     */
    suspend fun <T> execute(block: suspend () -> T): T
}

/**
 * Configuration for a [RateLimiter].
 *
 * This class holds the settings that define the behavior of the rate limiter,
 * such as the number of permitted calls within a specific time period.
 *
 * @property maxCalls The maximum number of calls that are permitted in a given `period`. Defaults to 100.
 * @property period The time period during which `maxCalls` are allowed. Defaults to 1 second.
 * @property timeoutWhenLimited The optional duration to wait for a permit if the rate limit is exceeded.
 *   If null (the default), calls will fail immediately with a [RateLimitExceededException] when the limit is reached.
 *   If set, the call will be suspended for up to this duration, waiting for a permit to become available.
 * @property onRateLimited A suspendable lambda function that is executed when a call is rate-limited.
 *   This can be used for logging or other side effects. When the policy is built via
 *   [com.santimattius.resilient.composition.resilient], the builder injects a telemetry callback that runs first
 *   (emitting [com.santimattius.resilient.telemetry.ResilientEvent.RateLimited]), then [onRateLimited] is invoked.
 */
class RateLimiterConfig {
    var maxCalls: Int = 100
    var period: Duration = 1.seconds
    var timeoutWhenLimited: Duration? = null
    var onRateLimited: suspend () -> Unit = { }
}

/**
 * Exception thrown when a call is rejected by the rate limiter because the rate limit has been exceeded.
 *
 * @property retryAfter Suggested duration to wait before retrying, or null if not available.
 */
class RateLimitExceededException(
    val retryAfter: Duration?
) : Exception()
