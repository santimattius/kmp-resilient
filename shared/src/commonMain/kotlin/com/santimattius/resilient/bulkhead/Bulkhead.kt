package com.santimattius.resilient.bulkhead

/**
 * A Bulkhead can be used to limit the number of concurrent executions.
 *
 * It maintains a fixed-size pool of permits. A caller must acquire a permit
 * to execute an operation. If a permit is available, it is acquired, and the
 * operation is executed. If no permits are available, the caller may be queued
 * up to a configurable limit (`maxWaitingCalls`). Once a permit is released, a
 * queued caller is dequeued and allowed to execute. If the queue is full,
 * further calls will be rejected immediately with a [BulkheadFullException].
 *
 * This pattern is useful for protecting resources from being overloaded by too many
 * concurrent requests.
 *
 * @see BulkheadConfig for configuration options.
 */
interface Bulkhead {
    /**
     * Acquires a permit (or queues), executes [block], then releases the permit.
     * @param T The return type of the block.
     * @param block The suspendable operation to run under the bulkhead limit.
     * @return The result of [block].
     * @throws BulkheadFullException When no permit is available and the wait queue is full (or wait times out if configured).
     */
    suspend fun <T> execute(block: suspend () -> T): T
}

/**
 * A configuration builder for a [Bulkhead].
 *
 * @property maxConcurrentCalls The maximum number of parallel executions allowed. Default is 10.
 * @property maxWaitingCalls The maximum number of callers that can be queued when the bulkhead is full. Default is 100.
 * @property timeout An optional timeout for waiting to acquire a permit. If null, callers will wait indefinitely. Default is null.
 */
class BulkheadConfig {
    var maxConcurrentCalls: Int = 10
    var maxWaitingCalls: Int = 100
    var timeout: kotlin.time.Duration? = null
}

/**
 * Exception thrown when the bulkhead cannot accept a new call.
 * This occurs when both the concurrent call limit and the waiting queue are full,
 * or when waiting for a permit exceeds the configured [BulkheadConfig.timeout].
 *
 * @see Bulkhead
 * @see BulkheadConfig
 */
class BulkheadFullException : Exception()
