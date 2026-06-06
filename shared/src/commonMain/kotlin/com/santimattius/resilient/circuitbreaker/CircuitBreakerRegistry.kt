package com.santimattius.resilient.circuitbreaker

/**
 * Registry of named [DefaultCircuitBreaker] instances so multiple
 * [com.santimattius.resilient.ResilientPolicy] configurations can **share** the same circuit breaker
 * (e.g. one breaker per downstream service `"payments"`, `"db"`).
 *
 * **Thread-safety:** [getOrCreate] is **not** synchronized across platforms. Typical use is to build
 * policies on a single thread at startup; do not call [getOrCreate] concurrently for the same registry
 * without external synchronization.
 *
 * The first [getOrCreate] call for a given [name] wins; subsequent calls return the **same instance**
 * and ignore the [configure] block (document your convention: one name, one config).
 *
 * **Telemetry limitation:** because a [DefaultCircuitBreaker] accepts a single `onStateChanged`
 * callback at construction time, only the *first* policy that registers a name receives state-change
 * events via its [com.santimattius.resilient.ResilientPolicy.events] `SharedFlow`. Policies that reuse
 * an existing registry entry will **not** receive [com.santimattius.resilient.telemetry.ResilientEvent.CircuitStateChanged]
 * events from the shared breaker. Use the registry [DefaultCircuitBreaker.state] `StateFlow` directly
 * for shared observability.
 */
class CircuitBreakerRegistry {

    private val instances = mutableMapOf<String, DefaultCircuitBreaker>()

    /**
     * Returns an existing [DefaultCircuitBreaker] for [name], or creates one with [configure].
     *
     * @param name Unique key for this circuit breaker (e.g. `"payments"`, `"db"`).
     * @param configure Lambda applied to [CircuitBreakerConfig] **only** when the instance is
     *   created. Ignored on subsequent calls for the same [name].
     * @param onStateChanged Optional callback forwarded to [DefaultCircuitBreaker] **only** when
     *   the instance is created. Receives `(newState, oldState)`. See the class-level telemetry
     *   limitation note above.
     */
    fun getOrCreate(
        name: String,
        configure: CircuitBreakerConfig.() -> Unit,
        onStateChanged: ((CircuitState, CircuitState) -> Unit)? = null
    ): DefaultCircuitBreaker {
        instances[name]?.let { return it }
        val created = DefaultCircuitBreaker(
            config = CircuitBreakerConfig().apply(configure),
            onStateChanged = onStateChanged ?: { _, _ -> }
        )
        instances[name] = created
        return created
    }
}
