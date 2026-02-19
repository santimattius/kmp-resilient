package com.santimattius.resilient.ratelimiter

import com.santimattius.resilient.circuitbreaker.SystemTimeSource
import com.santimattius.resilient.circuitbreaker.TimeSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds


/**
 * A default, thread-safe implementation of the [RateLimiter] interface that uses a token-bucket algorithm.
 *
 * This rate limiter controls the rate of operation execution by managing a pool of "tokens".
 * Each call to [execute] attempts to consume one token. Tokens are replenished periodically
 * according to the settings in the provided [RateLimiterConfig].
 *
 * When `execute` is called and no tokens are available, the rate limiter calculates the time
 * until the next token becomes available. It then has two behaviors based on the configuration:
 * 1. If [RateLimiterConfig.timeoutWhenLimited] is `null`, it immediately throws a [RateLimitExceededException].
 * 2. If a timeout is configured, it will wait for the required duration. However, if this wait time
 *    exceeds the configured timeout, it will throw a [RateLimitExceededException] instead of waiting.
 *
 * This implementation is thread-safe. Access to the token count is synchronized.
 *
 * @param config The configuration for this rate limiter, specifying the maximum number of calls
 *               per period and the timeout behavior.
 * @param onRateLimited A suspendable lambda that is invoked when a call is rate-limited and forced to wait.
 *                      It receives the duration of the wait as a parameter. This is called before the wait begins.
 * @param timeSource A source for the current time, used for calculating token refills. Defaults to the system clock.
 */
class DefaultRateLimiter(
    private val config: RateLimiterConfig,
    private val onRateLimited: suspend (Duration) -> Unit = {},
    private val timeSource: TimeSource = SystemTimeSource
) : RateLimiter {

    init {
        require(config.maxCalls >= 1) {
            "maxCalls must be >= 1, got ${config.maxCalls}"
        }
    }

    private val mutex = Mutex()
    private var tokens: Int = config.maxCalls
    private var lastRefillMs: Long = timeSource.currentTimeMillis()

    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun <T> execute(block: suspend () -> T): T {
        // Try to acquire token immediately
        val waitDuration = mutex.withLock {
            refillTokens()
            if (tokens > 0) {
                tokens--
                return@withLock null
            } else {
                val nextInMs = remainingMillisInWindow()
                nextInMs.milliseconds
            }
        }

        // If we need to wait, handle timeout and wait
        if (waitDuration != null) {
            onRateLimited(waitDuration)
            config.onRateLimited()

            when (val timeout = config.timeoutWhenLimited) {
                null -> throw RateLimitExceededException(waitDuration)
                else -> {
                    if (waitDuration > timeout) {
                        throw RateLimitExceededException(waitDuration)
                    }
                    coroutineScope {
                        val delayDeferred = async { delay(waitDuration.inWholeMilliseconds) }
                        select {
                            delayDeferred.onAwait { }
                            onTimeout(timeout.inWholeMilliseconds) {
                                delayDeferred.cancel()
                                throw RateLimitExceededException(waitDuration)
                            }
                        }
                        delayDeferred.await()
                    }

                    mutex.withLock {
                        val periodMs = config.period.inWholeMilliseconds
                        lastRefillMs += periodMs
                        // Add tokens for the period that just started
                        tokens = min(config.maxCalls, tokens + config.maxCalls)
                        // Now consume a token
                        if (tokens > 0) {
                            tokens--
                        } else {
                            // This shouldn't happen, but handle it gracefully
                            throw RateLimitExceededException(remainingMillisInWindow().milliseconds)
                        }
                    }
                }
            }
        }

        return block()
    }

    private fun refillTokens() {
        val now = timeSource.currentTimeMillis()
        val periodMs = config.period.inWholeMilliseconds
        if (now - lastRefillMs >= periodMs) {
            val periods = ((now - lastRefillMs) / periodMs).toInt()
            lastRefillMs += periods * periodMs
            tokens = min(config.maxCalls, tokens + periods * config.maxCalls)
        }
    }

    private fun remainingMillisInWindow(): Long {
        val periodMs = config.period.inWholeMilliseconds
        val now = timeSource.currentTimeMillis()
        val elapsed = now - lastRefillMs
        return (periodMs - (elapsed % periodMs)).coerceAtLeast(0)
    }
}
