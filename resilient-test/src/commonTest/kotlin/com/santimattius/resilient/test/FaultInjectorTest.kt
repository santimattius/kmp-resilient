package com.santimattius.resilient.test

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class FaultInjectorTest {

    @Test
    fun `given no faults when execute then block succeeds`() = runTest {
        val injector = FaultInjector.builder().build()
        val result = injector.execute { "success" }
        assertEquals("success", result)
    }

    @Test
    fun `given failureRate 1_0 when execute then always throws`() = runTest {
        val injector = FaultInjector.builder()
            .failureRate(1.0)
            .build()

        assertFailsWith<FaultInjectedException> {
            injector.execute { "should not return" }
        }
    }

    @Test
    fun `given custom exception when failure injected then throws custom exception`() = runTest {
        class CustomException : Exception("custom")
        val injector = FaultInjector.builder()
            .failureRate(1.0)
            .exception { CustomException() }
            .build()

        val error = assertFailsWith<CustomException> {
            injector.execute { "should not return" }
        }
        assertEquals("custom", error.message)
    }

    @Test
    fun `given delay when execute then adds delay before block`() = runTest {
        val injector = FaultInjector.builder()
            .delay(50.milliseconds)
            .build()

        val start = testScheduler.currentTime
        injector.execute { "delayed" }
        val elapsed = testScheduler.currentTime - start

        assertTrue(elapsed >= 50, "Expected delay >= 50ms, got $elapsed ms")
    }

    @Test
    fun `given failureRate 0_5 when execute many times then approximately half fail`() = runTest {
        val injector = FaultInjector.builder()
            .failureRate(0.5)
            .build()

        var failures = 0
        var successes = 0
        repeat(100) {
            try {
                injector.execute { "ok" }
                successes++
            } catch (e: FaultInjectedException) {
                failures++
            }
        }

        // With 100 trials and p=0.5, expect roughly 40-60 failures (allow wide margin for randomness)
        assertTrue(failures in 30..70, "Expected ~50 failures, got $failures")
        assertTrue(successes in 30..70, "Expected ~50 successes, got $successes")
    }

    @Test
    fun `given invalid failureRate when build then throws`() {
        val error = assertFailsWith<IllegalArgumentException> {
            FaultInjector.builder().failureRate(1.5).build()
        }
        assertTrue(error.message!!.contains("failureRate must be in"))
    }
}
