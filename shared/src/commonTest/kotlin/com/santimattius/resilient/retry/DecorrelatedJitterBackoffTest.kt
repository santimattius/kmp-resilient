package com.santimattius.resilient.retry

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class DecorrelatedJitterBackoffTest {

    // ── Scenario 1: every delay is in [base, cap] ────────────────────────────

    @Test
    fun `given base 100ms and cap 2s when computeDelayMs called for attempts 1 to 10 then all values are in range`() =
        runTest {
            val backoff = DecorrelatedJitterBackoff(base = 100.milliseconds, cap = 2.seconds)

            repeat(10) { index ->
                val attempt = index + 1
                val delayMs = backoff.computeDelayMs(attempt)
                assertTrue(delayMs >= 100L, "attempt $attempt: delay $delayMs < base 100ms")
                assertTrue(delayMs <= 2000L, "attempt $attempt: delay $delayMs > cap 2000ms")
            }
        }

    @Test
    fun `given base 50ms and cap 500ms when computeDelayMs called for attempts 1 to 20 then all values are in range`() =
        runTest {
            // Triangulation: different base/cap values still respect the range
            val backoff = DecorrelatedJitterBackoff(base = 50.milliseconds, cap = 500.milliseconds)

            repeat(20) { index ->
                val attempt = index + 1
                val delayMs = backoff.computeDelayMs(attempt)
                assertTrue(delayMs >= 50L, "attempt $attempt: delay $delayMs < base 50ms")
                assertTrue(delayMs <= 500L, "attempt $attempt: delay $delayMs > cap 500ms")
            }
        }

    // ── Scenario 2: invalid base/cap throws ──────────────────────────────────

    @Test
    fun `given cap less than base when constructed then throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            DecorrelatedJitterBackoff(base = 500.milliseconds, cap = 200.milliseconds)
        }
    }

    @Test
    fun `given zero base when constructed then throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            DecorrelatedJitterBackoff(base = 0.milliseconds, cap = 1.seconds)
        }
    }

    // ── Scenario 3: used inside a retry policy ───────────────────────────────

    @Test
    fun `given retry policy with decorrelated jitter when block fails every attempt then retries 3 times and rethrows`() =
        runTest {
            val backoff = DecorrelatedJitterBackoff(base = 50.milliseconds, cap = 1.seconds)
            val cfg = RetryPolicyConfig().apply {
                maxAttempts = 4
                backoffStrategy = backoff
            }
            val policy = DefaultRetryPolicy(cfg)
            var attempts = 0
            val targetException = RuntimeException("always fails")

            val thrown = assertFailsWith<RuntimeException> {
                policy.execute {
                    attempts++
                    throw targetException
                }
            }

            assertTrue(attempts == 4, "Expected 4 attempts (1 initial + 3 retries), got $attempts")
            assertTrue(thrown === targetException, "Should rethrow the last exception instance")
        }

    @Test
    fun `given retry policy with decorrelated jitter when block eventually succeeds then returns result`() =
        runTest {
            // Triangulation for Scenario 3: success path
            val backoff = DecorrelatedJitterBackoff(base = 50.milliseconds, cap = 1.seconds)
            val cfg = RetryPolicyConfig().apply {
                maxAttempts = 4
                backoffStrategy = backoff
            }
            val policy = DefaultRetryPolicy(cfg)
            var attempts = 0

            val result = policy.execute {
                attempts++
                if (attempts < 3) throw RuntimeException("fail") else "success"
            }

            assertTrue(attempts == 3, "Expected 3 attempts, got $attempts")
            assertTrue(result == "success")
        }
}
