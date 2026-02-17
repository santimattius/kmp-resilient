package com.santimattius.resilient.telemetry

import com.santimattius.resilient.circuitbreaker.CircuitState
import kotlin.time.Duration


/**
 * Represents a set of telemetry events that can be emitted by resilience policies
 * during the execution of an operation.
 *
 * These events provide insights into the behavior of policies like Retry, Circuit Breaker,
 * Rate Limiter, and Bulkhead. Consumers can collect and process these events to monitor
 * system health, diagnose issues, or trigger alerts.
 */
sealed interface ResilientEvent {


    /**
     * Emitted on each retry attempt.
     *
     * @property attempt The current retry attempt number (1-based).
     * @property error The error that triggered this retry attempt.
     */
    data class RetryAttempt(val attempt: Int, val error: Throwable) : ResilientEvent

    /**
     * Emitted when the circuit breaker changes its state.
     *
     * This event provides information about the transition from one state to another,
     * allowing for monitoring of the circuit breaker's behavior.
     *
     * @property from The previous state of the circuit breaker.
     * @property to The new, current state of the circuit breaker.
     */
    data class CircuitStateChanged(val from: CircuitState, val to: CircuitState) : ResilientEvent

    /**
     * Emitted when execution is rate limited; includes the suggested wait time before the next attempt.
     *
     * @property waitTime The duration the caller should wait before re-attempting the operation.
     */
    data class RateLimited(val waitTime: Duration) : ResilientEvent


    /**
     * Emitted when a call is rejected by the bulkhead due to capacity constraints.
     * @property reason A description of why the call was rejected (e.g., "Queue is full", "Max concurrent calls reached").
     */
    data class BulkheadRejected(val reason: String) : ResilientEvent

    /**
     * Emitted when the cache returns a valid entry (hit).
     * @property key The cache key that produced the hit.
     */
    data class CacheHit(val key: String) : ResilientEvent

    /**
     * Emitted when the cache does not have a valid entry and the block is executed (miss).
     * @property key The cache key that produced the miss.
     */
    data class CacheMiss(val key: String) : ResilientEvent

    /**
     * Emitted when the operation exceeds the configured timeout and is cancelled.
     * @property timeout The configured timeout duration that was exceeded.
     */
    data class TimeoutTriggered(val timeout: Duration) : ResilientEvent

    /**
     * Emitted when the fallback is used after the operation failed.
     * @property error The error that triggered the fallback.
     */
    data class FallbackTriggered(val error: Throwable) : ResilientEvent

    /**
     * Emitted when hedging returns a result from a non-primary attempt (attempt index > 0).
     * @property attemptIndex The 0-based index of the attempt that produced the result.
     */
    data class HedgingUsed(val attemptIndex: Int) : ResilientEvent

    /**
     * Emitted after a successful operation, including the total time taken for the execution.
     *
     * @property duration The measured duration of the successful operation.
     */
    data class OperationSuccess(val duration: Duration) : ResilientEvent

    /**
     * Emitted after a failed operation, capturing the triggering error and the execution duration.
     *
     * @property error The [Throwable] that caused the operation to fail.
     * @property duration The measured execution time of the failed operation.
     */
    data class OperationFailure(val error: Throwable, val duration: Duration) : ResilientEvent
}
