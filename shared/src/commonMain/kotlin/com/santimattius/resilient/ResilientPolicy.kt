package com.santimattius.resilient

import com.santimattius.resilient.telemetry.ResilientEvent
import kotlinx.coroutines.flow.SharedFlow

/**
 * A comprehensive resilience policy that orchestrates multiple strategies to safeguard suspendable operations.
 *
 * This policy composes strategies like timeout, retry, circuit breaker, rate limiter, bulkhead, and others,
 * applying them in a predefined order to enhance the robustness and reliability of the executed code.
 * It acts as a single entry point for executing an operation with a full suite of resilience measures.
 *
 * The execution pipeline follows this sequence (from outermost to innermost):
 * 1.  **Fallback**: Provides an alternative result if the entire pipeline fails.
 * 2.  **Cache**: Returns a cached result if available, bypassing the rest of the pipeline.
 * 3.  **Timeout**: Enforces a total time limit for the operation, including all retries.
 * 4.  **Retry**: Re-executes the operation upon specific failures.
 * 5.  **Circuit Breaker**: Halts execution temporarily if failure rates exceed a threshold.
 * 6.  **Rate Limiter**: Controls the frequency of executions.
 * 7.  **Bulkhead**: Limits the number of concurrent executions.
 * 8.  **Hedging**: Executes secondary operations in parallel if the primary is slow.
 * 9.  **User's Code Block**: The actual suspendable operation to be executed.
 *
 * Telemetry events are emitted throughout the execution, providing insights into the behavior of each strategy.
 *
 * **Lifecycle:** This policy implements [AutoCloseable]. Call [close] when the policy is no longer needed to release
 * internal resources (e.g. cache cleanup job when using [com.santimattius.resilient.cache.CacheConfig.cleanupInterval]).
 * If you use a [com.santimattius.resilient.composition.ResilientScope], closing that scope cancels its jobs
 * (including cache cleanup); calling [close] on the policy additionally stops the cache sweeper without closing the scope.
 */
interface ResilientPolicy : AutoCloseable {
    /**
     * A stream of telemetry events emitted by the policy's resilience strategies during execution.
     *
     * This `SharedFlow` provides real-time insights into the policy's behavior,
     * such as retry attempts, circuit breaker state changes, cache hits/misses,
     * and execution successes or failures. Subscribe to this flow to monitor
     * the health and performance of the decorated operations.
     */
    val events: SharedFlow<ResilientEvent>

    /**
     * Executes the provided suspendable [block] of code under the control of the composed resilience policies.
     *
     * The policies are applied in a specific order, wrapping the execution of the [block].
     * The execution order from the outermost to the innermost policy is as follows:
     *
     * **Execution Order (Outer → Inner):**
     * 1.  **Timeout:** Enforces an overall time limit for the entire operation, including all retries.
     * 2.  **Retry:** Retries the execution if it fails, subject to the retry policy's conditions.
     * 3.  **Circuit Breaker:** Prevents execution if the circuit is open, failing fast.
     * 4.  **Rate Limiter:** Delays execution until a permit is available from the rate limiter.
     * 5.  **Bulkhead:** Restricts concurrent executions, queueing or rejecting if the limit is reached.
     * 6.  **Hedging:** May trigger secondary, hedged executions if the primary one is slow.
     * 7.  **Block:** The actual suspendable operation to be executed.
     *
     * @param T The return type of the [block].
     * @param block The suspendable lambda function to execute.
     * @return The result of the [block] if it completes successfully.
     * @throws Throwable if the execution fails after all policies have been applied (e.g., all retries failed, or the timeout was exceeded).
     */
    suspend fun <T> execute(block: suspend () -> T): T
}
