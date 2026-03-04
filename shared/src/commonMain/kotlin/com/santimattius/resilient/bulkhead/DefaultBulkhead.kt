package com.santimattius.resilient.bulkhead

import kotlin.concurrent.Volatile
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * A semaphore-based implementation of the [Bulkhead] pattern.
 *
 * This implementation controls the number of concurrent executions and the number of calls waiting
 * to be executed. It uses a [Semaphore] to limit the number of parallel executions to
 * `[BulkheadConfig.maxConcurrentCalls]`.
 *
 * When all permits of the semaphore are acquired, incoming calls are placed in a waiting queue.
 * The size of this queue is limited by `[BulkheadConfig.maxWaitingCalls]`. If the queue is also full,
 * any new calls will be rejected immediately with a [BulkheadFullException].
 *
 * A call waiting in the queue will attempt to acquire a permit from the semaphore. If a `[BulkheadConfig.timeout]`
 * is configured, the call will wait for the specified duration. If it fails to acquire a permit within
 * this time, it is rejected.
 *
 * @param config The [BulkheadConfig] used to configure this instance.
 * @param onRejected Optional callback invoked synchronously when a call is rejected before throwing
 *                   [BulkheadFullException]. Receives a human-readable reason string. Used for telemetry
 *                   (e.g. emitting [com.santimattius.resilient.telemetry.ResilientEvent.BulkheadRejected])
 *                   regardless of whether a Fallback policy is configured.
 */
class DefaultBulkhead(
    private val config: BulkheadConfig,
    private val onRejected: ((reason: String) -> Unit)? = null,
) : Bulkhead {

    init {
        require(config.maxConcurrentCalls >= 1) {
            "maxConcurrentCalls must be >= 1, got ${config.maxConcurrentCalls}"
        }
        require(config.maxWaitingCalls >= 0) {
            "maxWaitingCalls must be >= 0, got ${config.maxWaitingCalls}"
        }
    }

    private val semaphore = Semaphore(config.maxConcurrentCalls)
    private val mutex = Mutex()
    @Volatile
    private var queuedWaiters: Int = 0
    @Volatile
    private var activeConcurrentCalls: Int = 0

    override fun snapshot(): BulkheadSnapshot = BulkheadSnapshot(
        activeConcurrentCalls = activeConcurrentCalls,
        waitingCalls = queuedWaiters,
        maxConcurrentCalls = config.maxConcurrentCalls,
        maxWaitingCalls = config.maxWaitingCalls
    )

    override suspend fun <T> execute(block: suspend () -> T): T {
        val acquired = tryAcquirePermit()
        if (!acquired) {
            onRejected?.invoke("Bulkhead full: max concurrent and queue capacity reached")
            throw BulkheadFullException()
        }
        try {
            mutex.withLock { activeConcurrentCalls++ }
            return block()
        } finally {
            withContext(NonCancellable) {
                mutex.withLock { activeConcurrentCalls-- }
                semaphore.release()
            }
        }
    }

    private suspend fun tryAcquirePermit(): Boolean {
        if (semaphore.tryAcquire()) return true

        currentCoroutineContext().ensureActive()

        var canEnqueueWaiter: Boolean
        mutex.withLock {
            canEnqueueWaiter = queuedWaiters < config.maxWaitingCalls
            if (canEnqueueWaiter) queuedWaiters++
        }
        if (!canEnqueueWaiter) return false
        return try {
            val timeout = config.timeout
            if (timeout == null) {
                semaphore.acquire()
                true
            } else {
                withTimeout(timeout.inWholeMilliseconds) {
                    semaphore.acquire()
                }
                true
            }
        } finally {
            mutex.withLock { queuedWaiters-- }
        }
    }
}
