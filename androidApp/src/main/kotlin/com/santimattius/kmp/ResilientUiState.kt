package com.santimattius.kmp

import com.santimattius.resilient.PolicyHealthSnapshot

data class ResilientUiState(
    val result: String? = null,
    val error: String? = null,
    val events: List<String> = emptyList(),
    val isLoading: Boolean = false,
    /** Health/readiness snapshot (circuit breaker + bulkhead) for the policy. */
    val healthSnapshot: PolicyHealthSnapshot? = null
)