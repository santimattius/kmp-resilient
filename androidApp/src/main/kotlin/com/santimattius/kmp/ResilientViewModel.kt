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
            cleanupInterval = 60.seconds
            cleanupBatch = 50
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
                        val label = when (event) {
                            is ResilientEvent.CacheHit -> "Cache hit: ${event.key}"
                            is ResilientEvent.CacheMiss -> "Cache miss: ${event.key}"
                            is ResilientEvent.RetryAttempt -> "Retry #${event.attempt}: ${event.error.message}"
                            is ResilientEvent.CircuitStateChanged -> "Circuit ${event.from} → ${event.to}"
                            is ResilientEvent.OperationSuccess -> "Success in ${event.duration}"
                            is ResilientEvent.OperationFailure -> "Failed: ${event.error.message}"
                            is ResilientEvent.TimeoutTriggered -> "Timeout: ${event.timeout}"
                            is ResilientEvent.FallbackTriggered -> "Fallback: ${event.error.message}"
                            is ResilientEvent.HedgingUsed -> "Hedging attempt ${event.attemptIndex + 1}"
                            is ResilientEvent.RateLimited -> "Rate limited: wait ${event.waitTime}"
                            is ResilientEvent.BulkheadRejected -> "Bulkhead: ${event.reason}"
                        }
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

    override fun onCleared() {
        super.onCleared()
        resilientScope.close()
    }
}
