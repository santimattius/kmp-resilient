package com.santimattius.resilient.circuitbreaker

/**
 * A single circuit breaker state change, from [from] to [to].
 *
 * Emitted by [DefaultCircuitBreaker.transitions].
 */
data class CircuitStateTransition(val from: CircuitState, val to: CircuitState)
