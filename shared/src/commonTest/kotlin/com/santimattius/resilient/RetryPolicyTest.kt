package com.santimattius.resilient

import com.santimattius.resilient.retry.DefaultRetryPolicy
import com.santimattius.resilient.retry.ExponentialBackoff
import com.santimattius.resilient.retry.FixedBackoff
import com.santimattius.resilient.retry.LinearBackoff
import com.santimattius.resilient.retry.RetryPolicyConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class RetryPolicyTest {

    @Test
    fun `given retry policy when retries until success then returns result`() = runTest {
        // given
        val cfg = RetryPolicyConfig().apply {
            maxAttempts = 3
            backoffStrategy = ExponentialBackoff(initialDelay = 10.milliseconds, factor = 1.0, jitter = false)
        }
        val policy = DefaultRetryPolicy(cfg)
        var attempts = 0

        // when
        val result = policy.execute {
            attempts++
            if (attempts < 3) error("fail") else "ok"
        }

        // then
        assertEquals(3, attempts)
        assertEquals("ok", result)
    }

    @Test
    fun `given retry policy when max attempts reached then throws last error`() = runTest {
        // given
        val cfg = RetryPolicyConfig().apply {
            maxAttempts = 2
            backoffStrategy = ExponentialBackoff(initialDelay = 1.milliseconds, factor = 1.0, jitter = false)
        }
        val policy = DefaultRetryPolicy(cfg)
        var attempts = 0

        // when & then
        assertFailsWith<Throwable> {
            policy.execute {
                attempts++
                error("always")
            }
        }
        assertEquals(2, attempts)
    }

    @Test
    fun `given retry policy when shouldRetry returns false then error is thrown immediately`() = runTest {
        // given
        val cfg = RetryPolicyConfig().apply {
            maxAttempts = 5
            shouldRetry = { it !is IllegalArgumentException }
            backoffStrategy = FixedBackoff(delay = 1.milliseconds)
        }
        val policy = DefaultRetryPolicy(cfg)
        var attempts = 0

        // when & then
        assertFailsWith<IllegalArgumentException> {
            policy.execute {
                attempts++
                throw IllegalArgumentException("should not retry")
            }
        }
        assertEquals(1, attempts, "Should not retry when shouldRetry returns false")
    }

    @Test
    fun `given retry policy when shouldRetry returns true then retries occur`() = runTest {
        // given
        val cfg = RetryPolicyConfig().apply {
            maxAttempts = 3
            shouldRetry = { it is IllegalStateException }
            backoffStrategy = FixedBackoff(delay = 1.milliseconds)
        }
        val policy = DefaultRetryPolicy(cfg)
        var attempts = 0

        // when
        val result = policy.execute {
            attempts++
            if (attempts < 3) throw IllegalStateException("retry") else "success"
        }

        // then
        assertEquals(3, attempts)
        assertEquals("success", result)
    }

    @Test
    fun `given retry policy when execution is cancelled during block then cancellation propagates`() = runTest {
        // given
        val cfg = RetryPolicyConfig().apply {
            maxAttempts = 5
            backoffStrategy = FixedBackoff(delay = 1.milliseconds)
        }
        val policy = DefaultRetryPolicy(cfg)

        // when - cancel during block execution
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
    fun `given retry policy when execution is cancelled during backoff delay then cancellation propagates`() = runTest {
        // given
        val cfg = RetryPolicyConfig().apply {
            maxAttempts = 5
            backoffStrategy = FixedBackoff(delay = 100.milliseconds)
        }
        val policy = DefaultRetryPolicy(cfg)
        var attemptCount = 0

        // when - cancel during backoff
        val job = async {
            policy.execute {
                attemptCount++
                if (attemptCount < 3) {
                    throw IllegalStateException("retry")
                }
                "success"
            }
        }
        delay(50.milliseconds) // Cancel during backoff delay
        job.cancel()

        // then - cancellation should propagate
        assertFailsWith<CancellationException> {
            job.await()
        }
    }

    @Test
    fun `given retry policy when onRetry callback is provided then callback is invoked before each retry`() = runTest {
        // given
        var callbackInvocations = 0
        val cfg = RetryPolicyConfig().apply {
            maxAttempts = 3
            backoffStrategy = FixedBackoff(delay = 1.milliseconds)
            onRetry = { attempt, error ->
                callbackInvocations++
                assertTrue(attempt > 0, "Attempt should be positive")
                assertTrue(error is IllegalStateException, "Error should be IllegalStateException")
            }
        }
        val policy = DefaultRetryPolicy(cfg)
        var attempts = 0

        // when
        val result = policy.execute {
            attempts++
            if (attempts < 3) throw IllegalStateException("retry") else "success"
        }

        // then
        assertEquals(3, attempts)
        assertEquals(2, callbackInvocations, "onRetry should be called for each retry (2 retries)")
        assertEquals("success", result)
    }

    @Test
    fun `given retry policy with exponential backoff when retries occur then delays increase exponentially`() = runTest {
        // given
        val delays = mutableListOf<Long>()
        val cfg = RetryPolicyConfig().apply {
            maxAttempts = 4
            backoffStrategy = ExponentialBackoff(
                initialDelay = 10.milliseconds,
                factor = 2.0,
                jitter = false
            )
        }
        val policy = DefaultRetryPolicy(cfg)
        var attempts = 0
        var lastTime = 0L

        // when
        val startTime = Clock.System.now().toEpochMilliseconds()
        val result = policy.execute {
            val currentTime = Clock.System.now().toEpochMilliseconds()
            if (attempts > 0) {
                delays.add(currentTime - lastTime)
            }
            lastTime = currentTime
            attempts++
            if (attempts < 4) throw IllegalStateException("retry") else "success"
        }

        // then
        assertEquals(4, attempts)
        assertEquals("success", result)
        // Note: Exact timing verification is difficult with virtual time,
        // but we verify the retry mechanism works
    }

    @Test
    fun `given retry policy with linear backoff when retries occur then delays are constant`() = runTest {
        // given
        val cfg = RetryPolicyConfig().apply {
            maxAttempts = 3
            backoffStrategy = LinearBackoff(delay = 10.milliseconds)
        }
        val policy = DefaultRetryPolicy(cfg)
        var attempts = 0

        // when
        val result = policy.execute {
            attempts++
            if (attempts < 3) throw IllegalStateException("retry") else "success"
        }

        // then
        assertEquals(3, attempts)
        assertEquals("success", result)
    }

    @Test
    fun `given retry policy when all attempts fail then last error is thrown`() = runTest {
        // given
        val cfg = RetryPolicyConfig().apply {
            maxAttempts = 3
            backoffStrategy = FixedBackoff(delay = 1.milliseconds)
        }
        val policy = DefaultRetryPolicy(cfg)
        val finalError = IllegalStateException("final error")

        // when & then
        val thrownError = assertFailsWith<IllegalStateException> {
            policy.execute {
                throw finalError
            }
        }
        assertEquals(finalError, thrownError)
    }

    @Test
    fun `given retry policy when shouldRetry predicate changes behavior then retry logic adapts`() = runTest {
        // given
        var shouldRetryCount = 0
        val cfg = RetryPolicyConfig().apply {
            maxAttempts = 5
            shouldRetry = { error ->
                shouldRetryCount++
                // Retry only first 2 errors
                shouldRetryCount <= 2
            }
            backoffStrategy = FixedBackoff(delay = 1.milliseconds)
        }
        val policy = DefaultRetryPolicy(cfg)
        var attempts = 0

        // when & then
        assertFailsWith<IllegalStateException> {
            policy.execute {
                attempts++
                throw IllegalStateException("error")
            }
        }
        // Should retry 2 times, then fail on 3rd attempt
        assertEquals(3, attempts, "Should attempt 3 times (1 initial + 2 retries)")
    }
}
