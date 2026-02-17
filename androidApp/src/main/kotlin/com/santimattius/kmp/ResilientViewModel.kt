package com.santimattius.kmp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.santimattius.resilient.annotations.ResilientExperimentalApi
import com.santimattius.resilient.composition.OrderablePolicyType
import com.santimattius.resilient.composition.ResilientScope
import com.santimattius.resilient.composition.resilient
import com.santimattius.resilient.retry.ExponentialBackoff
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

    @OptIn(ResilientExperimentalApi::class)
    private val policy = resilient(resilientScope) {
        timeout { timeout = 2.seconds }
        retry {
            maxAttempts = 3
            backoffStrategy = ExponentialBackoff(initialDelay = 100.milliseconds)
        }
        circuitBreaker { failureThreshold = 3 }

        compositionOrder(
            order = listOf(
                OrderablePolicyType.CIRCUIT_BREAKER,
                OrderablePolicyType.RETRY,
                OrderablePolicyType.TIMEOUT,
            )
        )
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
                        val currentEvents = _uiState.value.events
                        val updatedEvents = (currentEvents + event.toString()).takeLast(6)
                        _uiState.value = _uiState.value.copy(events = updatedEvents)
                    }
                }

                try {
                    val value = policy.execute {
                        // Simulate a flaky suspend call
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

    companion object {
        private const val TAG = "ResilientViewModel"
    }
}
