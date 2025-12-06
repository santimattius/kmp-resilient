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
 *   This can be used for logging or other side effects. It is invoked before the call is either rejected or queued.
 */
class RateLimiterConfig {
    var maxCalls: Int = 100
    var period: Duration = 1.seconds
    var timeoutWhenLimited: Duration? = null
    var onRateLimited: suspend () -> Unit = { }
}

class RateLimitExceededException(
    val retryAfter: Duration?
) : Exception()
