package com.santimattius.resilient.micrometer

import com.santimattius.resilient.annotations.ResilientExperimentalApi
import com.santimattius.resilient.circuitbreaker.CircuitState
import com.santimattius.resilient.telemetry.ResilientEvent
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@OptIn(ResilientExperimentalApi::class)
class MicrometerExporterTest {

    @Test
    fun `S2 given exportToMicrometer active when CircuitStateChanged to OPEN emitted then Micrometer metric reflects state change`() = runTest {
        val registry = SimpleMeterRegistry()
        val flow = MutableSharedFlow<ResilientEvent>(extraBufferCapacity = 16)
        val job = flow.exportToMicrometer(registry = registry, scope = this)

        // Advance to start the collection coroutine
        testScheduler.advanceUntilIdle()

        flow.emit(
            ResilientEvent.CircuitStateChanged(
                from = CircuitState.CLOSED,
                to = CircuitState.OPEN
            )
        )

        testScheduler.advanceUntilIdle()

        val counter = registry.find("resilient.circuit_breaker.state_changes").counter()
        assertNotNull(counter, "Expected metric 'resilient.circuit_breaker.state_changes' to be registered")
        assertEquals(1.0, counter.count(), "Expected counter value of 1 after one CircuitStateChanged event")

        job.cancel()
    }

    @Test
    fun `S2 triangulation given multiple CircuitStateChanged events then counter accumulates`() = runTest {
        val registry = SimpleMeterRegistry()
        val flow = MutableSharedFlow<ResilientEvent>(extraBufferCapacity = 16)
        val job = flow.exportToMicrometer(registry = registry, scope = this)

        // Advance to start the collection coroutine
        testScheduler.advanceUntilIdle()

        flow.emit(ResilientEvent.CircuitStateChanged(from = CircuitState.CLOSED, to = CircuitState.OPEN))
        flow.emit(ResilientEvent.CircuitStateChanged(from = CircuitState.OPEN, to = CircuitState.HALF_OPEN))
        flow.emit(ResilientEvent.CircuitStateChanged(from = CircuitState.HALF_OPEN, to = CircuitState.CLOSED))

        testScheduler.advanceUntilIdle()

        val counters = registry.find("resilient.circuit_breaker.state_changes").counters()
        val totalCount = counters.sumOf { it.count() }
        assertEquals(3.0, totalCount, "Expected counter total of 3 after three CircuitStateChanged events")

        job.cancel()
    }

    @Test
    fun `S4 given active export job when cancelled then no further events are processed`() = runTest {
        val registry = SimpleMeterRegistry()
        val flow = MutableSharedFlow<ResilientEvent>(extraBufferCapacity = 16)
        val job = flow.exportToMicrometer(registry = registry, scope = this)

        // Advance to start the collection coroutine
        testScheduler.advanceUntilIdle()

        flow.emit(
            ResilientEvent.CircuitStateChanged(from = CircuitState.CLOSED, to = CircuitState.OPEN)
        )
        testScheduler.advanceUntilIdle()

        job.cancel()
        testScheduler.advanceUntilIdle()

        // After cancellation, counter should remain at 1 (only the pre-cancel event counted)
        val counters = registry.find("resilient.circuit_breaker.state_changes").counters()
        val totalCount = counters.sumOf { it.count() }
        assertEquals(1.0, totalCount, "Only the pre-cancellation event should have been counted")
    }
}
