package com.santimattius.resilient.composition

/**
 * An immutable, reusable, context-free definition of resilience policies.
 *
 * A template stores configuration **definitions only**. It never holds live policy instances,
 * so every [resilient] build that [include][ResilientBuilder.include]s a template creates its own
 * independent runtime state (caches, rate-limit buckets, circuit breakers, bulkheads...).
 * State is shared only through explicit named registries (`rateLimiterNamed`,
 * `circuitBreakerNamed`, `bulkheadNamed`).
 *
 * Because a template is not tied to any result or input type, the same template can be included
 * in policies for unrelated types.
 *
 * ```kotlin
 * val baseline = resilienceTemplate {
 *     timeout { timeout = 2.seconds }
 *     retry { maxAttempts = 3 }
 * }
 * val policy = resilient(scope) {
 *     include(baseline)
 *     retry { maxAttempts = 2 } // overlay: only maxAttempts changes
 * }
 * ```
 *
 * @see resilienceTemplate
 * @see ResilientBuilder.include
 */
class ResilienceTemplate internal constructor(
    private val definition: ResilientBuilder
) {
    /** Applies a fresh copy of this template's definitions on top of [target]. */
    internal fun applyTo(target: ResilientBuilder) {
        target.mergeFrom(definition)
    }
}

/**
 * Creates an immutable [ResilienceTemplate] from the given DSL block.
 *
 * The block uses the same policy DSL as [resilient]. The resulting template is a snapshot: later
 * changes to objects captured by the block do not alter the configured values.
 */
fun resilienceTemplate(block: ResilientBuilder.() -> Unit): ResilienceTemplate =
    ResilienceTemplate(ResilientBuilder().apply(block).snapshot())
