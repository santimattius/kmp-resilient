package com.santimattius.resilient.circuitbreaker

import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A circuit breaker that protects a downstream service from overload and prevents
 * repeated calls to a failing service.
 *
 * It operates in three states:
 * - **[CLOSED][CircuitState.CLOSED]**: All calls are allowed to pass through. If a call fails, the failure count is incremented.
 *   If the failure count reaches the `failureThreshold`, the state changes to [OPEN][CircuitState.OPEN].
 * - **[OPEN][CircuitState.OPEN]**: All calls are immediately rejected with a [CircuitBreakerOpenException] without
 *   attempting to execute them. After a configured `timeout`, the state changes to [HALF_OPEN][CircuitState.HALF_OPEN].
 * - **[HALF_OPEN][CircuitState.HALF_OPEN]**: A limited number of calls (`halfOpenMaxCalls`) are allowed to pass through to test
 *   if the downstream service has recovered. If a call succeeds, the success count is incremented. If the
 *   success count reaches the `successThreshold`, the state changes back to [CLOSED][CircuitState.CLOSED]. If a call fails,
 *   the state immediately reverts to [OPEN][CircuitState.OPEN].
 *
 * The state of the circuit can be observed via the [state] property.
 *
 * @see CircuitBreakerConfig for configuration options.
 * @see CircuitBreakerOpenException for the exception thrown when the circuit is open.
 */
interface CircuitBreaker {
    suspend fun <T> execute(block: suspend () -> T): T
    val state: StateFlow<CircuitState>
}

/**
 * Represents the three states of the circuit breaker.
 *
 * - [CLOSED]: Normal operation. Requests are allowed and executed.
 * - [OPEN]: The breaker has tripped. Requests are rejected immediately without execution.
 * - [HALF_OPEN]: A trial period after being open. A limited number of requests are allowed to test if the underlying issue is resolved.
 */
enum class CircuitState {
    CLOSED, OPEN, HALF_OPEN
}

/**
 * Configuration for a [CircuitBreaker].
 *
 * This class provides a flexible way to configure the behavior of a [CircuitBreaker] instance
 * using a builder-style pattern.
 *
 * Example:
 * ```kotlin
 * val config = CircuitBreakerConfig().apply {
 *     failureThreshold = 10
 *     timeout = 30.seconds
 *     onStateChange = { newState -> println("Circuit state changed to $newState") }
 * }
 * val circuitBreaker = CircuitBreaker.create(config)
 * ```
 *
 * @property failureThreshold The number of consecutive failures required to open the circuit.
 * The circuit transitions to the [OPEN][CircuitState.OPEN] state after this many failures.
 * Defaults to `5`.
 *
 * @property successThreshold The number of consecutive successes required to close the circuit
 * when it is in the [HALF_OPEN][CircuitState.HALF_OPEN] state.
 * If a call succeeds, the circuit transitions back to [CLOSED][CircuitState.CLOSED].
 * Defaults to `2`.
 *
 * @property timeout The duration the circuit will remain in the [OPEN][CircuitState.OPEN] state
 * before transitioning to [HALF_OPEN][CircuitState.HALF_OPEN].
 * During this time, all calls will fail immediately with a [CircuitBreakerOpenException].
 * Defaults to `60.seconds`.
 *
 * @property halfOpenMaxCalls The maximum number of concurrent calls allowed when the circuit is in
 * the [HALF_OPEN][CircuitState.HALF_OPEN] state. This is to test if the downstream service has recovered.
 * Additional calls beyond this limit will be rejected.
 * Defaults to `3`.
 *
 */
class CircuitBreakerConfig {
    var failureThreshold: Int = 5
    var successThreshold: Int = 2
    var timeout: Duration = 60.seconds
    var halfOpenMaxCalls: Int = 3
    var shouldRecordFailure: (Throwable) -> Boolean = { true }
    var onStateChange: (CircuitState) -> Unit = { }
}

/**
 * Exception thrown when the [CircuitBreaker] rejects a call without executing it.
 *
 * This occurs when the circuit is in the [CircuitState.OPEN] state, or when the
 * concurrent call limit has been reached in the [CircuitState.HALF_OPEN] state.
 *
 * @property currentState The state of the circuit when the call was rejected.
 * @property retryAfter A suggested duration to wait before attempting another call.
 *                      This is typically provided when the state is [CircuitState.OPEN].
 */
class CircuitBreakerOpenException(
    val currentState: CircuitState,
    val retryAfter: Duration?
) : Exception()
