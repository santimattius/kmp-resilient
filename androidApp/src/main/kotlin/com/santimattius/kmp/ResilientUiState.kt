package com.santimattius.kmp

data class ResilientUiState(
    val result: String? = null,
    val error: String? = null,
    val events: List<String> = emptyList(),
    val isLoading: Boolean = false
)