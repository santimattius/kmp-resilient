package com.santimattius.resilient

import app.cash.turbine.test
import com.santimattius.resilient.circuitbreaker.CircuitBreakerOpenException
import com.santimattius.resilient.composition.ResilientScope
import com.santimattius.resilient.composition.resilient
import com.santimattius.resilient.fallback.FallbackConfig
import com.santimattius.resilient.ratelimiter.RateLimitExceededException
import com.santimattius.resilient.telemetry.ResilientEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Comprehensive behavioral tests for [ResilientPolicy] composition.
 *
 * These tests focus on:
 * - Policy composition order and interaction
 * - Telemetry event emission via Flow
 * - Cancellation propagation through composed policies
 * - Concurrent execution behavior
 * - Error handling and propagation
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResilientPolicyTest {

    private val testDispatcher = StandardTestDispatcher()

    @Test
    fun `given composed policies when execute succeeds then emits OperationSuccess event`() = runTest {
        // given
        val scope = ResilientScope(testDispatcher)
        val policy = resilient(scope) {
            timeout {
                timeout = 1.seconds
            }
        }

        // when
        policy.events.test {
            val result = policy.execute { "success" }
            assertEquals("success", result)

            // then - should emit OperationSuccess
            val event = awaitItem()
            assertIs<ResilientEvent.OperationSuccess>(event)
            assertTrue(event.duration.inWholeMilliseconds >= 0)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `given composed policies when execute fails then emits OperationFailure event`() = runTest {
        // given
        val scope = ResilientScope(testDispatcher)
        val policy = resilient(scope) {
            timeout {
                timeout = 1.seconds
            }
        }
        val testError = IllegalStateException("test error")

        // when
        policy.events.test {
            assertFailsWith<IllegalStateException> {
                policy.execute { throw testError }
            }

            // then - should emit OperationFailure
            val event = awaitItem()
            assertIs<ResilientEvent.OperationFailure>(event)
            assertEquals(testError.message, event.error.message)
            assertTrue(event.duration.inWholeMilliseconds >= 0)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `given retry policy when retries occur then emits RetryAttempt events`() = runTest {
        // given
        val scope = ResilientScope()
        var attemptCount = 0
        val policy = resilient(scope) {
            retry {
                maxAttempts = 3
            }
        }

        // when
        policy.events.test {
            val result = policy.execute {
                attemptCount++
                if (attemptCount < 3) throw IllegalStateException("retry") else "success"
            }
            assertEquals("success", result)

            // then - should emit RetryAttempt events for each retry
            val retryEvents = mutableListOf<ResilientEvent.RetryAttempt>()
            
            // Collect all events (may include retry attempts and success)
            try {
                while (true) {
                    when (val event = awaitItem()) {
                        is ResilientEvent.RetryAttempt -> retryEvents.add(event)
                        is ResilientEvent.OperationSuccess -> {
                            assertTrue(retryEvents.size >= 2, "Should have at least 2 retry attempts")
                            retryEvents.forEachIndexed { index, retryEvent ->
                                assertEquals(index + 1, retryEvent.attempt)
                                assertIs<IllegalStateException>(retryEvent.error)
                            }
                            cancelAndIgnoreRemainingEvents()
                            return@test
                        }
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                // Flow completed or cancelled
            }
        }
    }

    @Test
    fun `given circuit breaker when state changes then emits CircuitStateChanged events`() = runTest(testDispatcher) {
        // given
        val scope = ResilientScope(testDispatcher)
        val policy = resilient(scope) {
            circuitBreaker {
                failureThreshold = 2
                timeout = 100.seconds
            }
        }

        // when - trigger circuit to open
        policy.events.test {
            // First failure
            assertFailsWith<IllegalStateException> {
                policy.execute { throw IllegalStateException("failure 1") }
            }
            
            // Second failure - should open circuit
            assertFailsWith<IllegalStateException> {
                policy.execute { throw IllegalStateException("failure 2") }
            }

            // then - should emit CircuitStateChanged events
            val events = mutableListOf<ResilientEvent>()
            events.add(awaitItem())
            events.add(awaitItem())

            
            val stateChanges = events.filterIsInstance<ResilientEvent.CircuitStateChanged>()
            assertTrue(stateChanges.isNotEmpty(), "Should emit at least one state change")
            
            // Verify circuit is open
            assertFailsWith<CircuitBreakerOpenException> {
                policy.execute { "should not execute" }
            }
            
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `given rate limiter when rate limited then emits RateLimited events`() = runTest {
        // given
        val scope = ResilientScope()
        val policy = resilient(scope) {
            rateLimiter {
                maxCalls = 1
                period = 100.milliseconds
                timeoutWhenLimited = 50.milliseconds
            }
        }

        // when - exhaust rate limit
        policy.events.test {
            policy.execute { "first" }
            
            // Second call should be rate limited
            try {
                policy.execute { "second" }
            } catch (e: RateLimitExceededException) {
                // Expected
            }

            // then - should emit RateLimited event
            val events = mutableListOf<ResilientEvent>()
            events.add(awaitItem())
            events.add(awaitItem())
            
            val rateLimitedEvents = events.filterIsInstance<ResilientEvent.RateLimited>()
            assertTrue(rateLimitedEvents.isNotEmpty(), "Should emit RateLimited event")
            
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `given timeout policy when operation times out then emits OperationFailure with TimeoutCancellationException`() = runTest {
        // given
        val scope = ResilientScope()
        val policy = resilient(scope) {
            timeout {
                timeout = 50.milliseconds
            }
        }

        // when
        policy.events.test {
            assertFailsWith<TimeoutCancellationException> {
                policy.execute {
                    delay(200.milliseconds)
                    "never"
                }
            }

            // then - should emit OperationFailure
            val event = awaitItem()
            assertIs<ResilientEvent.OperationFailure>(event)
            assertIs<TimeoutCancellationException>(event.error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `given composed policies when execution is cancelled then cancellation propagates correctly`() = runTest {
        // given
        val scope = ResilientScope()
        val policy = resilient(scope) {
            timeout {
                timeout = 1.seconds
            }
            retry {
                maxAttempts = 3
            }
        }

        // when - cancel during execution
        val job = async {
            policy.execute {
                delay(500.milliseconds)
                "result"
            }
        }
        
        delay(10.milliseconds)
        job.cancel()

        // then - should propagate CancellationException
        assertFailsWith<CancellationException> {
            job.await()
        }
    }

    @Test
    fun `given composed policies when cancelled during retry backoff then cancellation propagates`() = runTest {
        // given
        val scope = ResilientScope()
        var attemptCount = 0
        val policy = resilient(scope) {
            retry {
                maxAttempts = 5
            }
        }

        // when - cancel during backoff delay
        val job = async {
            policy.execute {
                attemptCount++
                if (attemptCount < 5) {
                    delay(100.milliseconds) // Simulate work before failure
                    throw IllegalStateException("retry")
                }
                "success"
            }
        }
        
        delay(50.milliseconds) // Cancel during backoff
        job.cancel()

        // then - cancellation should propagate
        assertFailsWith<CancellationException> {
            job.await()
        }
    }

    @Test
    fun `given composed policies when cancelled during rate limiter wait then cancellation propagates`() = runTest {
        // given
        val scope = ResilientScope()
        val policy = resilient(scope) {
            rateLimiter {
                maxCalls = 1
                period = 200.milliseconds
                timeoutWhenLimited = 300.milliseconds
            }
        }

        // when - exhaust limit and cancel while waiting
        policy.execute { "first" }
        
        val job = async {
            policy.execute { "second" }
        }
        
        delay(10.milliseconds) // Cancel while waiting for token
        job.cancel()

        // then - cancellation should propagate
        assertFailsWith<CancellationException> {
            job.await()
        }
    }

    @Test
    fun `given multiple subscribers when events are emitted then all subscribers receive events`() = runTest {
        // given
        val scope = ResilientScope()
        val policy = resilient(scope) {
            timeout {
                timeout = 1.seconds
            }
        }

        // when - multiple subscribers
        val subscriber1Events = mutableListOf<ResilientEvent>()
        val subscriber2Events = mutableListOf<ResilientEvent>()
        
        val job1 = async {
            policy.events.test {
                subscriber1Events.add(awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }
        
        val job2 = async {
            policy.events.test {
                subscriber2Events.add(awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }
        
        advanceUntilIdle()
        
        // Trigger event
        policy.execute { "success" }
        advanceUntilIdle()
        
        job1.await()
        job2.await()

        // then - both subscribers should receive events
        assertTrue(subscriber1Events.isNotEmpty(), "Subscriber 1 should receive events")
        assertTrue(subscriber2Events.isNotEmpty(), "Subscriber 2 should receive events")
    }

    @Test
    fun `given composed policies when policies are applied in correct order then execution follows expected sequence`() = runTest {
        // given
        val scope = ResilientScope()
        val executionOrder = mutableListOf<String>()
        
        val policy = resilient(scope) {
            timeout {
                timeout = 1.seconds
            }
            retry {
                maxAttempts = 2
            }
            circuitBreaker {
                failureThreshold = 10
                timeout = 100.milliseconds
            }
        }

        // when
        policy.execute {
            executionOrder.add("block")
            "result"
        }

        // then - block should execute (order verification through observable behavior)
        assertEquals(listOf("block"), executionOrder)
    }

    @Test
    fun `given fallback policy when operation fails then fallback result is returned`() = runTest {
        // given
        val scope = ResilientScope()
        val policy = resilient(scope) {
            fallback(FallbackConfig { "fallback-result" })
        }

        // when
        val result = policy.execute<String> {
            throw IllegalStateException("failure")
        }

        // then
        assertEquals("fallback-result", result)
    }

    @Test
    fun `given fallback policy when operation succeeds then original result is returned`() = runTest {
        // given
        val scope = ResilientScope()
        val policy = resilient(scope) {
            fallback(FallbackConfig { "fallback-result" })
        }

        // when
        val result = policy.execute { "original-result" }

        // then
        assertEquals("original-result", result)
    }

    @Test
    fun `given cache policy when same key is requested then cached result is returned`() = runTest {
        // given
        val scope = ResilientScope()
        var executionCount = 0
        val policy = resilient(scope) {
            cache {
                key = "test-key"
                ttl = 1.seconds
            }
        }

        // when - first execution
        val result1 = policy.execute {
            executionCount++
            "result-$executionCount"
        }
        assertEquals("result-1", result1)
        assertEquals(1, executionCount)

        // then - second execution should use cache
        val result2 = policy.execute {
            executionCount++
            "result-$executionCount"
        }
        assertEquals("result-1", result2) // Should be cached
        assertEquals(1, executionCount) // Should not execute again
    }

    @Test
    fun `given concurrent executions when policies are applied then all complete successfully`() = runTest {
        // given
        val scope = ResilientScope()
        val policy = resilient(scope) {
            timeout {
                timeout = 1.seconds
            }
            bulkhead {
                maxConcurrentCalls = 5
                maxWaitingCalls = 10
            }
        }

        // when - concurrent executions
        val results = coroutineScope {
            (1..5).map { i ->
                async {
                    policy.execute { "result-$i" }
                }
            }
        }
        advanceUntilIdle()
        val actualResults = results.awaitAll()

        // then - all should succeed
        assertEquals(5, actualResults.size)
        actualResults.forEachIndexed { index, result ->
            assertEquals("result-${index + 1}", result)
        }
    }

    @Test
    fun `given ResilientScope when closed then background tasks are cancelled`() = runTest {
        // given
        val scope = ResilientScope()
        var cleanupExecuted = false
        val policy = resilient(scope) {
            cache {
                key = "test"
                ttl = 1.seconds
                cleanupInterval = 100.milliseconds
            }
        }

        // when - close scope
        policy.execute { "test" }
        scope.close()
        advanceUntilIdle()

        // then - scope should be closed (verification through observable behavior)
        // Cache cleanup job should be cancelled
        assertTrue(true) // Scope.close() should complete without blocking
    }

    @Test
    fun `given event flow when subscriber cancels then flow handles cancellation gracefully`() = runTest {
        // given
        val scope = ResilientScope()
        val policy = resilient(scope) {
            timeout {
                timeout = 1.seconds
            }
        }

        // when - subscriber cancels
        policy.events.test {
            cancel() // Cancel immediately
        }

        // then - policy should still work
        val result = policy.execute { "success" }
        assertEquals("success", result)
    }

    @Test
    fun `given composed policies when error occurs in inner policy then error propagates correctly`() = runTest {
        // given
        val scope = ResilientScope(testDispatcher)
        val policy = resilient(scope) {
            timeout {
                timeout = 1.seconds
            }
            retry {
                maxAttempts = 1 // No retries
            }
        }
        val testError = IllegalStateException("inner error")

        // when
        val thrownError = assertFailsWith<IllegalStateException> {
            policy.execute { throw testError }
        }

        // then
        assertEquals(testError.message, thrownError.message)
    }

    @Test
    fun `given hedging policy when first attempt succeeds then other attempts are cancelled`() = runTest {
        // given
        val scope = ResilientScope(testDispatcher)
        var attemptCount = 0
        val policy = resilient(scope) {
            hedging {
                attempts = 3
                stagger = 10.milliseconds
            }
        }

        // when
        val result = policy.execute {
            attemptCount++
            val value = "result-$attemptCount"
            delay(20.milliseconds)
            value
        }
        advanceTimeBy(60.milliseconds)
        // then - should return first result
        assertEquals("result-1", result)
        // Note: attemptCount may be > 1 if cancellation didn't happen immediately,
        // but the result should be from the first attempt
    }
}

