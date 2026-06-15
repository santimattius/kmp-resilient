package com.santimattius.resilient

import com.santimattius.resilient.cache.CacheHandle
import com.santimattius.resilient.circuitbreaker.CircuitBreakerSnapshot
import com.santimattius.resilient.bulkhead.BulkheadSnapshot
import com.santimattius.resilient.telemetry.ResilientEvent
import kotlinx.coroutines.flow.SharedFlow
import kotlin.time.Duration

/**
 * Point-in-time snapshot of the rate limiter policy state.
 *
 * @property remainingCalls Number of tokens (calls) still available in the current window.
 * @property timeToRefill Duration until the bucket is refilled.
 */
data class RateLimiterSnapshot(
    val remainingCalls: Int,
    val timeToRefill: Duration
)

/**
 * Point-in-time snapshot of the retry policy configuration.
 *
 * @property maxAttempts Maximum number of attempts (including the first one).
 */
data class RetrySnapshot(
    val maxAttempts: Int
)

/**
 * Point-in-time snapshot of the cache policy state.
 *
 * @property entryCount Current number of entries in the cache.
 * @property hitRate Ratio of cache hits to total lookups (hits + misses).
 *           [Double.NaN] when no calls have been made yet.
 */
data class CacheSnapshot(
    val entryCount: Int,
    val hitRate: Double
)

/**
 * Point-in-time snapshot of policy state for health/readiness endpoints and metrics.
 *
 * Use [ResilientPolicy.getHealthSnapshot] to build this. Expose it in your health endpoint
 * (e.g. Kubernetes readiness/liveness, or a `/health` API) so load balancers and orchestrators
 * can decide whether to route traffic.
 *
 * This is a **source-compatible** addition. Trailing nullable fields with defaults ensure
 * existing consumers compile without modification.
 *
 * @property circuitBreaker Snapshot of circuit breaker state and counters; null if no circuit breaker was configured.
 * @property bulkhead Snapshot of bulkhead usage; null if no bulkhead was configured or the implementation does not support snapshot.
 * @property rateLimiter Snapshot of rate limiter token state; null if no rate limiter was configured.
 * @property retry Snapshot of retry configuration; null if no retry was configured.
 * @property cache Snapshot of cache usage and hit rate; null if no cache was configured.
 */
data class PolicyHealthSnapshot(
    val circuitBreaker: CircuitBreakerSnapshot? = null,
    val bulkhead: BulkheadSnapshot? = null,
    val rateLimiter: RateLimiterSnapshot? = null,
    val retry: RetrySnapshot? = null,
    val cache: CacheSnapshot? = null
)

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
 * 3.  **Coalescing**: Deduplicates concurrent in-flight executions by key.
 * 4.  **Timeout**: Enforces a total time limit for the operation, including all retries.
 * 5.  **Retry**: Re-executes the operation upon specific failures.
 * 6.  **Circuit Breaker**: Halts execution temporarily if failure rates exceed a threshold.
 * 7.  **Rate Limiter**: Controls the frequency of executions.
 * 8.  **Bulkhead**: Limits the number of concurrent executions.
 * 9.  **Hedging**: Executes secondary operations in parallel if the primary is slow.
 * 10. **User's Code Block**: The actual suspendable operation to be executed.
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
     * Optional handle for cache invalidation when the policy was built with cache enabled.
     *
     * Use [CacheHandle.invalidate] to remove a single entry by key, or [CacheHandle.invalidatePrefix]
     * to remove all entries whose key starts with a given prefix (e.g. `"user:"`).
     * `null` if no cache was configured.
     */
    val cacheHandle: CacheHandle?
        get() = null

    /**
     * Returns a point-in-time snapshot of policy state for health/readiness and metrics.
     *
     * Use this to build health endpoints (e.g. Kubernetes readiness probe, or `/health` API).
     * When circuit breaker is configured, check [PolicyHealthSnapshot.circuitBreaker]?.state for OPEN;
     * when bulkhead is configured, you can expose [PolicyHealthSnapshot.bulkhead] for concurrent/waiting counts.
     *
     * @return [PolicyHealthSnapshot] with circuit breaker and bulkhead snapshots when configured.
     */
    fun getHealthSnapshot(): PolicyHealthSnapshot = PolicyHealthSnapshot()

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
