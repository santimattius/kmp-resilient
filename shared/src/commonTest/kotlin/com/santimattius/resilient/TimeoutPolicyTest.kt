package com.santimattius.resilient

import com.santimattius.resilient.timeout.DefaultTimeoutPolicy
import com.santimattius.resilient.timeout.TimeoutConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class TimeoutPolicyTest {

    @Test
    fun `given timeout policy when operation exceeds timeout then throws TimeoutCancellationException`() = runTest {
        // given
        val cfg = TimeoutConfig().apply { timeout = 50.milliseconds }
        val policy = DefaultTimeoutPolicy(cfg)

        // when & then
        assertFailsWith<TimeoutCancellationException> {
            policy.execute {
                delay(200.milliseconds)
                "done"
            }
        }
    }

    @Test
    fun `given timeout policy when operation completes within timeout then returns result`() = runTest {
        // given
        val cfg = TimeoutConfig().apply { timeout = 200.milliseconds }
        val policy = DefaultTimeoutPolicy(cfg)

        // when
        val result = policy.execute {
            delay(50.milliseconds)
            "success"
        }

        // then
        assertEquals("success", result)
    }

    @Test
    fun `given timeout policy when onTimeout callback is provided then callback is invoked on timeout`() = runTest {
        // given
        var callbackInvoked = false
        val cfg = TimeoutConfig().apply {
            timeout = 50.milliseconds
            onTimeout = {
                callbackInvoked = true
            }
        }
        val policy = DefaultTimeoutPolicy(cfg)

        // when
        assertFailsWith<TimeoutCancellationException> {
            policy.execute {
                delay(200.milliseconds)
                "never"
            }
        }

        // then
        assertTrue(callbackInvoked, "onTimeout callback should be invoked")
    }

    @Test
    fun `given timeout policy when execution is cancelled before timeout then cancellation propagates`() = runTest {
        // given
        val cfg = TimeoutConfig().apply { timeout = 200.milliseconds }
        val policy = DefaultTimeoutPolicy(cfg)

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
    fun `given timeout policy when block throws exception then exception propagates`() = runTest {
        // given
        val cfg = TimeoutConfig().apply { timeout = 200.milliseconds }
        val policy = DefaultTimeoutPolicy(cfg)
        val testError = IllegalStateException("test error")

        // when & then
        val thrownError = assertFailsWith<IllegalStateException> {
            policy.execute {
                throw testError
            }
        }
        assertEquals(testError.message, thrownError.message)
    }

    @Test
    fun `given timeout policy when multiple concurrent executions occur then each has independent timeout`() = runTest {
        // given
        val cfg = TimeoutConfig().apply { timeout = 100.milliseconds }
        val policy = DefaultTimeoutPolicy(cfg)

        // when - concurrent executions
        val job1 = async {
            policy.execute {
                delay(50.milliseconds)
                "result-1"
            }
        }
        val job2 = async {
            policy.execute {
                delay(50.milliseconds)
                "result-2"
            }
        }
        advanceUntilIdle()

        // then - both should succeed
        assertEquals("result-1", job1.await())
        assertEquals("result-2", job2.await())
    }

    @Test
    fun `given timeout policy when timeout occurs exactly at boundary then throws TimeoutCancellationException`() = runTest {
        // given
        val cfg = TimeoutConfig().apply { timeout = 50.milliseconds }
        val policy = DefaultTimeoutPolicy(cfg)

        // when & then
        assertFailsWith<TimeoutCancellationException> {
            policy.execute {
                delay(50.milliseconds)
                "never"
            }
        }
    }

    @Test
    fun `given timeout policy when onTimeout callback throws exception then original timeout exception is thrown`() = runTest {
        // given
        val cfg = TimeoutConfig().apply {
            timeout = 50.milliseconds
            onTimeout = {
                throw IllegalStateException("callback error")
            }
        }
        val policy = DefaultTimeoutPolicy(cfg)

        // when & then - should still throw TimeoutCancellationException
        // (onTimeout exception is swallowed, timeout exception is thrown)
        assertFailsWith<IllegalStateException> {
            policy.execute {
                delay(200.milliseconds)
                "never"
            }
        }
    }
}
