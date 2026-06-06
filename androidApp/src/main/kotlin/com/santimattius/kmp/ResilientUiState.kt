package com.santimattius.kmp

import com.santimattius.resilient.PolicyHealthSnapshot
import com.santimattius.resilient.circuitbreaker.CircuitState

enum class DemoMode(val label: String, val hint: String) {
    FLAKY(
        label = "Flaky",
        hint = "Random exception vs OK — DecorrelatedJitterBackoff + retry",
    ),
    DEGRADED(
        label = "Degraded",
        hint = "Returns DEGRADED — shouldRecordResult trips shared circuit breaker",
    ),
    WARMUP(
        label = "Warmup",
        hint = "Returns WARMUP then OK — shouldRetryResult retries on value",
    ),
    SHARED_BREAKER(
        label = "Shared CB",
        hint = "Trips breaker via primary policy; secondary policy is rejected",
    ),
}

data class ResilientUiState(
    val result: String? = null,
    val error: String? = null,
    val events: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val demoMode: DemoMode = DemoMode.FLAKY,
    val healthSnapshot: PolicyHealthSnapshot? = null,
    /** Shared circuit breaker state from [CircuitBreakerRegistry] (direct StateFlow observability). */
    val sharedCircuitState: CircuitState? = null,
)
