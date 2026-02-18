package com.santimattius.resilient.composition

/**
 * Represents the types of resilience policies that can have their composition order configured.
 * 
 * Note: Fallback is excluded because it must always be positioned outermost (first) to catch
 * all failures from other policies. It cannot be reordered.
 */
enum class OrderablePolicyType {
    CACHE,
    TIMEOUT,
    RETRY,
    CIRCUIT_BREAKER,
    RATE_LIMITER,
    BULKHEAD,
    HEDGING
}
