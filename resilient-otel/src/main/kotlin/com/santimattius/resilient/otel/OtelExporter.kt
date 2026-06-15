package com.santimattius.resilient.otel

import com.santimattius.resilient.annotations.ResilientExperimentalApi
import com.santimattius.resilient.telemetry.ResilientEvent
import io.opentelemetry.api.metrics.Meter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * Exports [ResilientEvent] telemetry from this [SharedFlow] to OpenTelemetry metrics.
 *
 * Each event type is mapped to a named OTel counter. The collection runs in the provided
 * [scope] as a background coroutine; cancel the returned [Job] to stop processing.
 *
 * @param meter The OTel [Meter] used to build and record counters.
 * @param scope The [CoroutineScope] that drives the collection coroutine.
 *              Defaults to [GlobalScope]; prefer passing an explicit scope to control lifecycle.
 * @return A [Job] for the background collection coroutine. Cancel it to stop exporting.
 */
@ResilientExperimentalApi
@OptIn(DelicateCoroutinesApi::class)
fun SharedFlow<ResilientEvent>.exportToOpenTelemetry(
    meter: Meter,
    scope: CoroutineScope = GlobalScope,
): Job {
    val retryAttemptsCounter = meter.counterBuilder("resilient.retry.attempts")
        .setDescription("Number of retry attempts")
        .build()

    val circuitBreakerStateChangesCounter = meter.counterBuilder("resilient.circuit_breaker.state_changes")
        .setDescription("Number of circuit breaker state changes")
        .build()

    val rateLimiterLimitedCounter = meter.counterBuilder("resilient.rate_limiter.limited")
        .setDescription("Number of times execution was rate limited")
        .build()

    val bulkheadRejectedCounter = meter.counterBuilder("resilient.bulkhead.rejected")
        .setDescription("Number of bulkhead rejections")
        .build()

    val operationSuccessCounter = meter.counterBuilder("resilient.operation.success")
        .setDescription("Number of successful operations")
        .build()

    val operationFailureCounter = meter.counterBuilder("resilient.operation.failure")
        .setDescription("Number of failed operations")
        .build()

    val cacheHitsCounter = meter.counterBuilder("resilient.cache.hits")
        .setDescription("Number of cache hits")
        .build()

    val cacheMissesCounter = meter.counterBuilder("resilient.cache.misses")
        .setDescription("Number of cache misses")
        .build()

    val timeoutTriggeredCounter = meter.counterBuilder("resilient.timeout.triggered")
        .setDescription("Number of timeout triggers")
        .build()

    val hedgingUsedCounter = meter.counterBuilder("resilient.hedging.used")
        .setDescription("Number of times hedging was used")
        .build()

    val fallbackTriggeredCounter = meter.counterBuilder("resilient.fallback.triggered")
        .setDescription("Number of fallback triggers")
        .build()

    return scope.launch {
        collect { event ->
            when (event) {
                is ResilientEvent.RetryAttempt -> retryAttemptsCounter.add(1)
                is ResilientEvent.CircuitStateChanged -> circuitBreakerStateChangesCounter.add(1)
                is ResilientEvent.RateLimited -> rateLimiterLimitedCounter.add(1)
                is ResilientEvent.BulkheadRejected -> bulkheadRejectedCounter.add(1)
                is ResilientEvent.OperationSuccess -> operationSuccessCounter.add(1)
                is ResilientEvent.OperationFailure -> operationFailureCounter.add(1)
                is ResilientEvent.CacheHit -> cacheHitsCounter.add(1)
                is ResilientEvent.CacheMiss -> cacheMissesCounter.add(1)
                is ResilientEvent.TimeoutTriggered -> timeoutTriggeredCounter.add(1)
                is ResilientEvent.HedgingUsed -> hedgingUsedCounter.add(1)
                is ResilientEvent.FallbackTriggered -> fallbackTriggeredCounter.add(1)
            }
        }
    }
}
