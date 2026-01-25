package com.santimattius.resilient.composition

/**
 * Defines the order in which resilience policies are composed.
 * Policies are applied from outermost (first in list) to innermost (last in list).
 *
 * **Important**: Fallback is always positioned outermost (first) and cannot be configured.
 * This ensures it can catch all failures from other policies.
 *
 * The default order is:
 * Fallback → Cache → Timeout → Retry → Circuit Breaker → Rate Limiter → Bulkhead → Hedging
 *
 * This order ensures that:
 * - Fallback wraps outermost to handle failures after all policies (always enforced)
 * - Cache is early to avoid unnecessary work
 * - Timeout prevents long-running operations
 * - Retry attempts to recover from transient failures
 * - Circuit Breaker prevents hammering failing services
 * - Rate Limiter controls request rate
 * - Bulkhead limits concurrency
 * - Hedging is innermost to launch parallel attempts close to execution
 *
 * @param order The list of orderable policy types in the desired composition order (outermost to innermost).
 *              Must contain all orderable policy types exactly once.
 *              Fallback is automatically prepended to the order.
 * @throws IllegalArgumentException if the order doesn't contain all orderable policy types or contains duplicates.
 */
class CompositionOrder(
    order: List<OrderablePolicyType>
) {
    /**
     * The complete order with Fallback always first, followed by the configured order.
     */
    val order: List<PolicyType> = run {
        val inputOrder = order.distinct()
        
        require(inputOrder.size == OrderablePolicyType.entries.size) {
            "CompositionOrder must contain all orderable policy types exactly once. " +
                    "Expected ${OrderablePolicyType.entries.size} types, but got ${inputOrder.size}. " +
                    "Missing: ${OrderablePolicyType.entries.filter { it !in inputOrder }}"
        }
        
        // Convert OrderablePolicyType to PolicyType and prepend Fallback
        listOf(PolicyType.FALLBACK) + inputOrder.map { orderableType ->
            when (orderableType) {
                OrderablePolicyType.CACHE -> PolicyType.CACHE
                OrderablePolicyType.TIMEOUT -> PolicyType.TIMEOUT
                OrderablePolicyType.RETRY -> PolicyType.RETRY
                OrderablePolicyType.CIRCUIT_BREAKER -> PolicyType.CIRCUIT_BREAKER
                OrderablePolicyType.RATE_LIMITER -> PolicyType.RATE_LIMITER
                OrderablePolicyType.BULKHEAD -> PolicyType.BULKHEAD
                OrderablePolicyType.HEDGING -> PolicyType.HEDGING
            }
        }
    }

    companion object {
        /**
         * Default composition order: Fallback → Cache → Timeout → Retry → Circuit Breaker → Rate Limiter → Bulkhead → Hedging
         */
        val DEFAULT = CompositionOrder(
            listOf(
                OrderablePolicyType.CACHE,
                OrderablePolicyType.TIMEOUT,
                OrderablePolicyType.RETRY,
                OrderablePolicyType.CIRCUIT_BREAKER,
                OrderablePolicyType.RATE_LIMITER,
                OrderablePolicyType.BULKHEAD,
                OrderablePolicyType.HEDGING
            )
        )
    }
}
