package com.santimattius.resilient.test

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds

class PolicyBuildersTest {

    @Test
    fun `given retryPolicy when block fails then retries`() = runTest {
        val scope = TestResilientScope()
        val policy = PolicyBuilders.retryPolicy(scope, maxAttempts = 3)

        var attempts = 0
        val result = policy.execute {
            attempts++
            if (attempts < 3) throw RuntimeException("fail")
            "success"
        }

        assertEquals("success", result)
        assertEquals(3, attempts)
    }

    @Test
    fun `given timeoutPolicy when block exceeds timeout then throws`() = runTest {
        val scope = TestResilientScope()
        val policy = PolicyBuilders.timeoutPolicy(scope, timeout = 100.milliseconds)

        assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> {
            policy.execute {
                kotlinx.coroutines.delay(200)
                "should timeout"
            }
        }
    }

    @Test
    fun `given circuitBreakerPolicy when failures exceed threshold then opens circuit`() = runTest {
        val scope = TestResilientScope()
        val policy = PolicyBuilders.circuitBreakerPolicy(
            scope,
            failureThreshold = 2,
            timeout = 1000.milliseconds
        )

        // Fail twice to open circuit
        repeat(2) {
            assertFailsWith<RuntimeException> {
                policy.execute { throw RuntimeException("fail") }
            }
        }

        // Third call should be rejected by open circuit
        assertFailsWith<Exception> {
            policy.execute { "should be rejected" }
        }
    }

    @Test
    fun `given bulkheadPolicy when concurrent calls exceed limit then rejects`() = runTest {
        val scope = TestResilientScope()
        val policy = PolicyBuilders.bulkheadPolicy(
            scope,
            maxConcurrentCalls = 1,
            maxWaitingCalls = 0
        )

        val gate = CompletableDeferred<Unit>()
        val job1 = launch {
            policy.execute {
                gate.await()
                "first"
            }
        }

        testScheduler.advanceUntilIdle()

        // Second call should be rejected
        assertFailsWith<Exception> {
            policy.execute { "should be rejected" }
        }

        gate.complete(Unit)
        job1.join()
    }

    @Test
    fun `given rateLimiterPolicy when calls exceed maxCalls then rejects`() = runTest {
        val scope = TestResilientScope()
        val policy = PolicyBuilders.rateLimiterPolicy(
            scope,
            maxCalls = 2,
            period = 1000.milliseconds
        )

        // First two calls succeed
        policy.execute { "first" }
        policy.execute { "second" }

        // Third call should be rejected
        assertFailsWith<Exception> {
            policy.execute { "should be rejected" }
        }
    }
}
