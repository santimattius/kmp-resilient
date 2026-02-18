package com.santimattius.kmp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.santimattius.resilient.composition.ResilientScope
import com.santimattius.resilient.composition.resilient
import com.santimattius.resilient.fallback.FallbackConfig
import com.santimattius.resilient.retry.ExponentialBackoff
import com.santimattius.resilient.telemetry.ResilientEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ResilientViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ResilientUiState())
    val uiState: StateFlow<ResilientUiState> = _uiState.asStateFlow()

    private val resilientScope = ResilientScope(dispatcher = Dispatchers.Default)

    private val policy = resilient(resilientScope) {
        cache {
            key = "demo-call"
            ttl = 30.seconds
        }
        timeout { timeout = 2.seconds }
        retry {
            maxAttempts = 3
            backoffStrategy = ExponentialBackoff(initialDelay = 100.milliseconds)
            shouldRetry = { it is IllegalStateException }
        }
        circuitBreaker { failureThreshold = 3; successThreshold = 2; timeout = 10.seconds }
        hedging {
            attempts = 2
            stagger = 50.milliseconds
        }
        fallback(FallbackConfig { e -> "fallback: ${e.message}" })
    }

    fun executePolicy() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                result = null,
                error = null,
                events = emptyList(),
                isLoading = true
            )
            coroutineScope {
                val collector = launch {
                    policy.events.collect { event ->
                        val label = formatEvent(event)
                        val currentEvents = _uiState.value.events
                        val updatedEvents = (currentEvents + label).takeLast(12)
                        _uiState.value = _uiState.value.copy(events = updatedEvents)
                    }
                }

                try {
                    val value = policy.execute {
                        delay(100)
                        if (System.currentTimeMillis() % 2L == 0L) {
                            throw IllegalStateException("Simulated failure")
                        }
                        "OK"
                    }
                    _uiState.value = _uiState.value.copy(
                        result = value,
                        error = null,
                        isLoading = false
                    )
                } catch (t: Throwable) {
                    _uiState.value = _uiState.value.copy(
                        error = t.message ?: t::class.simpleName ?: "Unknown error",
                        isLoading = false
                    )
                } finally {
                    collector.cancel()
                }
            }
        }
    }

    private fun formatEvent(event: ResilientEvent): String = when (event) {
        is ResilientEvent.CacheHit -> "CacheHit(key=${event.key})"
        is ResilientEvent.CacheMiss -> "CacheMiss(key=${event.key})"
        is ResilientEvent.TimeoutTriggered -> "TimeoutTriggered(${event.timeout})"
        is ResilientEvent.FallbackTriggered -> "FallbackTriggered: ${event.error.message}"
        is ResilientEvent.HedgingUsed -> "HedgingUsed(attempt ${event.attemptIndex + 1})"
        is ResilientEvent.RetryAttempt -> "RetryAttempt(#${event.attempt}: ${event.error.message})"
        is ResilientEvent.CircuitStateChanged -> "CircuitStateChanged(${event.from} → ${event.to})"
        is ResilientEvent.RateLimited -> "RateLimited(wait=${event.waitTime})"
        is ResilientEvent.BulkheadRejected -> "BulkheadRejected(${event.reason})"
        is ResilientEvent.OperationSuccess -> "OperationSuccess(${event.duration})"
        is ResilientEvent.OperationFailure -> "OperationFailure(${event.error.message}, ${event.duration})"
    }

    override fun onCleared() {
        super.onCleared()
        resilientScope.close()
    }
}
