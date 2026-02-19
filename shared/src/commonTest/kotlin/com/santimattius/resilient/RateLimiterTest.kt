package com.santimattius.resilient

import com.santimattius.resilient.circuitbreaker.TestTimeSource
import com.santimattius.resilient.ratelimiter.DefaultRateLimiter
import com.santimattius.resilient.ratelimiter.RateLimitExceededException
import com.santimattius.resilient.ratelimiter.RateLimiterConfig
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
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class RateLimiterTest {

    @Test
    fun `given rate limiter with tokens available when execute is called then executes immediately`() = runTest {
        // given
        val cfg = RateLimiterConfig().apply {
            maxCalls = 2
            period = 100.milliseconds
        }
        val rateLimiter = DefaultRateLimiter(cfg)

        // when
        val result1 = rateLimiter.execute { "result1" }
        val result2 = rateLimiter.execute { "result2" }

        // then
        assertEquals("result1", result1)
        assertEquals("result2", result2)
    }

    @Test
    fun `given rate limiter with no timeout when tokens exhausted then throws immediately`() = runTest {
        // given
        val cfg = RateLimiterConfig().apply {
            maxCalls = 1
            period = 100.milliseconds
            timeoutWhenLimited = null
        }
        val rateLimiter = DefaultRateLimiter(cfg)
        rateLimiter.execute { "first" }

        // when & then
        val exception = assertFailsWith<RateLimitExceededException> {
            rateLimiter.execute { "second" }
        }
        assertTrue(exception.retryAfter != null, "Exception should include retryAfter duration")
    }

    @Test
    fun `given rate limiter with timeout when wait duration exceeds timeout then throws immediately`() = runTest {
        // given
        val cfg = RateLimiterConfig().apply {
            maxCalls = 1
            period = 100.milliseconds
            timeoutWhenLimited = 10.milliseconds
        }
        val rateLimiter = DefaultRateLimiter(cfg)
        rateLimiter.execute { "first" }

        // when & then
        val exception = assertFailsWith<RateLimitExceededException> {
            rateLimiter.execute { "second" }
        }
        assertTrue(exception.retryAfter != null, "Exception should include retryAfter duration")
    }

    @Test
    fun `given rate limiter with timeout when wait duration is within timeout then waits and executes`() = runTest {
        // given
        val cfg = RateLimiterConfig().apply {
            maxCalls = 1
            period = 50.milliseconds
            timeoutWhenLimited = 100.milliseconds
        }
        val rateLimiter = DefaultRateLimiter(cfg)
        rateLimiter.execute { "first" }

        // when
        val deferred = async {
            rateLimiter.execute { "second" }
        }
        advanceTimeBy(50.milliseconds.inWholeMilliseconds)
        advanceUntilIdle()
        val result = deferred.await()

        // then
        assertEquals("second", result)
    }

    @Test
    @Ignore
    fun `given rate limiter when timeout expires before token available then throws RateLimitExceededException`() = runTest {
        // given
        val testTimeSource = TestTimeSource()
        testTimeSource.setTime(1000)
        val cfg = RateLimiterConfig().apply {
            maxCalls = 1
            period = 100.milliseconds
            timeoutWhenLimited = 30.milliseconds
        }
        val rateLimiter = DefaultRateLimiter(cfg, timeSource = testTimeSource)
        rateLimiter.execute { "first" }

        // when & then
        val deferred = async {
            rateLimiter.execute { "second" }
        }
        // Advance time so timeout expires (30ms) before delay completes (100ms)
        testTimeSource.advanceTimeBy(30.milliseconds.inWholeMilliseconds)
        advanceTimeBy(30.milliseconds.inWholeMilliseconds)
        advanceUntilIdle()

        // Exception should be thrown when awaiting
        try {
            deferred.await()
            assertTrue(false, "Should have thrown RateLimitExceededException")
        } catch (e: RateLimitExceededException) {
            assertTrue(e.retryAfter != null, "Exception should include retryAfter duration")
        }
    }

    @Test
    fun `given rate limiter when period elapses then tokens are refilled`() = runTest {
        // given
        val testTimeSource = TestTimeSource()
        testTimeSource.setTime(1000)
        val cfg = RateLimiterConfig().apply {
            maxCalls = 2
            period = 50.milliseconds
        }
        val rateLimiter = DefaultRateLimiter(cfg, timeSource = testTimeSource)
        rateLimiter.execute { "first" }
        rateLimiter.execute { "second" }

        // when - advance time beyond period
        testTimeSource.advanceTimeBy(50.milliseconds.inWholeMilliseconds)
        advanceTimeBy(50.milliseconds.inWholeMilliseconds)
        advanceUntilIdle()

        // then - should be able to execute again
        val result3 = rateLimiter.execute { "third" }
        val result4 = rateLimiter.execute { "fourth" }
        assertEquals("third", result3)
        assertEquals("fourth", result4)
    }

    @Test
    fun `given rate limiter when multiple periods elapse then tokens refill but are capped at maxCalls`() = runTest {
        // given
        val testTimeSource = TestTimeSource()
        testTimeSource.setTime(1000)
        val cfg = RateLimiterConfig().apply {
            maxCalls = 1
            period = 50.milliseconds
        }
        val rateLimiter = DefaultRateLimiter(cfg, timeSource = testTimeSource)
        rateLimiter.execute { "first" }

        // when - advance time by 2 periods
        testTimeSource.advanceTimeBy(100.milliseconds.inWholeMilliseconds)
        advanceTimeBy(100.milliseconds.inWholeMilliseconds)
        advanceUntilIdle()

        // then - should have 1 token (capped at maxCalls, not 2)
        val result2 = rateLimiter.execute { "second" }
        assertEquals("second", result2)
        
        // Should NOT be able to execute immediately after (tokens exhausted)
        assertFailsWith<RateLimitExceededException> {
            rateLimiter.execute { "third" }
        }
    }

    @Test
    fun `given rate limiter with maxCalls greater than 1 when multiple periods elapse then tokens accumulate up to maxCalls`() = runTest {
        // given
        val testTimeSource = TestTimeSource()
        testTimeSource.setTime(1000)
        val cfg = RateLimiterConfig().apply {
            maxCalls = 3
            period = 50.milliseconds
        }
        val rateLimiter = DefaultRateLimiter(cfg, timeSource = testTimeSource)
        rateLimiter.execute { "first" }
        rateLimiter.execute { "second" }
        rateLimiter.execute { "third" }

        // when - advance time by 2 periods (should refill 2 * 3 = 6 tokens, but capped at 3)
        testTimeSource.advanceTimeBy(100.milliseconds.inWholeMilliseconds)
        advanceTimeBy(100.milliseconds.inWholeMilliseconds)
        advanceUntilIdle()

        // then - should have 3 tokens (capped at maxCalls)
        val result4 = rateLimiter.execute { "fourth" }
        val result5 = rateLimiter.execute { "fifth" }
        val result6 = rateLimiter.execute { "sixth" }
        assertEquals("fourth", result4)
        assertEquals("fifth", result5)
        assertEquals("sixth", result6)
        
        // Should NOT be able to execute immediately after (tokens exhausted)
        assertFailsWith<RateLimitExceededException> {
            rateLimiter.execute { "seventh" }
        }
    }

    @Test
    fun `given rate limiter when concurrent calls execute then all execute within rate limit`() = runTest {
        // given
        val cfg = RateLimiterConfig().apply {
            maxCalls = 5
            period = 100.milliseconds
        }
        val rateLimiter = DefaultRateLimiter(cfg)

        // when
        val results = coroutineScope {
            (1..5).map { i ->
                async {
                    rateLimiter.execute { "result-$i" }
                }
            }
        }
        advanceUntilIdle()
        val actualResults = results.awaitAll()

        // then
        assertEquals(5, actualResults.size)
        actualResults.forEachIndexed { index, result ->
            assertEquals("result-${index + 1}", result)
        }
    }

    @Test
    fun `given rate limiter when concurrent calls exceed limit then some wait or throw`() = runTest {
        // given
        val cfg = RateLimiterConfig().apply {
            maxCalls = 2
            period = 100.milliseconds
            timeoutWhenLimited = 50.milliseconds
        }
        val rateLimiter = DefaultRateLimiter(cfg)

        // when
        val results = coroutineScope {
            (1..4).map { i ->
                async {
                    try {
                        rateLimiter.execute { "result-$i" }
                    } catch (e: RateLimitExceededException) {
                        "rate-limited-$i"
                    }
                }
            }
        }
        advanceUntilIdle()
        val actualResults = results.awaitAll()

        // then - first 2 should succeed, others may succeed if timeout allows or be rate-limited
        assertEquals(4, actualResults.size)
        val successCount = actualResults.count { it.startsWith("result-") }
        assertTrue(successCount >= 2, "At least 2 calls should succeed")
    }

    @Test
    fun `given rate limiter with onRateLimited callback when rate limited then callback is invoked`() = runTest {
        // given
        var callbackInvoked = false
        var callbackWaitDuration: kotlin.time.Duration? = null
        val cfg = RateLimiterConfig().apply {
            maxCalls = 1
            period = 50.milliseconds
            timeoutWhenLimited = 100.milliseconds
            onRateLimited = {
                callbackInvoked = true
            }
        }
        val customCallbackInvoked = mutableListOf<kotlin.time.Duration>()
        val rateLimiter = DefaultRateLimiter(
            config = cfg,
            onRateLimited = { waitDuration ->
                callbackWaitDuration = waitDuration
                customCallbackInvoked.add(waitDuration)
            }
        )
        rateLimiter.execute { "first" }

        // when
        val deferred = async {
            rateLimiter.execute { "second" }
        }
        advanceTimeBy(10.milliseconds.inWholeMilliseconds)
        advanceUntilIdle()

        // then
        assertTrue(callbackInvoked, "Config callback should be invoked")
        assertTrue(customCallbackInvoked.isNotEmpty(), "Custom callback should be invoked")
        assertTrue(callbackWaitDuration != null, "Custom callback should receive wait duration")
        
        // Complete the wait
        advanceTimeBy(50.milliseconds.inWholeMilliseconds)
        advanceUntilIdle()
        deferred.await()
    }

    @Test
    fun `given rate limiter with select when delay completes before timeout then executes successfully`() = runTest {
        // given
        val cfg = RateLimiterConfig().apply {
            maxCalls = 1
            period = 30.milliseconds
            timeoutWhenLimited = 100.milliseconds
        }
        val rateLimiter = DefaultRateLimiter(cfg)
        rateLimiter.execute { "first" }

        // when
        val deferred = async {
            rateLimiter.execute { "second" }
        }
        // Advance time so delay completes (30ms) but timeout hasn't (100ms)
        advanceTimeBy(30.milliseconds.inWholeMilliseconds)
        advanceUntilIdle()
        val result = deferred.await()

        // then
        assertEquals("second", result)
    }

    @Test
    @Ignore
    fun `given rate limiter with select when timeout expires before delay then throws exception`() = runTest {
        // given
        val testTimeSource = TestTimeSource()
        testTimeSource.setTime(1000)
        val cfg = RateLimiterConfig().apply {
            maxCalls = 1
            period = 100.milliseconds
            timeoutWhenLimited = 30.milliseconds
        }
        val rateLimiter = DefaultRateLimiter(cfg, timeSource = testTimeSource)
        rateLimiter.execute { "first" }

        // when & then - select timeout should win
        val deferred = async {
            rateLimiter.execute { "second" }
        }
        // Advance time so timeout expires (30ms) before delay completes (100ms)
        testTimeSource.advanceTimeBy(30.milliseconds.inWholeMilliseconds)
        advanceTimeBy(30.milliseconds.inWholeMilliseconds)
        advanceUntilIdle()

        // Exception should be thrown when awaiting
        try {
            deferred.await()
            assertTrue(false, "Should have thrown RateLimitExceededException")
        } catch (e: RateLimitExceededException) {
            assertTrue(e.retryAfter != null, "Exception should include retryAfter duration")
        }
    }

    @Test
    fun `given rate limiter when token is consumed after waiting then block executes successfully`() = runTest {
        // given
        val cfg = RateLimiterConfig().apply {
            maxCalls = 1
            period = 50.milliseconds
            timeoutWhenLimited = 100.milliseconds
        }
        val rateLimiter = DefaultRateLimiter(cfg)
        rateLimiter.execute { "first" }

        // when
        val deferred = async {
            rateLimiter.execute { "second-after-wait" }
        }
        // Advance time to allow token refill
        advanceTimeBy(50.milliseconds.inWholeMilliseconds)
        advanceUntilIdle()
        val result = deferred.await()

        // then
        assertEquals("second-after-wait", result)
    }

    @Test
    fun `given rate limiter when concurrent calls with timeout then select handles race correctly`() = runTest {
        // given - use TestTimeSource so refill time is deterministic (not real time)
        val testTimeSource = TestTimeSource()
        testTimeSource.setTime(0)
        val cfg = RateLimiterConfig().apply {
            maxCalls = 1
            period = 50.milliseconds
            timeoutWhenLimited = 30.milliseconds
        }
        val rateLimiter = DefaultRateLimiter(cfg, timeSource = testTimeSource)
        rateLimiter.execute { "first" }
        // Time still 0: token consumed, next refill in 50ms; timeout is 30ms so both waiters will timeout

        // when - start multiple concurrent calls
        val deferred1 = async {
            try {
                rateLimiter.execute { "second" }
            } catch (e: RateLimitExceededException) {
                "timeout-1"
            }
        }
        val deferred2 = async {
            try {
                rateLimiter.execute { "third" }
            } catch (e: RateLimitExceededException) {
                "timeout-2"
            }
        }
        
        // Advance virtual time - timeout wins (30ms < 50ms refill)
        advanceTimeBy(30.milliseconds.inWholeMilliseconds)
        advanceUntilIdle()

        // then - both should timeout
        val result1 = deferred1.await()
        val result2 = deferred2.await()
        assertEquals("timeout-1", result1)
        assertEquals("timeout-2", result2)
    }

    @Test
    fun `given rate limiter when delay completes exactly at timeout boundary then delay wins`() = runTest {
        // given
        val testTimeSource = TestTimeSource()
        testTimeSource.setTime(1000)
        val cfg = RateLimiterConfig().apply {
            maxCalls = 1
            period = 30.milliseconds
            timeoutWhenLimited = 30.milliseconds
        }
        val rateLimiter = DefaultRateLimiter(cfg, timeSource = testTimeSource)
        rateLimiter.execute { "first" }

        // when
        val deferred = async {
            rateLimiter.execute { "second" }
        }
        // Advance time so delay completes exactly at timeout (30ms = 30ms)
        testTimeSource.advanceTimeBy(30.milliseconds.inWholeMilliseconds)
        advanceTimeBy(30.milliseconds.inWholeMilliseconds)
        advanceUntilIdle()
        val result = deferred.await()

        // then - delay should win (completes first)
        assertEquals("second", result)
    }

    @Test
    fun `given rate limiter when execution is cancelled during wait then cancellation propagates`() = runTest {
        // given
        val cfg = RateLimiterConfig().apply {
            maxCalls = 1
            period = 100.milliseconds
            timeoutWhenLimited = 200.milliseconds
        }
        val rateLimiter = DefaultRateLimiter(cfg)
        rateLimiter.execute { "first" }

        // when - start waiting for token, then cancel
        val job = async {
            rateLimiter.execute { "second" }
        }
        delay(10.milliseconds) // Allow it to start waiting
        job.cancel()

        // then - cancellation should propagate
        assertFailsWith<CancellationException> {
            job.await()
        }
    }

    @Test
    fun `given rate limiter when execution is cancelled during delay then cancellation propagates`() = runTest {
        // given
        val cfg = RateLimiterConfig().apply {
            maxCalls = 1
            period = 100.milliseconds
            timeoutWhenLimited = 200.milliseconds
        }
        val rateLimiter = DefaultRateLimiter(cfg)
        rateLimiter.execute { "first" }

        // when - cancel during delay (inside select)
        val job = async {
            rateLimiter.execute { "second" }
        }
        delay(10.milliseconds) // Allow it to enter select and start delay
        job.cancel()

        // then - cancellation should propagate
        assertFailsWith<CancellationException> {
            job.await()
        }
    }

    @Test
    fun `given rate limiter when block execution is cancelled then cancellation propagates`() = runTest {
        // given
        val cfg = RateLimiterConfig().apply {
            maxCalls = 2
            period = 100.milliseconds
        }
        val rateLimiter = DefaultRateLimiter(cfg)

        // when - cancel during block execution
        val job = async {
            rateLimiter.execute {
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
    fun `given rate limiter when concurrent cancellations occur then all cancellations propagate`() = runTest {
        // given
        val cfg = RateLimiterConfig().apply {
            maxCalls = 1
            period = 100.milliseconds
            timeoutWhenLimited = 200.milliseconds
        }
        val rateLimiter = DefaultRateLimiter(cfg)
        rateLimiter.execute { "first" }

        // when - multiple concurrent calls that will wait, then cancel all
        val jobs = coroutineScope {
            (1..3).map { i ->
                async {
                    rateLimiter.execute { "result-$i" }
                }
            }
        }
        delay(10.milliseconds) // Allow them to start waiting
        jobs.forEach { it.cancel() }

        // then - all should be cancelled
        jobs.forEach { job ->
            assertFailsWith<CancellationException> {
                job.await()
            }
        }
    }

    @Test
    fun `given rate limiter when token refill occurs during concurrent waits then waiting calls proceed`() = runTest {
        // given
        val testTimeSource = TestTimeSource()
        testTimeSource.setTime(1000)
        val cfg = RateLimiterConfig().apply {
            maxCalls = 1
            period = 50.milliseconds
            timeoutWhenLimited = 200.milliseconds
        }
        val rateLimiter = DefaultRateLimiter(cfg, timeSource = testTimeSource)
        rateLimiter.execute { "first" }

        // when - multiple concurrent calls waiting for token
        val jobs = coroutineScope {
            (1..2).map { i ->
                async {
                    rateLimiter.execute { "result-$i" }
                }
            }
        }

        // Advance time to allow token refill
        testTimeSource.advanceTimeBy(50.milliseconds.inWholeMilliseconds)
        advanceTimeBy(50.milliseconds.inWholeMilliseconds)
        advanceUntilIdle()

        // then - at least one should succeed (token refilled)
        val results = jobs.mapNotNull { job ->
            runCatching { job.await() }.getOrNull()
        }
        assertTrue(results.isNotEmpty(), "At least one call should succeed after token refill")
    }
}
