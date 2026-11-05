package com.santimattius.resilient.composition

import com.santimattius.resilient.annotations.ResilientExperimentalApi
import com.santimattius.resilient.circuitbreaker.TestTimeSource
import com.santimattius.resilient.fallback.FallbackConfig
import com.santimattius.resilient.ratelimiter.RateLimitExceededException
import com.santimattius.resilient.ratelimiter.RateLimiterRegistry
import com.santimattius.resilient.retry.FixedBackoff
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Cancellation and timeout semantics of the typed pipeline, checked against the legacy `resilient { }`
 * pipeline built from the same template. Both run the same engine, so every scenario that applies to
 * both must behave identically.
 */
@OptIn(ResilientExperimentalApi::class, ExperimentalCoroutinesApi::class)
class TypedPolicyCancellationTest {

    private class Pipeline(
        val name: String,
        val fallbackCalls: () -> Int,
        val run: suspend (suspend () -> Int) -> Int,
    )

    private fun template(
        retryAttempts: Int,
        timeoutAfter: Duration? = null,
        onFallback: () -> Unit,
    ) = resilienceTemplate {
        retry {
            maxAttempts = retryAttempts
            backoffStrategy = FixedBackoff(Duration.ZERO)
        }
        if (timeoutAfter != null) {
            timeout { timeout = timeoutAfter }
        }
        fallback(FallbackConfig<Any?> { onFallback(); -1 })
    }

    /** One legacy and one typed pipeline built from identical templates, each with its own fallback counter. */
    private fun pipelines(retryAttempts: Int, timeoutAfter: Duration? = null): List<Pipeline> {
        var legacyFallbacks = 0
        var typedFallbacks = 0
        val legacy = resilient(ResilientScope()) {
            include(template(retryAttempts, timeoutAfter) { legacyFallbacks++ })
        }
        val typed = resilientFor<Unit>(ResilientScope()) {
            include(template(retryAttempts, timeoutAfter) { typedFallbacks++ })
        }
        return listOf(
            Pipeline("legacy", { legacyFallbacks }) { block -> legacy.execute { block() } },
            Pipeline("typed", { typedFallbacks }) { block -> typed.execute(Unit) { block() } },
        )
    }

    @Test
    fun `given a caller cancelled during execute then CancellationException propagates without retry or fallback`() =
        runTest {
            pipelines(retryAttempts = 3).forEach { pipeline ->
                var attempts = 0
                var observed: Throwable? = null
                val job = launch(start = CoroutineStart.UNDISPATCHED) {
                    pipeline.run {
                        attempts++
                        try {
                            awaitCancellation()
                        } catch (e: CancellationException) {
                            observed = e
                            throw e
                        }
                    }
                }
                runCurrent()
                job.cancelAndJoin()

                assertEquals(1, attempts, "${pipeline.name}: cancellation must not be retried")
                assertTrue(observed is CancellationException, "${pipeline.name}: block sees the cancellation")
                assertEquals(0, pipeline.fallbackCalls(), "${pipeline.name}: cancellation must not run fallback")
                assertTrue(job.isCancelled, "${pipeline.name}: caller job ends cancelled")
            }
        }

    @Test
    fun `given a configured timeout then it surfaces as TimeoutCancellationException and fallback does not swallow it`() =
        runTest {
            pipelines(retryAttempts = 1, timeoutAfter = 100.milliseconds).forEach { pipeline ->
                assertFailsWith<TimeoutCancellationException>(pipeline.name) {
                    pipeline.run { delay(10_000); 1 }
                }
                assertEquals(0, pipeline.fallbackCalls(), "${pipeline.name}: timeout is a cancellation, not a fallback trigger")
            }
        }

    @Test
    fun `given a timeout and retries then both pipelines retry the timed out attempt the same number of times`() =
        runTest {
            val attemptsByPipeline = pipelines(retryAttempts = 3, timeoutAfter = 100.milliseconds).associate { pipeline ->
                var attempts = 0
                assertFailsWith<TimeoutCancellationException>(pipeline.name) {
                    pipeline.run { attempts++; delay(10_000); 1 }
                }
                pipeline.name to attempts
            }

            assertEquals(attemptsByPipeline["legacy"], attemptsByPipeline["typed"], "typed must match legacy: $attemptsByPipeline")
        }

    @Test
    fun `given a caller waiting on the global limiter when cancelled then the block never runs`() =
        runTest {
            val legacy = resilient(ResilientScope()) {
                rateLimiter { maxCalls = 1; period = 10.minutes; timeoutWhenLimited = 1.hours }
            }
            val typed = resilientFor<Unit>(ResilientScope()) {
                rateLimiter { maxCalls = 1; period = 10.minutes; timeoutWhenLimited = 1.hours }
            }
            val runs = listOf<Pair<String, suspend (suspend () -> Int) -> Int>>(
                "legacy" to { block -> legacy.execute { block() } },
                "typed" to { block -> typed.execute(Unit) { block() } },
            )

            runs.forEach { (name, run) ->
                var executed = 0
                run { executed++; 1 }
                val waiter = launch(start = CoroutineStart.UNDISPATCHED) { run { executed++; 1 } }
                runCurrent()
                waiter.cancelAndJoin()

                assertEquals(1, executed, "$name: the cancelled waiter must not run its block")
                assertTrue(waiter.isCancelled, "$name: waiter ends cancelled")
            }
        }

    @Test
    fun `given a caller waiting on the keyed limiter when cancelled then the block never runs`() =
        runTest {
            val clock = TestTimeSource()
            val typed = resilientFor<String>(ResilientScope()) {
                keyedRateLimiter<String> {
                    keyProvider = { it }
                    maxCalls = 1
                    period = 10.minutes
                    timeoutWhenLimited = 1.hours
                    timeSource = clock
                }
            }
            var executed = 0
            typed.execute("tenant") { executed++ }
            val waiter = launch(start = CoroutineStart.UNDISPATCHED) { typed.execute("tenant") { executed++ } }
            runCurrent()
            waiter.cancelAndJoin()

            assertEquals(1, executed)
            assertTrue(waiter.isCancelled)
        }

    @Test
    fun `given a cancelled execution and a closed policy then registry-owned quota is not reset`() =
        runTest {
            class Built(val run: suspend (suspend () -> Int) -> Int, val close: () -> Unit)

            val legacyRegistry = RateLimiterRegistry()
            val typedRegistry = RateLimiterRegistry()
            val builders = listOf<Pair<String, () -> Built>>(
                "legacy" to {
                    val policy = resilient(ResilientScope()) {
                        rateLimiterNamed(legacyRegistry, "api") { maxCalls = 1; period = 10.minutes }
                    }
                    Built({ block -> policy.execute { block() } }, policy::close)
                },
                "typed" to {
                    val policy = resilientFor<Unit>(ResilientScope()) {
                        rateLimiterNamed(typedRegistry, "api") { maxCalls = 1; period = 10.minutes }
                    }
                    Built({ block -> policy.execute(Unit) { block() } }, policy::close)
                },
            )

            builders.forEach { (name, build) ->
                val first = build()
                val cancelled = launch(start = CoroutineStart.UNDISPATCHED) { first.run { awaitCancellation() } }
                runCurrent()
                cancelled.cancelAndJoin()
                first.close()

                val second = build()
                assertFailsWith<RateLimitExceededException>("$name: the permit consumed before cancellation stays consumed") {
                    second.run { 1 }
                }
                second.close()
            }
        }
}
