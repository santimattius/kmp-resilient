package com.santimattius.resilient.composition

/**
 * Represents the types of resilience policies that can have their composition order configured.
 * Pass a list of these to [ResilientBuilder.compositionOrder] (or the typed builder's equivalent);
 * the last explicit order wins.
 *
 * Note: Fallback is excluded because it must always be positioned outermost (first) to catch
 * all failures from other policies. It cannot be reordered.
 */
enum class OrderablePolicyType {
    /** Cache policy: returns cached result when available. */
    CACHE,
    /** Coalescing policy: deduplicates concurrent in-flight requests by key. */
    COALESCE,
    /** Timeout policy: cancels execution after a maximum duration. */
    TIMEOUT,
    /** Retry policy: retries the operation on failure. */
    RETRY,
    /** Circuit breaker: rejects calls when the circuit is open. */
    CIRCUIT_BREAKER,
    /** Rate limiter: limits the rate of executions. */
    RATE_LIMITER,
    /**
     * Keyed rate limiter: per-key quotas derived from the typed input of
     * [TypedResilientPolicy]; ignored by policies without a keyed limiter. By default it runs inside
     * (after) [RATE_LIMITER], so a globally rejected call never creates a key bucket.
     */
    KEYED_RATE_LIMITER,
    /** Bulkhead: limits concurrent executions. */
    BULKHEAD,
    /** Hedging: runs multiple parallel attempts and returns the first success. */
    HEDGING,
    /** Chaos: injects faults, latency, or overridden results; innermost wrapper. */
    CHAOS
}
