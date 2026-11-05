package com.santimattius.resilient.composition

import com.santimattius.resilient.ratelimiter.RateLimitExceededException
import com.santimattius.resilient.ratelimiter.RateLimiterRegistry
import com.santimattius.resilient.telemetry.ResilientEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ResilientEngineEventsTest {

    // More executions than the event buffer can hold (extraBufferCapacity = 10).
    private val saturatingExecutions = 30

    private fun TestScope.attachStuckCollector(events: SharedFlow<ResilientEvent>) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            events.collect { awaitCancellation() }
        }
    }

    @Test
    fun `given a stuck events collector when executions succeed then results are still returned`() =
        runTest {
            val policy = resilient(ResilientScope()) {}
            attachStuckCollector(policy.events)

            withTimeout(5.seconds) {
                repeat(saturatingExecutions) { index ->
                    assertEquals(index, policy.execute { index })
                }
            }
        }

    @Test
    fun `given a stuck events collector when executions fail then the original exception is thrown`() =
        runTest {
            val policy = resilient(ResilientScope()) {}
            attachStuckCollector(policy.events)

            withTimeout(5.seconds) {
                repeat(saturatingExecutions) {
                    val failure = assertFailsWith<IllegalStateException> {
                        policy.execute<Unit> { throw IllegalStateException("boom") }
                    }
                    assertEquals("boom", failure.message)
                }
            }
        }

    @Test
    fun `given two policies sharing a named global limiter when the second is limited then only the second emits RateLimited`() =
        runTest {
            val registry = RateLimiterRegistry()
            fun build() = resilient(ResilientScope()) {
                rateLimiterNamed(registry, "api") {
                    maxCalls = 1
                    period = 10.minutes
                }
            }
            val first = build()
            val second = build()
            val firstSeen = mutableListOf<ResilientEvent>()
            val secondSeen = mutableListOf<ResilientEvent>()
            val firstJob = launch(UnconfinedTestDispatcher(testScheduler)) { first.events.collect { firstSeen += it } }
            val secondJob = launch(UnconfinedTestDispatcher(testScheduler)) { second.events.collect { secondSeen += it } }

            second.execute { }
            assertFailsWith<RateLimitExceededException> { second.execute { } }
            runCurrent()
            firstJob.cancelAndJoin()
            secondJob.cancelAndJoin()

            assertEquals(1, secondSeen.count { it is ResilientEvent.RateLimited })
            assertEquals(0, firstSeen.count { it is ResilientEvent.RateLimited })
        }
}
