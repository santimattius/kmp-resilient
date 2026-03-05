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
 * **Refill semantics:** Tokens refill at the **start of each [RateLimiterConfig.period]** (fixed-window refill).
 * When a full period has elapsed since the last refill, the bucket is refilled with [RateLimiterConfig.maxCalls]
 * tokens (capped at maxCalls). So with maxCalls=10 and period=1s, you get at most 10 calls per second,
 * with the "window" aligned to when the bucket was last refilled. This is a single global bucket per policy.
 *
 * Each call to [execute] consumes one token. When no tokens are available:
 * 1. If [RateLimiterConfig.timeoutWhenLimited] is `null`, it immediately throws [RateLimitExceededException].
 * 2. If a timeout is configured, it waits up to that duration for the next refill; if the wait would exceed
 *    the timeout, it throws [RateLimitExceededException].
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
