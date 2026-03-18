package com.santimattius.resilient.coalescing

import com.santimattius.resilient.composition.ResilientScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A policy that *coalesces* concurrent requests by key.
 *
 * Unlike [com.santimattius.resilient.cache.CachePolicy], this policy does not cache completed
 * results. It only deduplicates *in-flight* executions: concurrent callers that resolve to the
 * same key will share a single in-flight operation and all await the same [Deferred] result.
 *
 * Implementation detail:
 * the shared in-flight operation is launched in the provided [ResilientScope] so cancelling the
 * first caller does not cancel the shared operation needed by other concurrent callers.
 */
interface CoalescingPolicy {
    suspend fun <T> execute(block: suspend () -> T): T
}

/**
 * Configuration for [CoalescingPolicy].
 *
 * @property key Key used when [keyProvider] is null.
 * @property keyProvider Optional suspend lambda that resolves a key at execution time
 * (e.g. based on request/user context captured in the coroutine).
 */
class CoalesceConfig {
    var key: String = "default"
    var keyProvider: (suspend () -> String)? = null
}

/**
 * Default implementation of [CoalescingPolicy].
 *
 * It deduplicates concurrent calls per resolved key using an in-memory map of in-flight
 * [Deferred] instances. When the shared deferred completes (success or failure), it is
 * removed from the map so subsequent calls can re-execute the block.
 *
 * **Why [ResilientScope] and not `coroutineScope { async { ... } }`?**
 * The shared in-flight work must run in a scope that is **not** the caller's scope. If we used
 * `async` inside the caller's [coroutineScope], that job would be a child of the first caller;
 * when that caller is cancelled (e.g. user navigates away, timeout), the shared job would be
 * cancelled too, and all other callers waiting on the same key would receive
 * [kotlinx.coroutines.CancellationException]. Coalescing requires that cancelling one caller
 * does not cancel the shared execution for the others. Hence the shared execution is launched
 * in [ResilientScope], which is independent of any single caller.
 *
 * **Structured concurrency:** The in-flight operation is intentionally not a child of the caller's
 * scope (it lives in [ResilientScope]). This is the accepted trade-off for request coalescing:
 * the merged work can outlive one requestor so that others still get the result. We do not use
 * [kotlinx.coroutines.GlobalScope]; [ResilientScope] is provided by the user and typically
 * tied to application or ViewModel lifecycle, so there is no unbounded lifetime.
 *
 * @param config Key (fixed or via [CoalesceConfig.keyProvider]) for deduplication.
 * @param resilientScope Scope used to launch the shared in-flight execution so it is not
 *        cancelled when a single caller is cancelled.
 */
class DefaultCoalescingPolicy(
    private val config: CoalesceConfig,
    private val resilientScope: ResilientScope
) : CoalescingPolicy {
    private data class Inflight(val token: Any, val deferred: Deferred<Any?>)

    private val inflight = mutableMapOf<String, Inflight>()
    private val mutex = Mutex()

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T> execute(block: suspend () -> T): T = coroutineScope {
        val key = config.keyProvider?.invoke() ?: config.key

        val deferred: Deferred<Any?> = mutex.withLock {
            inflight[key]?.let { return@withLock it.deferred }

            val token = Any()
            val created = resilientScope.async<Any?> {
                try {
                    block() as Any?
                } finally {
                    mutex.withLock {
                        val current = inflight[key]
                        if (current?.token === token) {
                            inflight.remove(key)
                        }
                    }
                }
            }

            inflight[key] = Inflight(token = token, deferred = created)
            created
        }

        deferred.await() as T
    }
}

