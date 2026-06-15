package com.santimattius.kmp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.santimattius.resilient.circuitbreaker.CircuitBreakerOpenException
import com.santimattius.resilient.circuitbreaker.CircuitBreakerRegistry
import com.santimattius.resilient.circuitbreaker.DefaultCircuitBreaker
import com.santimattius.resilient.composition.resilient
import com.santimattius.resilient.fallback.FallbackConfig
import com.santimattius.resilient.ratelimiter.RateLimiterRegistry
import com.santimattius.resilient.retry.DecorrelatedJitterBackoff
import com.santimattius.resilient.retry.RetryableResultException
import com.santimattius.resilient.telemetry.ResilientEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ResilientViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ResilientUiState())
    val uiState: StateFlow<ResilientUiState> = _uiState.asStateFlow()

    /** Current resource id used for cache key (dynamic key via keyProvider). */
    private val _resourceId = MutableStateFlow("default")
    val resourceId: StateFlow<String> = _resourceId.asStateFlow()

    /** 1.5.0 — shared registries so multiple policies reuse the same breaker / limiter quota. */
    private val circuitBreakerRegistry = CircuitBreakerRegistry()
    private val rateLimiterRegistry = RateLimiterRegistry()

    /** 1.5.0 — [CoroutineScope.resilient] ties policy lifecycle to [viewModelScope]. */
    private val policy = viewModelScope.resilient {
        cache {
            key = "demo:default"
            keyProvider = { "demo:${_resourceId.value}" }
            ttl = 30.seconds
            cleanupInterval = 60.seconds
            cleanupBatch = 50
        }
        timeout { timeout = 2.seconds }
        retry {
            maxAttempts = 3
            perAttemptTimeout = 800.milliseconds
            backoffStrategy = DecorrelatedJitterBackoff(
                base = 100.milliseconds,
                cap = 800.milliseconds,
            )
            shouldRetry = { it is IllegalStateException }
            shouldRetryResult = { it == "WARMUP" }
        }
        circuitBreakerNamed(circuitBreakerRegistry, "demo-api") {
            failureRateThreshold = 50.0
            minimumNumberOfCalls = 4
            successThreshold = 2
            timeout = 10.seconds
            shouldRecordResult = { it == "DEGRADED" }
        }
        rateLimiterNamed(rateLimiterRegistry, "demo-api") {
            maxCalls = 6
            period = 5.seconds
        }
        bulkhead {
            maxConcurrentCalls = 4
            maxWaitingCalls = 8
        }
        fallback(FallbackConfig { e -> "fallback: ${e.message}" })
    }

    /** Second policy sharing the same registry entries — demonstrates global trip / quota. */
    private val secondaryPolicy = viewModelScope.resilient {
        circuitBreakerNamed(circuitBreakerRegistry, "demo-api") {
            failureThreshold = 99
        }
        rateLimiterNamed(rateLimiterRegistry, "demo-api") {
            maxCalls = 99
            period = 5.seconds
        }
    }

    private var warmupStep = 0

    init {
        observeSharedCircuitBreaker()
        refreshHealthSnapshot()
    }

    fun setResourceId(id: String) {
        _resourceId.value = id
    }

    fun setDemoMode(mode: DemoMode) {
        _uiState.update { it.copy(demoMode = mode) }
    }

    fun executePolicy() {
        viewModelScope.launch {
            warmupStep = 0
            _uiState.update { it.copy(result = null, error = null, events = emptyList(), isLoading = true) }
            coroutineScope {
                val collector = launch {
                    policy.events.collect { event ->
                        val label = formatEvent(event)
                        _uiState.update { current ->
                            current.copy(events = (current.events + label).takeLast(14))
                        }
                    }
                }

                try {
                    val value = when (_uiState.value.demoMode) {
                        DemoMode.SHARED_BREAKER -> executeSharedBreakerDemo()
                        else -> policy.execute { simulateBackendCall(_uiState.value.demoMode) }
                    }
                    _uiState.update { it.copy(result = value, error = null, isLoading = false) }
                } catch (t: CancellationException) {
                    throw t
                } catch (t: Throwable) {
                    _uiState.update {
                        it.copy(
                            error = t.message ?: t::class.simpleName ?: "Unknown error",
                            isLoading = false,
                        )
                    }
                } finally {
                    collector.cancel()
                    refreshHealthSnapshot()
                }
            }
        }
    }

    /** Invalidates the cache entry for the current resource (demo:resourceId). */
    fun invalidateCache() {
        viewModelScope.launch {
            policy.cacheHandle?.invalidate("demo:${_resourceId.value}")
            _uiState.update { current ->
                current.copy(events = (current.events + "Cache invalidated: demo:${_resourceId.value}").takeLast(14))
            }
            refreshHealthSnapshot()
        }
    }

    /** Invalidates all cache entries whose key starts with "demo:". */
    fun invalidateCachePrefix() {
        viewModelScope.launch {
            policy.cacheHandle?.invalidatePrefix("demo:")
            _uiState.update { current ->
                current.copy(events = (current.events + "Cache invalidated (prefix: demo:)").takeLast(14))
            }
            refreshHealthSnapshot()
        }
    }

    /** Refreshes the health/readiness snapshot (circuit breaker, bulkhead, rate limiter, retry, cache). */
    fun refreshHealthSnapshot() {
        _uiState.update {
            it.copy(
                healthSnapshot = policy.getHealthSnapshot(),
                sharedCircuitState = sharedCircuitBreaker.state.value,
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        policy.close()
        secondaryPolicy.close()
    }

    private val sharedCircuitBreaker: DefaultCircuitBreaker
        get() = circuitBreakerRegistry.getOrCreate(name="demo-api", {})

    private fun observeSharedCircuitBreaker() {
        viewModelScope.launch {
            sharedCircuitBreaker.state.collect { state ->
                _uiState.update { it.copy(sharedCircuitState = state) }
            }
        }
    }

    private suspend fun executeSharedBreakerDemo(): String {
        appendEvent("Tripping shared breaker via primary policy (3× DEGRADED)…")
        repeat(3) {
            policy.execute { "DEGRADED" }
        }
        appendEvent("Shared circuit state: ${sharedCircuitBreaker.state.value}")

        return try {
            secondaryPolicy.execute {
                delay(50)
                "via-secondary"
            }
        } catch (e: CircuitBreakerOpenException) {
            appendEvent("Secondary policy rejected — same CircuitBreakerRegistry entry")
            throw e
        }
    }

    private suspend fun simulateBackendCall(mode: DemoMode): String {
        delay(100)
        return when (mode) {
            DemoMode.FLAKY -> {
                if (System.currentTimeMillis() % 2L == 0L) {
                    throw IllegalStateException("Simulated failure")
                }
                "OK"
            }
            DemoMode.DEGRADED -> "DEGRADED"
            DemoMode.WARMUP -> {
                val value = if (warmupStep < 2) "WARMUP" else "OK"
                warmupStep++
                value
            }
            DemoMode.SHARED_BREAKER -> error("Handled by executeSharedBreakerDemo()")
        }
    }

    private fun appendEvent(label: String) {
        _uiState.update { current ->
            current.copy(events = (current.events + label).takeLast(14))
        }
    }

    private fun formatEvent(event: ResilientEvent): String = when (event) {
        is ResilientEvent.CacheHit -> "Cache hit: ${event.key}"
        is ResilientEvent.CacheMiss -> "Cache miss: ${event.key}"
        is ResilientEvent.RetryAttempt -> when (val error = event.error) {
            is RetryableResultException -> "Retry #${event.attempt} (result=${error.lastValue})"
            else -> "Retry #${event.attempt}: ${error.message}"
        }
        is ResilientEvent.CircuitStateChanged -> "Circuit ${event.from} → ${event.to}"
        is ResilientEvent.OperationSuccess -> "Success in ${event.duration}"
        is ResilientEvent.OperationFailure -> "Failed: ${event.error.message}"
        is ResilientEvent.TimeoutTriggered -> "Timeout: ${event.timeout}"
        is ResilientEvent.FallbackTriggered -> "Fallback: ${event.error.message}"
        is ResilientEvent.HedgingUsed -> "Hedging attempt ${event.attemptIndex + 1}"
        is ResilientEvent.RateLimited -> "Rate limited: wait ${event.waitTime}"
        is ResilientEvent.BulkheadRejected -> "Bulkhead: ${event.reason}"
    }
}
