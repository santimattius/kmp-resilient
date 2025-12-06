package com.santimattius.resilient.ratelimiter

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.min
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime


/**
 * A default implementation of the [RateLimiter] interface that uses a token-bucket algorithm.
 *
 * This rate limiter controls the rate of execution of operations by maintaining a number of "tokens".
 * Each call to [execute] consumes one token. The tokens are refilled periodically based on the
 * provided [RateLimiterConfig].
 *
 * When a call is made and no tokens are available, the rate limiter calculates the time until the
 * next token will be available. It will then either wait for that duration or, if the wait time
 * exceeds the configured [RateLimiterConfig.timeoutWhenLimited], it will immediately throw a
 * [RateLimitExceededException].
 *
 * This implementation is thread-safe.
 *
 * @property config The configuration for this rate limiter, specifying the maximum number of calls
 *                  per period and the timeout behavior.
 * @property onRateLimited A suspendable lambda that is invoked when a call is rate-limited and forced to wait.
 *                         It receives the duration of the wait as a parameter.
 */
class DefaultRateLimiter(
    private val config: RateLimiterConfig,
    private val onRateLimited: suspend (Duration) -> Unit = {}
) : RateLimiter {

    private val mutex = Mutex()
    private var tokens: Int = config.maxCalls
    private var lastRefillMs: Long = currentEpochMillis()

    override suspend fun <T> execute(block: suspend () -> T): T {
        val wait = mutex.withLock {
            refillTokens()
            if (tokens > 0) {
                tokens--
                0.milliseconds
            } else {
                val nextInMs = remainingMillisInWindow()
                val waitDuration = nextInMs.milliseconds
                if (config.timeoutWhenLimited != null && waitDuration > config.timeoutWhenLimited!!) {
                    throw RateLimitExceededException(waitDuration)
                }
                waitDuration
            }
        }
        if (wait.isPositive()) {
            onRateLimited(wait)
            config.onRateLimited()
            delay(wait.inWholeMilliseconds)
        }
        return block()
    }

    private fun refillTokens() {
        val now = currentEpochMillis()
        val periodMs = config.period.inWholeMilliseconds
        if (now - lastRefillMs >= periodMs) {
            val periods = ((now - lastRefillMs) / periodMs).toInt()
            lastRefillMs += periods * periodMs
            tokens = min(config.maxCalls, tokens + periods * config.maxCalls)
        }
    }

    private fun remainingMillisInWindow(): Long {
        val periodMs = config.period.inWholeMilliseconds
        val now = currentEpochMillis()
        val elapsed = now - lastRefillMs
        return (periodMs - (elapsed % periodMs)).coerceAtLeast(0)
    }

    @OptIn(ExperimentalTime::class)
    private fun currentEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()
}
