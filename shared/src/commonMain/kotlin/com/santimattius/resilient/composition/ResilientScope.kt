package com.santimattius.resilient.composition

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    private val _scope = CoroutineScope(dispatcher + SupervisorJob())

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
    ) = _scope.launch(context, start, block)

    /**
     * Cancels this scope and all its child coroutines. Call when the resilience policy is no longer needed.
     */
    override fun close() {
        _scope.cancel()
    }
}
