package com.santimattius.resilient

import app.cash.turbine.test
import com.santimattius.resilient.bulkhead.BulkheadFullException
import com.santimattius.resilient.composition.ResilientScope
import com.santimattius.resilient.composition.resilient
import com.santimattius.resilient.fallback.FallbackConfig
import com.santimattius.resilient.telemetry.ResilientEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * Behavioral tests for the [ResilientEvent.BulkheadRejected] event.
 *
 * These tests verify that BulkheadRejected is emitted correctly in all scenarios,
 * including when a Fallback policy is configured (which previously caused the event to be lost).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BulkheadRejectedEventTest {

    /**
     * Regression: without Fallback, BulkheadRejected must still be emitted and
     * the exception must propagate to the caller.
     */
    @Test
    fun `given bulkhead full without fallback when rejected then BulkheadRejected event is emitted`() =
        runTest {
            val scope = ResilientScope()
            val policy = resilient(scope) {
                bulkhead {
                    maxConcurrentCalls = 1
                    maxWaitingCalls = 0
                }
            }

            policy.events.test {
                // Occupy the single permit so the next call is rejected immediately
                val holder = async { policy.execute { delay(10.seconds); "holding" } }
                runCurrent()

                assertFailsWith<BulkheadFullException> {
                    policy.execute { "rejected" }
                }

                // Expected sequence: BulkheadRejected, then OperationFailure
                val rejected = awaitItem()
                assertIs<ResilientEvent.BulkheadRejected>(rejected)

                val failure = awaitItem()
                assertIs<ResilientEvent.OperationFailure>(failure)
                assertIs<BulkheadFullException>(failure.error)

                holder.cancel()
                cancelAndIgnoreRemainingEvents()
            }
        }

    /**
     * Core fix: when Fallback is configured, BulkheadRejected must still be emitted.
     * Previously the event was emitted only in the outer catch block, which Fallback bypassed.
     */
    @Test
    fun `given bulkhead full with fallback when rejected then BulkheadRejected event is emitted`() =
        runTest {
            val scope = ResilientScope()
            val policy = resilient(scope) {
                bulkhead {
                    maxConcurrentCalls = 1
                    maxWaitingCalls = 0
                }
                fallback(FallbackConfig { "fallback-value" })
            }

            policy.events.test {
                val holder = async { policy.execute { delay(10.seconds); "holding" } }
                runCurrent()

                // Fallback catches the rejection — execute must not throw
                val result = policy.execute { "rejected" }
                assertEquals("fallback-value", result)

                // BulkheadRejected must be emitted even though Fallback swallowed the exception
                val rejected = awaitItem()
                assertIs<ResilientEvent.BulkheadRejected>(rejected)

                holder.cancel()
                cancelAndIgnoreRemainingEvents()
            }
        }

    /**
     * When Fallback is active, the full event sequence for a bulkhead rejection must be:
     * BulkheadRejected → FallbackTriggered → OperationSuccess (not OperationFailure).
     */
    @Test
    fun `given bulkhead full with fallback when rejected then emits BulkheadRejected FallbackTriggered OperationSuccess in order`() =
        runTest {
            val scope = ResilientScope()
            val policy = resilient(scope) {
                bulkhead {
                    maxConcurrentCalls = 1
                    maxWaitingCalls = 0
                }
                fallback(FallbackConfig { "fallback-value" })
            }

            policy.events.test {
                val holder = async { policy.execute { delay(10.seconds); "holding" } }
                runCurrent()

                val result = policy.execute { "rejected" }
                assertEquals("fallback-value", result)

                val event1 = awaitItem()
                assertIs<ResilientEvent.BulkheadRejected>(event1)

                val event2 = awaitItem()
                assertIs<ResilientEvent.FallbackTriggered>(event2)
                assertIs<BulkheadFullException>(event2.error)

                val event3 = awaitItem()
                assertIs<ResilientEvent.OperationSuccess>(event3)

                holder.cancel()
                cancelAndIgnoreRemainingEvents()
            }
        }

    /**
     * Verifies that two consecutive rejections each emit their own BulkheadRejected event.
     */
    @Test
    fun `given bulkhead full without fallback when rejected twice then two BulkheadRejected events are emitted`() =
        runTest {
            val scope = ResilientScope()
            val policy = resilient(scope) {
                bulkhead {
                    maxConcurrentCalls = 1
                    maxWaitingCalls = 0
                }
            }

            policy.events.test {
                val holder = async { policy.execute { delay(10.seconds); "holding" } }
                runCurrent()

                repeat(2) {
                    assertFailsWith<BulkheadFullException> { policy.execute { "rejected" } }
                }

                var rejectedCount = 0
                var failureCount = 0
                // Consume all events: 2x (BulkheadRejected + OperationFailure)
                repeat(4) {
                    when (awaitItem()) {
                        is ResilientEvent.BulkheadRejected -> rejectedCount++
                        is ResilientEvent.OperationFailure -> failureCount++
                        else -> {}
                    }
                }
                assertEquals(2, rejectedCount)
                assertEquals(2, failureCount)

                holder.cancel()
                cancelAndIgnoreRemainingEvents()
            }
        }
}
