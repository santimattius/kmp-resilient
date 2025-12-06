package com.santimattius.resilient.retry

/**
 * Defines a contract for implementing resilience strategies that automatically retry
 * a failed operation.
 *
 * Implementations of this interface encapsulate the logic for retrying a suspendable
 * code block (`block`) based on a configured set of rules, such as the maximum
 * number of attempts, the backoff strategy between retries, and conditions for
 * when a retry should occur.
 */
interface RetryPolicy {
    suspend fun <T> execute(block: suspend () -> T): T
}

/**
 * Configuration class for defining the behavior of a [RetryPolicy].
 *
 * This class holds the parameters that control how and when retries are performed.
 * It is typically used within a DSL to configure a retry mechanism.
 *
 * @property maxAttempts The maximum number of times an operation will be attempted.
 *   Includes the initial attempt. Defaults to 3.
 * @property backoffStrategy The strategy to determine the delay between retry attempts.
 *   Defaults to [ExponentialBackoff].
 * @property shouldRetry A predicate to decide if a retry should be performed based on the
 *   [Throwable] that was caught. Defaults to a lambda that always returns `true`,
 *   meaning any exception will trigger a retry.
 * @property onRetry A suspendable callback function that is executed before each retry attempt.
 *   It receives the current attempt number and the error that caused the failure.
 *   Defaults to an empty lambda.
 */
class RetryPolicyConfig {
    var maxAttempts: Int = 3
    var backoffStrategy: BackoffStrategy = ExponentialBackoff()
    var shouldRetry: (Throwable) -> Boolean = { true }
    var onRetry: suspend (attempt: Int, error: Throwable) -> Unit = { _, _ -> }
}

sealed interface BackoffStrategy {
    suspend fun delay(attempt: Int)
}
