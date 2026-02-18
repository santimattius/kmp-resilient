package com.santimattius.resilient.composition

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Manages a [CoroutineScope] for background tasks within resilience policies,
 * like cache cleanup or periodic retries.
 *
 * This class provides a structured way to launch coroutines that should not be tied
 * to a specific UI lifecycle but rather to the lifecycle of the resilience strategy itself.
 * The internal scope uses a [SupervisorJob], ensuring that the failure of one child
 * coroutine does not affect others.
 *
 * As this class implements [AutoCloseable], it can be used with `use` blocks for automatic
 * resource management. It is crucial to call [close] when the scope is no longer needed
 * to cancel all running tasks and release resources, preventing memory leaks.
 *
 * @param dispatcher The [CoroutineDispatcher] to be used for launching coroutines. Defaults to [Dispatchers.Default].
 */
class ResilientScope(
    dispatcher: CoroutineDispatcher = Dispatchers.Default
) : AutoCloseable {
    private val _scope = CoroutineScope(dispatcher + SupervisorJob())

    fun launch(
        context: CoroutineContext = EmptyCoroutineContext,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend CoroutineScope.() -> Unit
    ) = _scope.launch(context, start, block)

    override fun close() {
        _scope.cancel()
    }
}
