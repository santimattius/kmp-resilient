package com.santimattius.resilient.circuitbreaker

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.max
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime

/**
 * A default, thread-safe implementation of the [CircuitBreaker] interface.
 *
 * This class protects a system from failures by stopping the execution of operations
 * when a certain failure threshold is reached. It follows the state machine pattern with
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
 * The behavior of the circuit breaker is determined by the provided [CircuitBreakerConfig].
 * State changes can be observed via the [state] flow or the `onStateChanged` callback.
 *
 * @param config The configuration for the circuit breaker's behavior.
 * @param onStateChanged An optional callback that is invoked whenever the circuit breaker's state changes.
 */
class DefaultCircuitBreaker(
    private val config: CircuitBreakerConfig,
    private val onStateChanged: (CircuitState) -> Unit = {}
) : CircuitBreaker {

    private val mutex = Mutex()
    private val _state = MutableStateFlow(CircuitState.CLOSED)
    override val state: StateFlow<CircuitState> get() = _state

    private var failureCount: Int = 0
    private var successCount: Int = 0
    private var openUntilMs: Long = 0L
    private var halfOpenAllowedCalls: Int = 0

    override suspend fun <T> execute(block: suspend () -> T): T {
        val now = getTimeMillis()
        return mutex.withLock {
            when (_state.value) {
                CircuitState.OPEN -> {
                    if (now >= openUntilMs) {
                        transitionTo(CircuitState.HALF_OPEN)
                        halfOpenAllowedCalls = max(1, config.halfOpenMaxCalls)
                    } else {
                        throw CircuitBreakerOpenException(
                            currentState = _state.value,
                            retryAfter = (openUntilMs - now).milliseconds
                        )
                    }
                }

                CircuitState.HALF_OPEN -> {
                    if (halfOpenAllowedCalls <= 0) {
                        throw CircuitBreakerOpenException(
                            currentState = _state.value,
                            retryAfter = max(0, openUntilMs - now).milliseconds
                        )
                    }
                    halfOpenAllowedCalls--
                }

                CircuitState.CLOSED -> {}
            }
        }.let {
            try {
                val result = block()
                onSuccess()
                result
            } catch (t: Throwable) {
                onFailure(t)
                throw t
            }
        }
    }

    private suspend fun onSuccess() {
        mutex.withLock {
            when (_state.value) {
                CircuitState.CLOSED -> failureCount = 0
                CircuitState.OPEN -> { /* ignored */}
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

                CircuitState.OPEN -> { /* remain open */}

                CircuitState.HALF_OPEN -> {
                    transitionOpenFor(config.timeout)
                }
            }
        }
    }

    private fun transitionOpenFor(duration: Duration) {
        openUntilMs = getTimeMillis() + duration.inWholeMilliseconds
        successCount = 0
        transitionTo(CircuitState.OPEN)
    }

    @OptIn(ExperimentalTime::class)
    private fun getTimeMillis(): Long {
        return Clock.System.now().toEpochMilliseconds()
    }

    private fun transitionTo(new: CircuitState) {
        if (_state.value != new) {
            _state.value = new
            config.onStateChange(new)
            onStateChanged(new)
        }
    }
}
