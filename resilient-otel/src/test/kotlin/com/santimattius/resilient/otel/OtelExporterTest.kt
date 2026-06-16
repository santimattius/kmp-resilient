package com.santimattius.resilient.otel

import com.santimattius.resilient.annotations.ResilientExperimentalApi
import com.santimattius.resilient.telemetry.ResilientEvent
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ResilientExperimentalApi::class)
class OtelExporterTest {

    @Test
    fun `S1 given exportToOpenTelemetry active when RetryAttempt emitted then OTel retry counter incremented`() = runTest {
        val reader = InMemoryMetricReader.create()
        val meterProvider = SdkMeterProvider.builder()
            .registerMetricReader(reader)
            .build()
        val openTelemetry = OpenTelemetrySdk.builder()
            .setMeterProvider(meterProvider)
            .build()
        val meter = openTelemetry.getMeter("test")

        val flow = MutableSharedFlow<ResilientEvent>(extraBufferCapacity = 16)
        val job = flow.exportToOpenTelemetry(meter = meter, scope = this)

        // Advance scheduler to start the collection coroutine
        testScheduler.advanceUntilIdle()

        // Emit a RetryAttempt event
        flow.emit(ResilientEvent.RetryAttempt(attempt = 1, error = RuntimeException("test")))

        // Give the collector coroutine time to process
        testScheduler.advanceUntilIdle()

        val metrics: Collection<MetricData> = reader.collectAllMetrics()
        val retryMetric = metrics.find { it.name == "resilient.retry.attempts" }
        requireNotNull(retryMetric) { "Expected metric 'resilient.retry.attempts' to be recorded" }

        val totalCount = retryMetric.longSumData.points.sumOf { it.value }
        assertEquals(1L, totalCount, "Expected counter value of 1 after one RetryAttempt")

        job.cancel()
    }

    @Test
    fun `S1 triangulation given exportToOpenTelemetry active when two RetryAttempt events emitted then counter equals 2`() = runTest {
        val reader = InMemoryMetricReader.create()
        val meterProvider = SdkMeterProvider.builder()
            .registerMetricReader(reader)
            .build()
        val openTelemetry = OpenTelemetrySdk.builder()
            .setMeterProvider(meterProvider)
            .build()
        val meter = openTelemetry.getMeter("test")

        val flow = MutableSharedFlow<ResilientEvent>(extraBufferCapacity = 16)
        val job = flow.exportToOpenTelemetry(meter = meter, scope = this)

        // Advance to start collector
        testScheduler.advanceUntilIdle()

        flow.emit(ResilientEvent.RetryAttempt(attempt = 1, error = RuntimeException("test1")))
        flow.emit(ResilientEvent.RetryAttempt(attempt = 2, error = RuntimeException("test2")))

        testScheduler.advanceUntilIdle()

        val metrics = reader.collectAllMetrics()
        val retryMetric = metrics.find { it.name == "resilient.retry.attempts" }
        requireNotNull(retryMetric) { "Expected metric 'resilient.retry.attempts' to be recorded" }

        val totalCount = retryMetric.longSumData.points.sumOf { it.value }
        assertEquals(2L, totalCount, "Expected counter value of 2 after two RetryAttempt events")

        job.cancel()
    }

    @Test
    fun `S4 given active export job when job cancelled then no further events are processed`() = runTest {
        val reader = InMemoryMetricReader.create()
        val meterProvider = SdkMeterProvider.builder()
            .registerMetricReader(reader)
            .build()
        val openTelemetry = OpenTelemetrySdk.builder()
            .setMeterProvider(meterProvider)
            .build()
        val meter = openTelemetry.getMeter("test")

        val flow = MutableSharedFlow<ResilientEvent>(extraBufferCapacity = 16)
        val job = flow.exportToOpenTelemetry(meter = meter, scope = this)

        // Advance to start collector
        testScheduler.advanceUntilIdle()

        flow.emit(ResilientEvent.RetryAttempt(attempt = 1, error = RuntimeException("before cancel")))
        testScheduler.advanceUntilIdle()

        job.cancel()
        testScheduler.advanceUntilIdle()

        // Collect once after cancellation to freeze the state
        val metrics = reader.collectAllMetrics()
        val retryMetric = metrics.find { it.name == "resilient.retry.attempts" }
        requireNotNull(retryMetric) { "Expected metric recorded before cancellation" }
        val totalCount = retryMetric.longSumData.points.sumOf { it.value }
        assertEquals(1L, totalCount, "Only the pre-cancellation event should have been counted")
    }
}
