package com.santimattius.resilient.bulkhead

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
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
 */
class DefaultBulkhead(
    private val config: BulkheadConfig,
) : Bulkhead {

    private val semaphore = Semaphore(config.maxConcurrentCalls)
    private val mutex = Mutex()
    private var queuedWaiters: Int = 0

    override suspend fun <T> execute(block: suspend () -> T): T {
        val acquired = tryAcquirePermit()
        if (!acquired) throw BulkheadFullException()
        try {
            return block()
        } finally {
            semaphore.release()
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
