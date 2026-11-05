package com.santimattius.resilient.composition

import com.santimattius.resilient.telemetry.ResilientEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
}
