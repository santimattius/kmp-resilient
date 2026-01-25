package com.santimattius.resilient.composition

/**
 * Represents the different types of resilience policies that can be composed.
 * Each policy type corresponds to a specific resilience strategy.
 */
internal enum class PolicyType {
    FALLBACK,
    CACHE,
    TIMEOUT,
    RETRY,
    CIRCUIT_BREAKER,
    RATE_LIMITER,
    BULKHEAD,
    HEDGING
}
