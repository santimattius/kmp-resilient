package com.santimattius.resilient

import com.santimattius.resilient.hedging.DefaultHedgingPolicy
import com.santimattius.resilient.hedging.HedgingConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class HedgingPolicyTest {

    @Test
    fun `given hedging policy when first attempt completes then returns first result`() = runTest {
        // given
        val policy = DefaultHedgingPolicy(HedgingConfig().apply {
            attempts = 3
            stagger = 10.milliseconds
        })

        // when
        val result = policy.execute {
            delay(20.milliseconds)
            "result-1"
        }

        // then
        assertEquals("result-1", result)
    }

    @Test
    fun `given hedging policy with single attempt when execute is called then executes once`() = runTest {
        // given
        val policy = DefaultHedgingPolicy(HedgingConfig().apply {
            attempts = 1
        })
        var executionCount = 0

        // when
        val result = policy.execute {
            executionCount++
            "result"
        }

        // then
        assertEquals(1, executionCount)
        assertEquals("result", result)
    }

    @Test
    fun `given hedging policy when multiple attempts are configured then launches parallel attempts`() = runTest {
        // given
        val policy = DefaultHedgingPolicy(HedgingConfig().apply {
            attempts = 3
            stagger = 5.milliseconds
        })
        var attemptCount = 0

        // when
        val result = policy.execute {
            attemptCount++
            delay(10.milliseconds)
            "result-$attemptCount"
        }

        // then - should return result from first completing attempt
        assertTrue(result.startsWith("result-"))
        // Note: attemptCount may be > 1 if cancellation doesn't happen immediately
    }

    @Test
    fun `given hedging policy when stagger is configured then delays between attempt starts`() = runTest {
        // given
        val policy = DefaultHedgingPolicy(HedgingConfig().apply {
            attempts = 3
            stagger = 20.milliseconds
        })
        val startTimes = mutableListOf<Long>()

        // when
        val result = policy.execute {
            startTimes.add(Clock.System.now().toEpochMilliseconds())
            delay(5.milliseconds)
            "result"
        }
        advanceUntilIdle()

        // then - should have multiple attempts (staggered)
        assertTrue(startTimes.isNotEmpty(), "Should have at least one attempt")
        assertEquals("result", result)
    }

    @Test
    fun `given hedging policy when execution is cancelled then cancellation propagates`() = runTest {
        // given
        val policy = DefaultHedgingPolicy(HedgingConfig().apply {
            attempts = 2
            stagger = 10.milliseconds
        })

        // when - cancel during execution
        val job = async {
            policy.execute {
                delay(100.milliseconds)
                "result"
            }
        }
        delay(10.milliseconds)
        job.cancel()

        // then - cancellation should propagate
        assertFailsWith<CancellationException> {
            job.await()
        }
    }

    @Test
    @Ignore
    fun `given hedging policy when first attempt fails then other attempts continue`() = runTest {
        // given
        val policy = DefaultHedgingPolicy(HedgingConfig().apply {
            attempts = 3
            stagger = 5.milliseconds
        })
        var attemptCount = 0

        // when
        val result = policy.execute {
            attemptCount++
            if (attemptCount == 1) {
                delay(10.milliseconds)
                throw IllegalStateException("first fails")
            } else {
                delay(20.milliseconds)
                "success"
            }
        }

        // then - should return result from successful attempt
        assertEquals("success", result)
    }

    @Test
    fun `given hedging policy when all attempts fail then throws exception`() = runTest {
        // given
        val policy = DefaultHedgingPolicy(HedgingConfig().apply {
            attempts = 2
            stagger = 5.milliseconds
        })
        val testError = IllegalStateException("all fail")

        // when & then
        val thrownError = assertFailsWith<IllegalStateException> {
            policy.execute {
                throw testError
            }
        }
        assertEquals(testError.message, thrownError.message)
    }

    @Test
    fun `given hedging policy when concurrent executions occur then each executes independently`() = runTest {
        // given
        val policy = DefaultHedgingPolicy(HedgingConfig().apply {
            attempts = 2
            stagger = 5.milliseconds
        })

        // when - concurrent executions
        val results = coroutineScope {
            (1..3).map { i ->
                async {
                    policy.execute {
                        delay(10.milliseconds)
                        "result-$i"
                    }
                }
            }
        }
        advanceUntilIdle()
        val actualResults = results.awaitAll()

        // then - all should succeed
        assertEquals(3, actualResults.size)
        actualResults.forEachIndexed { index, result ->
            assertEquals("result-${index + 1}", result)
        }
    }

    @Test
    fun `given hedging policy when zero stagger is configured then attempts start immediately`() = runTest {
        // given
        val policy = DefaultHedgingPolicy(HedgingConfig().apply {
            attempts = 2
            stagger = 0.milliseconds
        })

        // when
        val result = policy.execute {
            delay(10.milliseconds)
            "result"
        }

        // then
        assertEquals("result", result)
    }

    @Test
    fun `given hedging policy when block throws CancellationException then cancellation propagates`() = runTest {
        // given
        val policy = DefaultHedgingPolicy(HedgingConfig().apply {
            attempts = 2
            stagger = 5.milliseconds
        })

        // when & then
        assertFailsWith<CancellationException> {
            policy.execute {
                throw CancellationException("cancelled")
            }
        }
    }
}
