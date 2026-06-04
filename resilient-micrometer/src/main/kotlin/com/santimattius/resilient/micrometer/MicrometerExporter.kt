package com.santimattius.resilient.micrometer

import com.santimattius.resilient.annotations.ResilientExperimentalApi
import com.santimattius.resilient.telemetry.ResilientEvent
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * Exports [ResilientEvent] telemetry from this [SharedFlow] to Micrometer metrics.
 *
 * Each event type is mapped to a named Micrometer [Counter]. The collection runs in the
 * provided [scope] as a background coroutine; cancel the returned [Job] to stop processing.
 *
 * @param registry The Micrometer [MeterRegistry] used to register and increment counters.
 * @param scope The [CoroutineScope] that drives the collection coroutine.
 *              Defaults to [GlobalScope]; prefer passing an explicit scope to control lifecycle.
 * @return A [Job] for the background collection coroutine. Cancel it to stop exporting.
 */
@ResilientExperimentalApi
@OptIn(DelicateCoroutinesApi::class)
fun SharedFlow<ResilientEvent>.exportToMicrometer(
    registry: MeterRegistry,
    scope: CoroutineScope = GlobalScope,
): Job {
    return scope.launch {
        collect { event ->
            when (event) {
                is ResilientEvent.RetryAttempt ->
                    Counter.builder("resilient.retry.attempts")
                        .description("Number of retry attempts")
                        .tag("attempt", event.attempt.toString())
                        .register(registry)
                        .increment()

                is ResilientEvent.CircuitStateChanged ->
                    Counter.builder("resilient.circuit_breaker.state_changes")
                        .description("Number of circuit breaker state changes")
                        .tag("from", event.from.name)
                        .tag("to", event.to.name)
                        .register(registry)
                        .increment()

                is ResilientEvent.RateLimited ->
                    Counter.builder("resilient.rate_limiter.limited")
                        .description("Number of times execution was rate limited")
                        .register(registry)
                        .increment()

                is ResilientEvent.BulkheadRejected ->
                    Counter.builder("resilient.bulkhead.rejected")
                        .description("Number of bulkhead rejections")
                        .register(registry)
                        .increment()

                is ResilientEvent.OperationSuccess ->
                    Counter.builder("resilient.operation.success")
                        .description("Number of successful operations")
                        .register(registry)
                        .increment()

                is ResilientEvent.OperationFailure ->
                    Counter.builder("resilient.operation.failure")
                        .description("Number of failed operations")
                        .register(registry)
                        .increment()

                is ResilientEvent.CacheHit ->
                    Counter.builder("resilient.cache.hits")
                        .description("Number of cache hits")
                        .register(registry)
                        .increment()

                is ResilientEvent.CacheMiss ->
                    Counter.builder("resilient.cache.misses")
                        .description("Number of cache misses")
                        .register(registry)
                        .increment()

                is ResilientEvent.TimeoutTriggered ->
                    Counter.builder("resilient.timeout.triggered")
                        .description("Number of timeout triggers")
                        .register(registry)
                        .increment()

                is ResilientEvent.HedgingUsed ->
                    Counter.builder("resilient.hedging.used")
                        .description("Number of times hedging was used")
                        .register(registry)
                        .increment()

                is ResilientEvent.FallbackTriggered ->
                    Counter.builder("resilient.fallback.triggered")
                        .description("Number of fallback triggers")
                        .register(registry)
                        .increment()
            }
        }
    }
}
