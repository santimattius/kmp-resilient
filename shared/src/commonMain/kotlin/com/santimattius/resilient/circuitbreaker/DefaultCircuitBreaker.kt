package com.santimattius.resilient.circuitbreaker

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.math.max
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A default, thread-safe implementation of the [CircuitBreaker] interface.
 *
 * This class protects a system from failures by stopping the execution of operations
 * when a certain failure threshold is reached. It follows a state machine pattern with
 * three states: [CircuitState.CLOSED], [CircuitState.OPEN], and [CircuitState.HALF_OPEN].
 *
 * - **[CircuitState.CLOSED]**: The default state. All calls are executed. If the number of failures
 *   exceeds the configured `failureThreshold`, the state transitions to [CircuitState.OPEN].
 * - **[CircuitState.OPEN]**: Calls are immediately rejected with a [CircuitBreakerOpenException]
 *   without attempting to execute them. After a configured `timeout`, the state transitions
 *   to [CircuitState.HALF_OPEN].
 * - **[CircuitState.HALF_OPEN]**: A limited number of calls (configured by `halfOpenMaxCalls`) are
 *   allowed to execute to test if the underlying system has recovered. If these calls succeed
 *   (up to the `successThreshold`), the circuit transitions back to [CircuitState.CLOSED].
 *   If any of these test calls fail, it transitions back to [CircuitState.OPEN].
 *
 * ### Thread Safety and Concurrency
 *
 * This implementation is designed to be highly concurrent and thread-safe.
 *
 * - **State Checks**: A fast-path check is used for the common [CircuitState.CLOSED] state,
 *   which avoids locking and allows maximum concurrency. For [CircuitState.OPEN] and
 *   [CircuitState.HALF_OPEN] states, a `Mutex` is used to ensure atomic state transitions.
 * - **State Updates**: All state modifications are protected by a `Mutex`.
 * - **User Code Execution**: The provided `block` of code is always executed *outside* the lock
 *   to prevent holding the mutex for an extended period.
 *
 * @param config The circuit breaker configuration.
 * @param timeSource Source of current time for open/half-open timeouts; defaults to [SystemTimeSource].
 * @param onStateChanged Callback invoked on state transition (newState, oldState); used for telemetry.
 */
class DefaultCircuitBreaker(
    private val config: CircuitBreakerConfig,
    private val timeSource: TimeSource = SystemTimeSource,
    private val onStateChanged: (CircuitState, CircuitState) -> Unit = { _, _ -> }
) : CircuitBreaker {

    init {
        require(config.failureThreshold >= 1) {
            "failureThreshold must be >= 1, got ${config.failureThreshold}"
        }
        require(config.successThreshold >= 1) {
            "successThreshold must be >= 1, got ${config.successThreshold}"
        }
        require(config.halfOpenMaxCalls >= 1) {
            "halfOpenMaxCalls must be >= 1, got ${config.halfOpenMaxCalls}"
        }
    }

    private val mutex = Mutex()
    private val _state = MutableStateFlow(CircuitState.CLOSED)
    override val state: StateFlow<CircuitState> get() = _state

    @Volatile
    private var failureCount: Int = 0

    @Volatile
    private var successCount: Int = 0

    @Volatile
    private var openUntilMs: Long = 0L

    @Volatile
    private var halfOpenAllowedCalls: Int = 0

    override suspend fun <T> execute(block: suspend () -> T): T {
        val currentState = _state.value

        if (currentState == CircuitState.CLOSED) {
            return executeBlock(block)
        }

        return when (currentState) {
            CircuitState.OPEN -> handleOpenState(block)
            CircuitState.HALF_OPEN -> handleHalfOpenState(block)
            CircuitState.CLOSED -> executeBlock(block) // Should not reach here due to fast path
        }
    }

    private suspend fun <T> handleOpenState(block: suspend () -> T): T {
        val result = mutex.withLock {
            val now = getTimeMillis()
            val state = _state.value

            when {
                state != CircuitState.OPEN -> {
                    return@withLock StateCheckResult.StateChanged(state)
                }

                openUntilMs in 1..now -> {
                    transitionTo(CircuitState.HALF_OPEN)
                    halfOpenAllowedCalls = max(1, config.halfOpenMaxCalls)
                    halfOpenAllowedCalls--
                    StateCheckResult.AllowedWithCounterDecrement
                }

                else -> {
                    val retryAfter = if (openUntilMs > 0) {
                        (openUntilMs - now).milliseconds
                    } else {
                        config.timeout
                    }
                    StateCheckResult.Rejected(retryAfter)
                }
            }
        }

        return when (result) {
            is StateCheckResult.AllowedWithCounterDecrement -> {
                try {
                    executeBlock(block)
                } catch (e: CancellationException) {
                    // If cancelled after transitioning to HALF_OPEN, restore the allowed call counter
                    // since the call didn't complete
                    withContext(NonCancellable) {
                        mutex.withLock {
                            if (_state.value == CircuitState.HALF_OPEN) {
                                halfOpenAllowedCalls++
                            }
                        }
                    }
                    throw e
                }
            }

            is StateCheckResult.Allowed -> {
                executeBlock(block)
            }

            is StateCheckResult.Rejected -> throw CircuitBreakerOpenException(
                currentState = CircuitState.OPEN,
                retryAfter = result.retryAfter
            )

            is StateCheckResult.StateChanged -> {
                when (result.newState) {
                    CircuitState.CLOSED -> executeBlock(block)
                    CircuitState.HALF_OPEN -> handleHalfOpenState(block)
                    CircuitState.OPEN -> handleOpenState(block) // Retry
                }
            }
        }
    }

    private suspend fun <T> handleHalfOpenState(block: suspend () -> T): T {
        val result = mutex.withLock {
            val state = _state.value
            when {
                state != CircuitState.HALF_OPEN -> {
                    return@withLock StateCheckResult.StateChanged(state)
                }

                halfOpenAllowedCalls > 0 -> {
                    halfOpenAllowedCalls--
                    StateCheckResult.AllowedWithCounterDecrement
                }

                else -> {
                    StateCheckResult.Rejected(null)
                }
            }
        }

        return when (result) {
            is StateCheckResult.AllowedWithCounterDecrement -> {
                try {
                    executeBlock(block)
                } catch (e: CancellationException) {
                    withContext(NonCancellable) {
                        mutex.withLock {
                            if (_state.value == CircuitState.HALF_OPEN) {
                                halfOpenAllowedCalls++
                            }
                        }
                    }
                    throw e
                }
            }

            is StateCheckResult.Allowed -> {
                executeBlock(block)
            }

            is StateCheckResult.Rejected -> throw CircuitBreakerOpenException(
                currentState = CircuitState.HALF_OPEN,
                retryAfter = result.retryAfter
            )

            is StateCheckResult.StateChanged -> {
                when (result.newState) {
                    CircuitState.CLOSED -> executeBlock(block)
                    CircuitState.OPEN -> handleOpenState(block)
                    CircuitState.HALF_OPEN -> handleHalfOpenState(block) // Retry
                }
            }
        }
    }

    private suspend fun <T> executeBlock(block: suspend () -> T): T {
        return try {
            val result = block()
            // Use NonCancellable to ensure state is updated even if coroutine is cancelled
            // after block completes but before onSuccess is called
            withContext(NonCancellable) {
                onSuccess()
            }
            result
        } catch (e: CancellationException) {
            // Cancellation is not a failure - rethrow immediately without updating state
            // This ensures cancelled operations don't affect circuit breaker state
            throw e
        } catch (t: Throwable) {
            // Use NonCancellable to ensure state is updated even if coroutine is cancelled
            // after onFailure is called
            withContext(NonCancellable) {
                onFailure(t)
            }
            throw t
        }
    }

    private suspend fun onSuccess() {
        mutex.withLock {
            when (_state.value) {
                CircuitState.CLOSED -> {
                    failureCount = 0
                }

                CircuitState.OPEN -> {
                    // Ignore - should not happen, but safe to ignore
                }

                CircuitState.HALF_OPEN -> {
                    successCount++
                    if (successCount >= config.successThreshold) {
                        transitionTo(CircuitState.CLOSED)
                        successCount = 0
                        failureCount = 0
                    }
                }
            }
        }
    }

    private suspend fun onFailure(t: Throwable) {
        if (!config.shouldRecordFailure(t)) return

        mutex.withLock {
            when (_state.value) {
                CircuitState.CLOSED -> {
                    failureCount++
                    if (failureCount >= config.failureThreshold) {
                        transitionOpenFor(config.timeout)
                    }
                }

                CircuitState.OPEN -> {
                    // Remain open - no action needed
                }

                CircuitState.HALF_OPEN -> {
                    // Any failure in HALF_OPEN immediately opens the circuit
                    transitionOpenFor(config.timeout)
                }
            }
        }
    }

    private fun transitionOpenFor(duration: Duration) {
        val now = getTimeMillis()
        val durationMs = duration.inWholeMilliseconds
        // Ensure we add at least 1ms to avoid immediate timeout
        openUntilMs = now + max(1, durationMs)
        successCount = 0
        transitionTo(CircuitState.OPEN)
    }

    private fun getTimeMillis(): Long {
        return timeSource.currentTimeMillis()
    }

    private fun transitionTo(new: CircuitState) {
        val old = _state.value
        if (old != new) {
            _state.value = new
            config.onStateChange(new)
            onStateChanged(new, old)
        }
    }

    /**
     * A sealed class representing the internal result of a state check before executing a call.
     * This is used within the synchronized blocks to determine the next action without holding the lock
     * while executing the user's code block. It helps manage state transitions and execution flow cleanly.
     */
    private sealed class StateCheckResult {
        object Allowed : StateCheckResult()
        object AllowedWithCounterDecrement : StateCheckResult()
        data class Rejected(val retryAfter: Duration?) : StateCheckResult()
        data class StateChanged(val newState: CircuitState) : StateCheckResult()
    }
}