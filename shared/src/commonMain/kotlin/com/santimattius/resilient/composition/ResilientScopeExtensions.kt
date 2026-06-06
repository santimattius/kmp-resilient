package com.santimattius.resilient.composition

import com.santimattius.resilient.ResilientPolicy
import kotlinx.coroutines.CoroutineScope

/**
 * Wraps this [CoroutineScope] as a [ResilientScope] that participates in the caller's
 * coroutine hierarchy.
 *
 * **Lifecycle contract**:
 * - When the outer [CoroutineScope] is cancelled, the derived [ResilientScope] and all
 *   background jobs it owns (e.g. cache-cleanup sweepers, coalescing jobs) are cancelled
 *   automatically via structured concurrency.
 * - Calling [ResilientPolicy.close] on a policy built from the returned scope cancels only
 *   the internal policy jobs — it does **not** cancel this [CoroutineScope].
 *
 * **Important**: Do not call this on a scope that has already been cancelled. The derived
 * [ResilientScope] will still be created, but any internal jobs it tries to launch will
 * fail immediately.
 *
 * @return A [ResilientScope] whose lifecycle is governed by this [CoroutineScope].
 */
fun CoroutineScope.asResilientScope(): ResilientScope = ResilientScope.fromExternalScope(this)

/**
 * Shorthand for building a [ResilientPolicy] bound to the lifecycle of this [CoroutineScope].
 *
 * Equivalent to:
 * ```kotlin
 * resilient(this.asResilientScope(), block)
 * ```
 *
 * **Lifecycle contract**:
 * - When this [CoroutineScope] is cancelled, all internal background jobs spawned by the
 *   resulting policy (cache-cleanup, coalescing) are cancelled automatically.
 * - Calling [ResilientPolicy.close] on the returned policy stops its internal jobs but does
 *   **not** cancel this [CoroutineScope].
 *
 * **Important**: Do not call this on a scope that has already been cancelled. The policy
 * will be created, but its internal coroutines may throw [kotlinx.coroutines.CancellationException]
 * when they attempt to start.
 *
 * Example:
 * ```kotlin
 * // In a ViewModel:
 * val policy = viewModelScope.resilient {
 *     retry { maxAttempts = 3 }
 *     cache { ttl = 60.seconds }
 * }
 * ```
 *
 * @param block Lambda with [ResilientBuilder] receiver to configure resilience policies.
 * @return A [ResilientPolicy] whose background jobs are tied to this scope's lifecycle.
 */
fun CoroutineScope.resilient(block: ResilientBuilder.() -> Unit): ResilientPolicy =
    resilient(this.asResilientScope(), block)
