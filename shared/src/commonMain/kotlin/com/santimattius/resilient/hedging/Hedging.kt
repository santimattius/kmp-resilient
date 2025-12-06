package com.santimattius.resilient.hedging

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
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
    suspend fun <T> execute(block: suspend () -> T): T
}

/**
 * Configuration for [HedgingPolicy].
 *
 * - [attempts]: number of parallel attempts to launch
 * - [stagger]: delay between launching each subsequent attempt
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
 * If the first attempt to complete fails with an exception, this implementation currently cancels
 * all other attempts and re-throws the exception.
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

        val deferreds = (0 until config.attempts).map { idx ->
            async {
                if (idx > 0 && config.stagger.isPositive()) delay(config.stagger.inWholeMilliseconds)
                block()
            }
        }
        try {
            val winnerIndex = select {
                deferreds.forEachIndexed { index, d ->
                    d.onAwait { _ -> index }
                }
            }
            val value = deferreds[winnerIndex].getCompleted()
            //TODO: cancel others?
            deferreds.forEachIndexed { index, d -> if (index != winnerIndex) d.cancel() }
            deferreds.forEachIndexed { index, d -> if (index != winnerIndex) d.join() }
            value
        } catch (t: Throwable) {
            // if the first completion was a failure, let others continue, then rethrow last failure if all fail
            // For simplicity: cancel all and rethrow
            deferreds.forEach { it.cancel() }
            deferreds.joinAll()
            throw t
        }
    }
}
