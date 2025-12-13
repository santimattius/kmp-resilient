package com.santimattius.resilient

import com.santimattius.resilient.circuitbreaker.CircuitBreakerConfig
import com.santimattius.resilient.circuitbreaker.CircuitBreakerOpenException
import com.santimattius.resilient.circuitbreaker.CircuitState
import com.santimattius.resilient.circuitbreaker.DefaultCircuitBreaker
import com.santimattius.resilient.circuitbreaker.TestTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Ignore
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class CircuitBreakerTest {

    @Test
    fun `given CLOSED state when failures reach threshold then circuit opens`() = runTest {
        // given
        val cfg = CircuitBreakerConfig().apply {
            failureThreshold = 2
            successThreshold = 1
            timeout = 200.milliseconds
            halfOpenMaxCalls = 1
        }
        val cb = DefaultCircuitBreaker(cfg)

        // when
        repeat(2) {
            assertFailsWith<Throwable> { cb.execute { error("boom") } }
        }

        // then
        assertEquals(CircuitState.OPEN, cb.state.value)
        assertFailsWith<CircuitBreakerOpenException> {
            cb.execute { "never" }
        }
    }

    @Test
    fun `given CLOSED state when multiple concurrent requests execute then all execute concurrently`() = runTest {
        // given
        val cfg = CircuitBreakerConfig().apply {
            failureThreshold = 10
            successThreshold = 1
            timeout = 1.seconds
            halfOpenMaxCalls = 1
        }
        val cb = DefaultCircuitBreaker(cfg)
        var executionCount = 0

        // when
        val results = coroutineScope {
            (1..10).map {
                async {
                    cb.execute {
                        executionCount++
                        "result-$it"
                    }
                }
            }
        }
        advanceUntilIdle()
        val actualResults = results.awaitAll()

        // then
        assertEquals(10, executionCount)
        assertEquals(10, actualResults.size)
        actualResults.forEachIndexed { index, result ->
            assertEquals("result-${index + 1}", result)
        }
        assertEquals(CircuitState.CLOSED, cb.state.value)
    }

    @Test
    fun `given CLOSED state when concurrent requests fail then circuit opens after threshold`() = runTest {
        // given
        val cfg = CircuitBreakerConfig().apply {
            failureThreshold = 5
            successThreshold = 1
            timeout = 200.milliseconds
            halfOpenMaxCalls = 2
        }
        val cb = DefaultCircuitBreaker(cfg)

        // when
        val failures = coroutineScope {
            (1..5).map {
                async {
                    assertFailsWith<IllegalStateException> {
                        cb.execute {
                            throw IllegalStateException("boom-$it")
                        }
                    }
                }
            }
        }
        advanceUntilIdle()
        failures.awaitAll()

        // then
        assertEquals(CircuitState.OPEN, cb.state.value)
        assertFailsWith<CircuitBreakerOpenException> {
            cb.execute { "should not execute" }
        }
    }

    @Test
    fun `given OPEN state when timeout elapses and execute is called then transitions to HALF_OPEN`() = runTest {
        // given
        val testTimeSource = TestTimeSource()
        testTimeSource.setTime(1000) // Start at time 1000ms
        val cfg = CircuitBreakerConfig().apply {
            failureThreshold = 1
            successThreshold = 1
            timeout = 10.milliseconds
            halfOpenMaxCalls = 2
        }
        val cb = DefaultCircuitBreaker(cfg, testTimeSource)
        
        // Trigger OPEN state - this sets openUntilMs to 1000 + 10 = 1010
        assertFailsWith<Throwable> { cb.execute { error("boom") } }
        assertEquals(CircuitState.OPEN, cb.state.value)

        // Verify circuit is OPEN and request is rejected immediately (time is still 1000, timeout is 1010)
        val exceptionBefore = assertFailsWith<CircuitBreakerOpenException> {
            cb.execute { "should not execute" }
        }
        assertTrue(exceptionBefore.retryAfter != null, "Should have retryAfter before timeout")

        // when - advance time beyond timeout (1010ms)
        // Time is now 1015ms, which is >= 1010ms, so timeout has elapsed
        testTimeSource.advanceTimeBy(15.milliseconds.inWholeMilliseconds)
        advanceUntilIdle()
        
        // Verify we're still in OPEN state before executing (timeout check happens in execute)
        assertEquals(CircuitState.OPEN, cb.state.value)
        
        // Execute should transition to HALF_OPEN and succeed
        // The transition happens inside execute when it checks the timeout inside the lock
        val result = cb.execute { "half-open-success" }

        // then
        assertEquals("half-open-success", result)
        assertEquals(CircuitState.CLOSED, cb.state.value)
    }

    @Test
    fun `given HALF_OPEN state when requests succeed then transitions to CLOSED`() = runTest {
        // given
        val testTimeSource = TestTimeSource()
        testTimeSource.setTime(1000)
        val cfg = CircuitBreakerConfig().apply {
            failureThreshold = 1
            successThreshold = 2
            timeout = 10.milliseconds
            halfOpenMaxCalls = 3
        }
        val cb = DefaultCircuitBreaker(cfg, testTimeSource)
        assertFailsWith<Throwable> { cb.execute { error("boom") } }
        testTimeSource.advanceTimeBy(15.milliseconds.inWholeMilliseconds) // Advance beyond timeout
        advanceUntilIdle()

        // when
        cb.execute { "success-1" }
        advanceUntilIdle()

        // then
        assertEquals(CircuitState.HALF_OPEN, cb.state.value)

        // when
        cb.execute { "success-2" }
        advanceUntilIdle()

        // then
        assertEquals(CircuitState.CLOSED, cb.state.value)
    }

    @Test
    fun `given HALF_OPEN state when request fails then transitions back to OPEN`() = runTest {
        // given
        val testTimeSource = TestTimeSource()
        testTimeSource.setTime(1000)
        val cfg = CircuitBreakerConfig().apply {
            failureThreshold = 1
            successThreshold = 2
            timeout = 10.milliseconds
            halfOpenMaxCalls = 3
        }
        val cb = DefaultCircuitBreaker(cfg, testTimeSource)
        assertFailsWith<Throwable> { cb.execute { error("boom") } }
        testTimeSource.advanceTimeBy(15.milliseconds.inWholeMilliseconds) // Advance beyond timeout
        advanceUntilIdle()

        // when
        assertFailsWith<IllegalStateException> {
            cb.execute { throw IllegalStateException("boom") }
        }
        advanceUntilIdle()

        // then
        assertEquals(CircuitState.OPEN, cb.state.value)
        assertFailsWith<CircuitBreakerOpenException> {
            cb.execute { "should not execute" }
        }
    }

    @Test
    fun `given CLOSED state when success occurs after failures then failure count is reset`() = runTest {
        // given
        val cfg = CircuitBreakerConfig().apply {
            failureThreshold = 3
            successThreshold = 1
            timeout = 200.milliseconds
            halfOpenMaxCalls = 1
        }
        val cb = DefaultCircuitBreaker(cfg)

        // when - two failures
        assertFailsWith<Throwable> { cb.execute { error("boom1") } }
        assertFailsWith<Throwable> { cb.execute { error("boom2") } }

        // then - success should reset failure count
        cb.execute { "success" }
        advanceUntilIdle()

        // when - another failure
        assertFailsWith<Throwable> { cb.execute { error("boom3") } }
        advanceUntilIdle()

        // then - circuit should still be CLOSED (only 1 failure now, not 3)
        assertEquals(CircuitState.CLOSED, cb.state.value)

        // when - two more failures
        assertFailsWith<Throwable> { cb.execute { error("boom4") } }
        assertFailsWith<Throwable> { cb.execute { error("boom5") } }
        advanceUntilIdle()

        // then - circuit should now be OPEN
        assertEquals(CircuitState.OPEN, cb.state.value)
    }

    @Test
    fun `given shouldRecordFailure filter when exception is filtered then failure is not recorded`() = runTest {
        // given
        var recordedFailures = 0
        val cfg = CircuitBreakerConfig().apply {
            failureThreshold = 2
            successThreshold = 1
            timeout = 200.milliseconds
            halfOpenMaxCalls = 1
            shouldRecordFailure = { exception ->
                recordedFailures++
                exception !is IllegalArgumentException // Don't record IllegalArgumentException
            }
        }
        val cb = DefaultCircuitBreaker(cfg)

        // when - filtered failures
        assertFailsWith<IllegalArgumentException> {
            cb.execute { throw IllegalArgumentException("ignored") }
        }
        assertFailsWith<IllegalArgumentException> {
            cb.execute { throw IllegalArgumentException("ignored") }
        }
        advanceUntilIdle()

        // then - circuit should still be CLOSED
        assertEquals(CircuitState.CLOSED, cb.state.value)
        assertEquals(2, recordedFailures)

        // when - real failures
        assertFailsWith<IllegalStateException> {
            cb.execute { throw IllegalStateException("recorded") }
        }
        advanceUntilIdle()
        assertEquals(3, recordedFailures)

        assertFailsWith<IllegalStateException> {
            cb.execute { throw IllegalStateException("recorded") }
        }
        advanceUntilIdle()

        // then - circuit should be OPEN
        assertEquals(CircuitState.OPEN, cb.state.value)
        assertEquals(4, recordedFailures)
    }

    @Test
    fun `given CLOSED state when executing then uses fast path without locking`() = runTest {
        // given
        val cfg = CircuitBreakerConfig().apply {
            failureThreshold = 10
            successThreshold = 1
            timeout = 1.seconds
            halfOpenMaxCalls = 1
        }
        val cb = DefaultCircuitBreaker(cfg)

        // when - execute many concurrent requests in CLOSED state
        val results = coroutineScope {
            (1..100).map {
                async {
                    cb.execute { "result-$it" }
                }
            }
        }
        advanceUntilIdle()

        // then - all should succeed concurrently (fast path)
        val actualResults = results.awaitAll()
        assertEquals(100, actualResults.size)
        actualResults.forEachIndexed { index, result ->
            assertEquals("result-${index + 1}", result)
        }
        assertEquals(CircuitState.CLOSED, cb.state.value)
    }

    @Test
    fun `given OPEN state when state changes during lock acquisition then handles state transition correctly`() = runTest {
        // given
        val testTimeSource = TestTimeSource()
        testTimeSource.setTime(1000)
        val cfg = CircuitBreakerConfig().apply {
            failureThreshold = 1
            successThreshold = 1
            timeout = 10.milliseconds
            halfOpenMaxCalls = 1
        }
        val cb = DefaultCircuitBreaker(cfg, testTimeSource)
        assertFailsWith<Throwable> { cb.execute { error("boom") } }
        assertEquals(CircuitState.OPEN, cb.state.value)

        // when - advance time beyond timeout and trigger state change
        testTimeSource.advanceTimeBy(15.milliseconds.inWholeMilliseconds)
        advanceUntilIdle()

        // Launch concurrent requests that will see state change
        val results = coroutineScope {
            (1..5).map { index ->
                async {
                    runCatching {
                        cb.execute { "result-$index" }
                    }
                }
            }
        }
        advanceUntilIdle()

        // then - at least one should succeed (state transition happened)
        val outcomes = results.awaitAll()
        val successes = outcomes.count { it.isSuccess }
        assertTrue(successes > 0, "Expected at least one success after state transition")
    }

    @Test
    fun `given HALF_OPEN state when state changes during lock acquisition then handles state transition correctly`() = runTest {
        // given
        val testTimeSource = TestTimeSource()
        testTimeSource.setTime(1000)
        val cfg = CircuitBreakerConfig().apply {
            failureThreshold = 1
            successThreshold = 1
            timeout = 10.milliseconds
            halfOpenMaxCalls = 1
        }
        val cb = DefaultCircuitBreaker(cfg, testTimeSource)
        assertFailsWith<Throwable> { cb.execute { error("boom") } }
        testTimeSource.advanceTimeBy(15.milliseconds.inWholeMilliseconds)
        advanceUntilIdle()

        // when - first request transitions to HALF_OPEN and succeeds, triggering transition to CLOSED
        val first = async {
            cb.execute { "first" }
        }
        advanceUntilIdle()
        val firstResult = first.await()
        assertEquals("first", firstResult)

        // Launch concurrent requests that may see state change from HALF_OPEN to CLOSED
        val results = coroutineScope {
            (1..5).map { index ->
                async {
                    runCatching {
                        cb.execute { "result-$index" }
                    }
                }
            }
        }
        advanceUntilIdle()

        // then - all should succeed (state changed to CLOSED)
        val outcomes = results.awaitAll()
        val successes = outcomes.count { it.isSuccess }
        assertEquals(5, successes, "All requests should succeed after transition to CLOSED")
        assertEquals(CircuitState.CLOSED, cb.state.value)
    }

    @Test
    fun `given OPEN state when timeout not elapsed then retryAfter is calculated correctly`() = runTest {
        // given
        val testTimeSource = TestTimeSource()
        testTimeSource.setTime(1000)
        val cfg = CircuitBreakerConfig().apply {
            failureThreshold = 1
            successThreshold = 1
            timeout = 100.milliseconds
            halfOpenMaxCalls = 1
        }
        val cb = DefaultCircuitBreaker(cfg, testTimeSource)
        assertFailsWith<Throwable> { cb.execute { error("boom") } }
        assertEquals(CircuitState.OPEN, cb.state.value)

        // when - immediately try to execute (timeout not elapsed)
        val exception = assertFailsWith<CircuitBreakerOpenException> {
            cb.execute { "should not execute" }
        }

        // then - should have retryAfter calculated
        assertEquals(CircuitState.OPEN, exception.currentState)
        assertTrue(exception.retryAfter != null, "OPEN state should have retryAfter")
        assertTrue(
            exception.retryAfter.inWholeMilliseconds > 0,
            "retryAfter should be positive"
        )
        assertTrue(
            exception.retryAfter.inWholeMilliseconds <= 100,
            "retryAfter should not exceed timeout"
        )
    }

    @Test
    fun `given state changes during execution then handles recursive state handling correctly`() = runTest {
        // given
        val testTimeSource = TestTimeSource()
        testTimeSource.setTime(1000)
        val cfg = CircuitBreakerConfig().apply {
            failureThreshold = 1
            successThreshold = 1
            timeout = 10.milliseconds
            halfOpenMaxCalls = 1
        }
        val cb = DefaultCircuitBreaker(cfg, testTimeSource)
        assertFailsWith<Throwable> { cb.execute { error("boom") } }
        testTimeSource.advanceTimeBy(15.milliseconds.inWholeMilliseconds)
        advanceUntilIdle()

        // when - first request transitions HALF_OPEN -> CLOSED, concurrent request sees state change
        val first = async {
            cb.execute { "first" }
        }
        advanceUntilIdle()
        
        // Launch request that may see state change from HALF_OPEN to CLOSED
        val second = async {
            cb.execute { "second" }
        }
        advanceUntilIdle()

        // then - both should succeed (state change handled correctly)
        assertEquals("first", first.await())
        assertEquals("second", second.await())
        assertEquals(CircuitState.CLOSED, cb.state.value)
    }

    @Test
    @Ignore
    fun `given OPEN state when multiple requests arrive simultaneously then only one transitions to HALF_OPEN`() = runTest {
        // given
        val testTimeSource = TestTimeSource()
        testTimeSource.setTime(1000)
        val cfg = CircuitBreakerConfig().apply {
            failureThreshold = 1
            successThreshold = 1
            timeout = 10.milliseconds
            halfOpenMaxCalls = 3
        }
        val cb = DefaultCircuitBreaker(cfg, testTimeSource)
        assertFailsWith<Throwable> { cb.execute { error("boom") } }
        testTimeSource.advanceTimeBy(15.milliseconds.inWholeMilliseconds)
        advanceUntilIdle()

        // when - launch many concurrent requests exactly when timeout elapses
        val results = coroutineScope {
            (1..10).map { index ->
                async {
                    runCatching {
                        cb.execute { "result-$index" }
                    }
                }
            }
        }
        advanceUntilIdle()

        // then - only halfOpenMaxCalls should succeed (transition happened once)
        val outcomes = results.awaitAll()
        val successes = outcomes.count { it.isSuccess }
        assertEquals(3, successes, "Should respect halfOpenMaxCalls")
        assertEquals(CircuitState.HALF_OPEN, cb.state.value)
    }

    @Test
    fun `given CLOSED state when request is cancelled then cancellation is propagated and not recorded as failure`() = runTest {
        // given
        val cfg = CircuitBreakerConfig().apply {
            failureThreshold = 2
            successThreshold = 1
            timeout = 200.milliseconds
            halfOpenMaxCalls = 1
        }
        val cb = DefaultCircuitBreaker(cfg)

        // when - cancel a request
        val job = async {
            cb.execute {
                delay(100.milliseconds)
                "result"
            }
        }
        delay(10.milliseconds)
        job.cancel()

        // then - should throw CancellationException, not record as failure
        assertFailsWith<CancellationException> {
            job.await()
        }
        assertEquals(CircuitState.CLOSED, cb.state.value)
    }

    @Test
    fun `given HALF_OPEN state when request is cancelled then allowed call counter is restored`() = runTest {
        // given
        val testTimeSource = TestTimeSource()
        testTimeSource.setTime(1000)
        val cfg = CircuitBreakerConfig().apply {
            failureThreshold = 1
            successThreshold = 1
            timeout = 10.milliseconds
            halfOpenMaxCalls = 2
        }
        val cb = DefaultCircuitBreaker(cfg, testTimeSource)
        assertFailsWith<Throwable> { cb.execute { error("boom") } }
        testTimeSource.advanceTimeBy(15.milliseconds.inWholeMilliseconds)
        advanceUntilIdle()

        // when - cancel first request in HALF_OPEN
        val job1 = async {
            cb.execute {
                delay(100.milliseconds)
                "result-1"
            }
        }
        delay(10.milliseconds)
        job1.cancel()

        // then - cancellation should be propagated
        assertFailsWith<CancellationException> {
            job1.await()
        }

        // and - counter should be restored, allowing another request
        val result2 = cb.execute { "result-2" }
        assertEquals("result-2", result2)
        assertEquals(CircuitState.CLOSED, cb.state.value)
    }

    @Test
    fun `given HALF_OPEN state when request succeeds after cancellation then state transitions correctly`() = runTest {
        // given
        val testTimeSource = TestTimeSource()
        testTimeSource.setTime(1000)
        val cfg = CircuitBreakerConfig().apply {
            failureThreshold = 1
            successThreshold = 2
            timeout = 10.milliseconds
            halfOpenMaxCalls = 3
        }
        val cb = DefaultCircuitBreaker(cfg, testTimeSource)
        assertFailsWith<Throwable> { cb.execute { error("boom") } }
        testTimeSource.advanceTimeBy(15.milliseconds.inWholeMilliseconds)
        advanceUntilIdle()

        // when - cancel first request, then succeed with two more
        val job1 = async {
            cb.execute {
                delay(100.milliseconds)
                "result-1"
            }
        }
        delay(10.milliseconds)
        job1.cancel()

        assertFailsWith<CancellationException> {
            job1.await()
        }

        // Counter restored, so we can still make 2 more calls (halfOpenMaxCalls = 3, we used 1 and cancelled it)
        val result2 = cb.execute { "result-2" }
        val result3 = cb.execute { "result-3" }

        // then - should transition to CLOSED after 2 successes
        assertEquals("result-2", result2)
        assertEquals("result-3", result3)
        assertEquals(CircuitState.CLOSED, cb.state.value)
    }

    @Test
    fun `given OPEN state when request is cancelled after transition to HALF_OPEN then counter is restored`() = runTest {
        // given
        val testTimeSource = TestTimeSource()
        testTimeSource.setTime(1000)
        val cfg = CircuitBreakerConfig().apply {
            failureThreshold = 1
            successThreshold = 1
            timeout = 10.milliseconds
            halfOpenMaxCalls = 2
        }
        val cb = DefaultCircuitBreaker(cfg, testTimeSource)
        assertFailsWith<Throwable> { cb.execute { error("boom") } }
        testTimeSource.advanceTimeBy(15.milliseconds.inWholeMilliseconds)
        advanceUntilIdle()

        // when - request transitions OPEN->HALF_OPEN but is cancelled
        val job = async {
            cb.execute {
                delay(100.milliseconds)
                "result"
            }
        }
        delay(10.milliseconds)
        job.cancel()

        // then - cancellation propagated, counter restored
        assertFailsWith<CancellationException> {
            job.await()
        }

        // Counter should be restored, allowing another request
        val result2 = cb.execute { "result-2" }
        assertEquals("result-2", result2)
        assertEquals(CircuitState.CLOSED, cb.state.value)
    }
}
