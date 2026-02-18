package com.santimattius.resilient.composition

/**
 * Represents the different types of resilience policies that can be composed.
 * Each policy type corresponds to a specific resilience strategy.
 * Used internally when building the composition order (includes [FALLBACK], which is not in [OrderablePolicyType]).
 */
internal enum class PolicyType {
    /** Fallback: provides alternative result on failure; always outermost. */
    FALLBACK,
    /** Cache: returns cached result when available. */
    CACHE,
    /** Timeout: cancels after max duration. */
    TIMEOUT,
    /** Retry: retries on failure. */
    RETRY,
    /** Circuit breaker: rejects when open. */
    CIRCUIT_BREAKER,
    /** Rate limiter: limits rate. */
    RATE_LIMITER,
    /** Bulkhead: limits concurrency. */
    BULKHEAD,
    /** Hedging: parallel attempts, first success wins. */
    HEDGING
}
