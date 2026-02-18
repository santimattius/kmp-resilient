package com.santimattius.resilient.hedging

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A policy for executing an operation with hedging.
 *
 * Hedging involves making multiple concurrent requests to the same endpoint or for the same resource.
 * The first successful response is returned, and the remaining requests are canceled. This strategy
 * can reduce latency by mitigating the impact of slow or outlier responses.
 *
 * It's particularly useful for operations where tail latency is a concern.
 */
interface HedgingPolicy {
    /**
     * Executes [block] with multiple parallel attempts; returns the first successful result or throws if all fail.
     * @param T The return type of the block.
     * @param block The suspendable operation to execute (possibly multiple times in parallel).
     * @return The result of the first successful execution.
     * @throws Throwable The first failure if all attempts fail.
     */
    suspend fun <T> execute(block: suspend () -> T): T
}

/**
 * Configuration for [HedgingPolicy].
 *
 * @property attempts Number of parallel attempts to launch. Must be >= 1. Defaults to 2.
 * @property stagger Delay between launching each subsequent attempt (cumulative: attempt i starts after i * stagger). Defaults to 0.
 */
class HedgingConfig {
    var attempts: Int = 2
    var stagger: Duration = 0.milliseconds
}

/**
 * A default implementation of [HedgingPolicy] that executes a given block of code multiple times in parallel
 * and returns the result of the first one to complete successfully.
 *
 * This policy launches a configurable number of parallel attempts ([HedgingConfig.attempts]).
 * A delay ([HedgingConfig.stagger]) can be introduced between the start of each subsequent attempt.
 *
 * If the first attempt to complete is successful, its result is returned immediately, and all other
 * running attempts are canceled to save resources.
 *
 * If the first attempt to complete fails with an exception, the policy waits for other attempts to
 * complete. If any subsequent attempt succeeds, its result is returned. If all attempts fail,
 * the exception from the first failing attempt is re-thrown.
 *
 * @param config The [HedgingConfig] to configure the behavior of the policy, such as the number of attempts and the delay between them.
 */
class DefaultHedgingPolicy(
    private val config: HedgingConfig
) : HedgingPolicy {

    /**
     * Executes a given suspending block of code according to the hedging policy.
     *
     * This function launches multiple parallel executions (`attempts`) of the `block`.
     * A configurable `stagger` delay can be applied between the start of each subsequent execution.
     *
     * The result of the first execution to complete successfully is returned immediately. All other
     * concurrent executions are then canceled to conserve resources.
     *
     * If an execution fails, the policy will wait for other executions to complete. If all executions fail,
     * the exception from the first failing execution is re-thrown.
     *
     * @param T The return type of the suspending block.
     * @param block The suspending block of code to execute.
     * @return The result of the first successful execution of the block.
     * @throws Throwable if all executions fail, the exception from the first failure is thrown.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun <T> execute(block: suspend () -> T): T = coroutineScope {
        require(config.attempts >= 1) { "Hedging attempts must be >= 1" }
        if (config.attempts == 1) return@coroutineScope block()

        // Launch all attempts with proper stagger delay
        // Stagger should be cumulative: attempt 0 starts immediately,
        // attempt 1 starts after 1*stagger, attempt 2 after 2*stagger, etc.
        val deferreds = (0 until config.attempts).map { idx ->
            async {
                // Apply cumulative stagger delay before executing the block
                if (idx > 0 && config.stagger.isPositive()) {
                    delay(idx * config.stagger.inWholeMilliseconds)
                }
                block()
            }
        }

        val activeIndices = deferreds.indices.toMutableSet()
        var firstFailure: Throwable? = null

        while (activeIndices.isNotEmpty()) {
            // Build list of active deferreds with their original indices
            val activeDeferreds = deferreds.mapIndexedNotNull { index, d ->
                if (index in activeIndices) index to d else null
            }

            if (activeDeferreds.isEmpty()) break
            // Use select to wait for first completion
            // onAwait works for both completed and pending deferreds
            val (winnerIndex, winner) = select {
                activeDeferreds.forEach { (index, d) ->
                    d.onAwait { index to d }
                }
            }

            // Since onAwait guarantees the deferred is completed, await() will return immediately
            // Use runCatching to safely handle both success and failure cases
            val result = runCatching {
                winner.await()
            }

            if (result.isSuccess) {
                // Success! Cancel all other attempts and return
                activeIndices.forEach { idx ->
                    if (idx != winnerIndex) {
                        deferreds[idx].cancel()
                    }
                }
                // Wait for cancellation to complete
                activeIndices.forEach { idx ->
                    if (idx != winnerIndex) {
                        runCatching { deferreds[idx].join() }
                    }
                }
                return@coroutineScope result.getOrThrow()
            } else {
                // Completion was a failure
                val failure = result.exceptionOrNull()

                // CancellationException should propagate immediately
                if (failure is CancellationException) {
                    // Cancel all other attempts and propagate cancellation
                    activeIndices.forEach { idx ->
                        if (idx != winnerIndex) {
                            deferreds[idx].cancel()
                        }
                    }
                    throw failure
                }

                // Remember the first failure
                if (failure != null && firstFailure == null) {
                    firstFailure = failure
                }

                // Remove the failed attempt and continue waiting for others
                activeIndices.remove(winnerIndex)
            }
        }

        // All attempts failed - throw the first failure
        throw firstFailure ?: IllegalStateException("All hedging attempts failed")
    }
}
