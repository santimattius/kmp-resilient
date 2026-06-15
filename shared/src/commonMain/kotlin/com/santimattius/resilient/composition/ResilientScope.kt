package com.santimattius.resilient.composition

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Job
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Manages a [CoroutineScope] for **internal** background tasks within resilience policies,
 * such as cache cleanup (when [com.santimattius.resilient.cache.CacheConfig.cleanupInterval] is set).
 *
 * The user's execution block passed to [ResilientPolicy.execute] runs in the **caller's** coroutine context,
 * not in this scope. This scope is only used for policy-internal work (e.g. periodic sweeper jobs).
 *
 * The internal scope uses a [SupervisorJob], ensuring that the failure of one child coroutine does not affect others.
 * [Dispatchers.Default] is used by default for CPU/background work.
 *
 * As this class implements [AutoCloseable], it can be used with `use` blocks for automatic resource management.
 * Call [close] when the scope is no longer needed to cancel all running tasks and release resources.
 *
 * @param dispatcher The [CoroutineDispatcher] for launching internal policy coroutines. Defaults to [Dispatchers.Default].
 */
class ResilientScope(
    dispatcher: CoroutineDispatcher = Dispatchers.Default
) : AutoCloseable {

    // The scope used for all coroutine operations.
    // The field is read-only after construction. `fromExternalScope` uses a secondary constructor
    // (via the `_scope` parameter overload) to set the backing scope directly.
    private val _scope: CoroutineScope

    // Public constructor: creates an independently-owned scope.
    init {
        _scope = CoroutineScope(dispatcher + SupervisorJob())
    }

    // Internal helper constructor that accepts a pre-built scope.
    // A dummy Unit parameter resolves Kotlin's overload-resolution ambiguity between
    // `(CoroutineScope)` and the `(CoroutineDispatcher = Dispatchers.Default)` constructor;
    // both would otherwise be called with a `CoroutineScope` argument.
    @Suppress("UNUSED_PARAMETER")
    internal constructor(preBuiltScope: CoroutineScope, ignored: Unit = Unit) : this(Dispatchers.Default) {
        // Re-point _scope to the pre-built scope. We do this via a mutable backing field.
        // The Dispatchers.Default scope created by the delegated call is discarded.
        _scopeField = preBuiltScope
    }

    // Mutable backing field used only by the internal constructor to override _scope.
    // Null means the value from init{} is active.
    private var _scopeField: CoroutineScope? = null
    private val activeScope: CoroutineScope get() = _scopeField ?: _scope

    /**
     * Launches a coroutine in this scope for internal policy work (e.g. cache cleanup).
     * @param context Additional [CoroutineContext] to merge with the scope's context.
     * @param start [CoroutineStart] strategy (default is [CoroutineStart.DEFAULT]).
     * @param block The suspend block to run in the launched coroutine.
     * @return The [Job] of the launched coroutine.
     */
    fun launch(
        context: CoroutineContext = EmptyCoroutineContext,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend CoroutineScope.() -> Unit
    ) = activeScope.launch(context, start, block)

    /**
     * Launches a coroutine in this scope for internal policy work and returns its [Deferred].
     *
     * This is primarily useful for features that need to deduplicate work across multiple
     * callers (e.g. request coalescing) without coupling the in-flight operation lifetime to
     * the lifetime of the first caller.
     */
    fun <T> async(
        context: CoroutineContext = EmptyCoroutineContext,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend CoroutineScope.() -> T
    ): Deferred<T> = activeScope.async(context, start, block)

    /**
     * Cancels this scope and all its child coroutines. Call when the resilience policy is no longer needed.
     *
     * When this [ResilientScope] was created via [CoroutineScope.asResilientScope], only the internal
     * derived child scope is cancelled — the outer [CoroutineScope] is NOT affected.
     */
    override fun close() {
        activeScope.cancel()
    }

    internal companion object {
        /**
         * Creates a [ResilientScope] that derives a **child** [CoroutineScope] from [externalScope].
         *
         * The derived scope is a structured-concurrency child of [externalScope]: when [externalScope]
         * is cancelled, the derived scope (and all internal background jobs it owns) are cancelled
         * automatically. A [SupervisorJob] linked to the outer scope's [Job] ensures internal policy
         * job failures do NOT propagate back to [externalScope].
         *
         * Calling [close] on the returned [ResilientScope] cancels only the derived child scope;
         * [externalScope] is left completely untouched.
         *
         * @param externalScope The caller-owned [CoroutineScope] whose lifecycle governs this scope.
         * @return A [ResilientScope] bound to [externalScope]'s lifecycle.
         */
        fun fromExternalScope(externalScope: CoroutineScope): ResilientScope {
            val childScope = CoroutineScope(
                externalScope.coroutineContext + SupervisorJob(externalScope.coroutineContext[Job])
            )
            return ResilientScope(childScope, Unit)
        }
    }
}
