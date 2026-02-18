package com.santimattius.resilient.composition

/**
 * Represents the types of resilience policies that can have their composition order configured.
 *
 * Note: Fallback is excluded because it must always be positioned outermost (first) to catch
 * all failures from other policies. It cannot be reordered.
 */
enum class OrderablePolicyType {
    /** Cache policy: returns cached result when available. */
    CACHE,
    /** Timeout policy: cancels execution after a maximum duration. */
    TIMEOUT,
    /** Retry policy: retries the operation on failure. */
    RETRY,
    /** Circuit breaker: rejects calls when the circuit is open. */
    CIRCUIT_BREAKER,
    /** Rate limiter: limits the rate of executions. */
    RATE_LIMITER,
    /** Bulkhead: limits concurrent executions. */
    BULKHEAD,
    /** Hedging: runs multiple parallel attempts and returns the first success. */
    HEDGING
}
