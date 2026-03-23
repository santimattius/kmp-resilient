package com.santimattius.resilient.test

import com.santimattius.resilient.ResilientPolicy
import com.santimattius.resilient.composition.ResilientScope
import com.santimattius.resilient.composition.resilient
import com.santimattius.resilient.retry.ExponentialBackoff
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Convenience builders for creating [ResilientPolicy] instances with common test configurations.
 *
 * These builders reduce boilerplate in tests by providing sensible defaults for resilience policies.
 */
object PolicyBuilders {

    /**
     * Creates a [ResilientPolicy] with a retry policy configured for fast testing.
     *
     * **Default configuration:**
     * - `maxAttempts = 3`
     * - `backoffStrategy = ExponentialBackoff(initialDelay = 10ms, maxDelay = 100ms, factor = 2.0)`
     * - `shouldRetry = { true }` (retries all exceptions)
     *
     * @param scope The [ResilientScope] for the policy (typically a [TestResilientScope]).
     * @param maxAttempts Maximum number of retry attempts. Default is 3.
     * @param initialDelay Initial backoff delay. Default is 10ms.
     * @param maxDelay Maximum backoff delay. Default is 100ms.
     * @param shouldRetry Predicate to determine if an exception should trigger a retry. Default retries all.
     * @return A configured [ResilientPolicy] with retry.
     */
    fun retryPolicy(
        scope: ResilientScope,
        maxAttempts: Int = 3,
        initialDelay: Duration = 10.milliseconds,
        maxDelay: Duration = 100.milliseconds,
        shouldRetry: (Throwable) -> Boolean = { true }
    ): ResilientPolicy = resilient(scope) {
        retry {
            this.maxAttempts = maxAttempts
            this.shouldRetry = shouldRetry
            this.backoffStrategy = ExponentialBackoff(
                initialDelay = initialDelay,
                maxDelay = maxDelay,
                factor = 2.0
            )
        }
    }

    /**
     * Creates a [ResilientPolicy] with a timeout policy configured for fast testing.
     *
     * **Default configuration:**
     * - `timeout = 1.second`
     *
     * @param scope The [ResilientScope] for the policy.
     * @param timeout The timeout duration. Default is 1 second.
     * @return A configured [ResilientPolicy] with timeout.
     */
    fun timeoutPolicy(
        scope: ResilientScope,
        timeout: Duration = 1.seconds
    ): ResilientPolicy = resilient(scope) {
        timeout { this.timeout = timeout }
    }

    /**
     * Creates a [ResilientPolicy] with a circuit breaker configured for fast testing.
     *
     * **Default configuration:**
     * - `failureThreshold = 3`
     * - `successThreshold = 2`
     * - `halfOpenMaxCalls = 1`
     * - `timeout = 5.seconds`
     *
     * @param scope The [ResilientScope] for the policy.
     * @param failureThreshold Number of failures to open the circuit. Default is 3.
     * @param successThreshold Number of successes to close the circuit from half-open. Default is 2.
     * @param timeout Duration the circuit stays open. Default is 5 seconds.
     * @return A configured [ResilientPolicy] with circuit breaker.
     */
    fun circuitBreakerPolicy(
        scope: ResilientScope,
        failureThreshold: Int = 3,
        successThreshold: Int = 2,
        timeout: Duration = 5.seconds
    ): ResilientPolicy = resilient(scope) {
        circuitBreaker {
            this.failureThreshold = failureThreshold
            this.successThreshold = successThreshold
            this.halfOpenMaxCalls = 1
            this.timeout = timeout
        }
    }

    /**
     * Creates a [ResilientPolicy] with a bulkhead configured for fast testing.
     *
     * **Default configuration:**
     * - `maxConcurrentCalls = 2`
     * - `maxWaitingCalls = 4`
     *
     * @param scope The [ResilientScope] for the policy.
     * @param maxConcurrentCalls Maximum number of concurrent executions. Default is 2.
     * @param maxWaitingCalls Maximum number of waiting calls. Default is 4.
     * @return A configured [ResilientPolicy] with bulkhead.
     */
    fun bulkheadPolicy(
        scope: ResilientScope,
        maxConcurrentCalls: Int = 2,
        maxWaitingCalls: Int = 4
    ): ResilientPolicy = resilient(scope) {
        bulkhead {
            this.maxConcurrentCalls = maxConcurrentCalls
            this.maxWaitingCalls = maxWaitingCalls
        }
    }

    /**
     * Creates a [ResilientPolicy] with a rate limiter configured for fast testing.
     *
     * **Default configuration:**
     * - `maxCalls = 5`
     * - `period = 1.second`
     *
     * @param scope The [ResilientScope] for the policy.
     * @param maxCalls Maximum number of calls allowed in the period. Default is 5.
     * @param period Time period for rate limiting. Default is 1 second.
     * @return A configured [ResilientPolicy] with rate limiter.
     */
    fun rateLimiterPolicy(
        scope: ResilientScope,
        maxCalls: Int = 5,
        period: Duration = 1.seconds
    ): ResilientPolicy = resilient(scope) {
        rateLimiter {
            this.maxCalls = maxCalls
            this.period = period
        }
    }
}
