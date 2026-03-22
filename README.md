# Resilient  - Kotlin Multiplatform Library

A Kotlin Multiplatform library providing resilience patterns (Timeout, Retry, Circuit Breaker, Rate Limiter, Bulkhead, Hedging, Cache, Fallback) for suspend functions. Compose them declaratively with a small DSL and observe runtime telemetry via Flow.

## Quick Start
```kotlin
import com.santimattius.resilient.composition.resilient
import com.santimattius.resilient.retry.ExponentialBackoff
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.milliseconds

val policy = resilient {
    timeout { timeout = 2.seconds }
    retry {
        maxAttempts = 3
        backoffStrategy = ExponentialBackoff(initialDelay = 100.milliseconds)
    }
    circuitBreaker {
        failureThreshold = 5
        successThreshold = 2
        timeout = 30.seconds
    }
}

suspend fun call(): String = policy.execute { fetchData() }
```

## Telemetry
Observe runtime events from `policy.events` (SharedFlow):
- **RetryAttempt**(attempt, error)
- **CircuitStateChanged**(from, to)
- **RateLimited**(waitTime)
- **BulkheadRejected**(reason)
- **CacheHit**(key) / **CacheMiss**(key)
- **TimeoutTriggered**(timeout)
- **FallbackTriggered**(error)
- **HedgingUsed**(attemptIndex)
- **OperationSuccess**(duration) / **OperationFailure**(error, duration)

```kotlin
val job = scope.launch {
    policy.events.collect { event -> println(event) }
}
```

## Composition Order
Outer → Inner (default):
- Fallback → Cache → Coalesce → Timeout → Retry → Circuit Breaker → Rate Limiter → Bulkhead → Hedging → Block
- Fallback wraps outermost to handle failures after all policies.

### Custom Composition Order
The composition order is configurable. Use `compositionOrder()` to specify a custom order:

```kotlin
import com.santimattius.resilient.composition.OrderablePolicyType

val policy = resilient(scope) {
    compositionOrder(listOf(
        OrderablePolicyType.CACHE,        // Check cache first (after Fallback)
        OrderablePolicyType.COALESCE,     // Deduplicate in-flight requests by key
        OrderablePolicyType.TIMEOUT,      // Then apply timeout
        OrderablePolicyType.RETRY,        // Retry on failures
        OrderablePolicyType.CIRCUIT_BREAKER,
        OrderablePolicyType.RATE_LIMITER,
        OrderablePolicyType.BULKHEAD,
        OrderablePolicyType.HEDGING
    ))
    // Fallback is automatically added as the outermost policy
    // ... configure policies
}
```

**Important Notes:**
- **Fallback is not included** in `OrderablePolicyType` - it is always positioned outermost automatically
- `compositionOrder(...)` accepts a subset: policy types not included are appended in the default order
- Duplicates are removed
- Fallback must remain outermost to catch all failures from other policies
- The order affects how policies interact with each other, so choose carefully based on your use case

### Timeout vs Retry order
- **Timeout outer, Retry inner** (default): The timeout applies to the **entire** execution (all retries combined). A single slow attempt can consume the full timeout; if it expires, the whole operation fails.
- **Retry outer, Timeout inner**: Each **attempt** has its own timeout. You can get more attempts within the same total wall-clock time. Use `compositionOrder(listOf(..., OrderablePolicyType.RETRY, OrderablePolicyType.TIMEOUT, ...))` if you want per-attempt timeout.

---

## Features

### Timeout
Aborts the operation after a configured duration. `onTimeout` runs only on actual timeouts. This is a **single** time limit for the block as wrapped by the policy (see [Timeout vs Retry order](#timeout-vs-retry-order)). For a timeout **per retry attempt**, use [Retry](#retry) with `perAttemptTimeout` instead.
```kotlin
val policy = resilient {
    timeout {
        timeout = 3.seconds
        onTimeout = { /* log metric */ }
    }
}
```

### Retry
Retries failing operations according to a backoff strategy and predicate. Use **shouldRetry** to avoid retrying non-transient errors (e.g. 4xx client errors); retry only on 5xx, network/IO, or timeouts. Optional **perAttemptTimeout** limits each attempt (including the first) so a single slow attempt does not consume the whole retry budget.

Optional **shouldRetryResult** retries when the block returns successfully but the value is not acceptable (e.g. HTTP 202, empty body, “not ready” flag). Return `true` from the predicate to request another attempt; it shares the same **maxAttempts** budget as exception-based retries. **`onRetry`** receives `RetryableResultException` (with `lastValue`) for telemetry; if attempts are exhausted while the predicate stays `true`, the **last returned value** is returned (unlike exception exhaustion, which rethrows).
```kotlin
import com.santimattius.resilient.retry.*

val policy = resilient {
    retry {
        maxAttempts = 4
        shouldRetry = { it is java.io.IOException }  // e.g. only retry IO; do not retry 4xx
        shouldRetryResult = { status -> (status as Int) >= 500 } // e.g. retry on 5xx-like codes returned as value
        perAttemptTimeout = 5.seconds               // optional: timeout per attempt
        backoffStrategy = ExponentialBackoff(
            initialDelay = 200.milliseconds,
            maxDelay = 5.seconds,
            factor = 2.0,
            jitter = true
        )
    }
}
```
Supported backoffs: `ExponentialBackoff`, `LinearBackoff`, `FixedBackoff`.

### Circuit Breaker
Prevents hammering failing downstreams. States: CLOSED → OPEN → HALF_OPEN.

By default, **`failureThreshold`** counts **consecutive** failures in CLOSED (a success resets the count). Set **`slidingWindow`** to a positive duration to open when **`failureThreshold`** failures fall **within that time window** (not necessarily consecutive); successes only prune expired timestamps from the window.
```kotlin
val policy = resilient(scope) {
    circuitBreaker {
        failureThreshold = 5
        successThreshold = 2
        halfOpenMaxCalls = 1
        timeout = 60.seconds // OPEN duration
        slidingWindow = 30.seconds // optional: time-based failure window
    }
}
```

### Rate Limiter
Token-bucket rate limiting: tokens refill at the **start of each period** (fixed-window refill). With `maxCalls = 10` and `period = 1.seconds`, at most 10 calls per second are allowed. One bucket per policy (global limit). Optional max wait when limited.
```kotlin
val policy = resilient {
    rateLimiter {
        maxCalls = 10
        period = 1.seconds
        timeoutWhenLimited = 5.seconds // throw if wait would exceed
        onRateLimited = { /* metric */ }
    }
}
```

### Bulkhead
Limit concurrent executions and queued waiters; optional acquire timeout.
```kotlin
val policy = resilient(scope) {
    bulkhead {
        maxConcurrentCalls = 8
        maxWaitingCalls = 32
        timeout = 2.seconds
    }
}
```

**Named / shared bulkhead:** use `BulkheadRegistry` so several policies share the same pool (e.g. one limit for all `"database"` calls). Cannot combine `bulkhead { }` and `bulkheadNamed(...)` in the same policy.
```kotlin
import com.santimattius.resilient.bulkhead.BulkheadRegistry

val bulkheads = BulkheadRegistry()

val policyA = resilient(scope) {
    bulkheadNamed(bulkheads, "database") {
        maxConcurrentCalls = 4
        maxWaitingCalls = 16
    }
}
val policyB = resilient(scope) {
    bulkheadNamed(bulkheads, "database") {
        maxConcurrentCalls = 4 // first registration wins; same instance as policyA
    }
}
```

### Hedging
Launch parallel attempts and return the first successful result. Use carefully (extra load).
```kotlin
val policy = resilient {
    hedging {
        attempts = 3
        stagger = 50.milliseconds
    }
}
```

### Cache (In-Memory TTL)
Cache successful results per key with TTL. Use a fixed **key** or a **keyProvider** (suspend) for dynamic keys (e.g. per user, per request). Invalidation is available via `policy.cacheHandle` when cache is configured.
```kotlin
val policy = resilient(scope) {
    cache {
        key = "users:123"                    // fixed key
        // or keyProvider = { "user:${userId}" }  // dynamic key
        ttl = 30.seconds
    }
}

// Invalidate by key or prefix (e.g. after write)
policy.cacheHandle?.invalidate("users:123")
policy.cacheHandle?.invalidatePrefix("user:")
```
The in-memory implementation is one possible `CachePolicy`; custom backends (e.g. persistent storage or Redis) can implement the same interface.

### Coalescing (Request Deduplication)
Deduplicates **concurrent in-flight** executions by key. If multiple callers resolve the same key while the operation is still running, only one block execution happens and all callers share the same result (or error). It does **not** cache completed results; for TTL caching, use `cache { ... }`.
```kotlin
val policy = resilient(scope) {
    coalesce {
        key = "profile:42" // fixed key
        // or keyProvider = { "profile:${userId}" } // dynamic key
    }
}
```

### Health / Readiness
Use `policy.getHealthSnapshot()` to build health or readiness endpoints (e.g. Kubernetes probes, `/health` API). The snapshot includes circuit breaker state and counters, and bulkhead usage when configured.
```kotlin
import com.santimattius.resilient.circuitbreaker.CircuitState

val snapshot = policy.getHealthSnapshot()

// Circuit breaker: is the circuit open?
val healthy = snapshot.circuitBreaker?.state != CircuitState.OPEN
// Optional: snapshot.circuitBreaker?.failureCount, successCount for metrics

// Bulkhead: active and waiting counts
snapshot.bulkhead?.let { bh ->
    // bh.activeConcurrentCalls, bh.waitingCalls, bh.maxConcurrentCalls, bh.maxWaitingCalls
}
```

### Fallback
Return a fallback value when the operation fails. **Ensure the fallback return type matches the type returned by the block** passed to `execute()`, otherwise a `ClassCastException` may occur at runtime.
```kotlin
import com.santimattius.resilient.fallback.FallbackConfig

val policy = resilient {
    fallback(FallbackConfig { err ->
        // produce a fallback from error (log/metric here)
        "fallback-value"
    })
}
```

---

## Combined Example
```kotlin
val policy = resilient {
    cache { key = "profile:42"; ttl = 20.seconds }
    timeout { timeout = 2.seconds }
    retry {
        maxAttempts = 3
        backoffStrategy = ExponentialBackoff(initialDelay = 100.milliseconds)
    }
    circuitBreaker { failureThreshold = 5; successThreshold = 2; halfOpenMaxCalls = 1 }
    rateLimiter { maxCalls = 50; period = 1.seconds }
    bulkhead { maxConcurrentCalls = 10; maxWaitingCalls = 100 }
    hedging { attempts = 2 }
    fallback(FallbackConfig { "cached-or-default" })
}

suspend fun load(): User = policy.execute { loadProfile() }
```

## Android (Compose) Example
A ready-to-run sample is available at:
- `androidApp/src/main/kotlin/com/santimattius/kmp/ResilientExample.kt`

Use it from `MainActivity`:
```kotlin
setContent { ResilientExample() }
```

## Best Practices
- Start simple: Timeout + Retry. Add Circuit Breaker for flaky dependencies.
- **shouldRetry:** Do not retry on 4xx client errors (e.g. 400, 404, 409); retry only on transient failures (5xx, network/IO, timeouts). This avoids amplifying bad requests and keeps idempotency expectations clear.
- Rate limiter for external APIs; Bulkhead for database or threadpool protection.
- Hedging reduces tail latency but raises load—use selectively.
- Cache only idempotent reads; set TTL appropriately.
- Always observe telemetry (including CacheHit/CacheMiss, FallbackTriggered) for visibility and tuning.

## Testing with `resilient-test`

The `resilient-test` module provides utilities for testing resilience policies with fault injection and simplified policy builders.

### Fault Injection

Use `FaultInjector` to simulate failures, delays, and intermittent behavior in your tests:

```kotlin
import com.santimattius.resilient.test.FaultInjector
import kotlin.time.Duration.Companion.milliseconds

val injector = FaultInjector.builder()
    .failureRate(0.3)           // 30% chance of failure
    .delay(50.milliseconds)      // Add 50ms delay
    .delayJitter(true)           // Randomize delay ±20%
    .exception { CustomException() }  // Custom exception
    .build()

val result = injector.execute {
    fetchData() // may throw or delay
}
```

**Configuration:**
- `failureRate(rate: Double)`: Probability of throwing an exception (0.0 = never, 1.0 = always).
- `exception(block: () -> Throwable)`: Factory for the exception to throw (default: `FaultInjectedException`).
- `delay(duration: Duration)`: Fixed delay before executing the block (default: `Duration.ZERO`).
- `delayJitter(enable: Boolean)`: Randomize delay ±20% (default: `false`).

### Policy Builders for Tests

Use `PolicyBuilders` to create policies with sensible test defaults:

```kotlin
import com.santimattius.resilient.test.PolicyBuilders
import com.santimattius.resilient.test.TestResilientScope
import kotlinx.coroutines.test.runTest

@Test
fun `test retry with fault injection`() = runTest {
    val scope = TestResilientScope(this)
    val policy = PolicyBuilders.retryPolicy(
        scope,
        maxAttempts = 3,
        initialDelay = 10.milliseconds
    )
    
    val injector = FaultInjector.builder()
        .failureRate(0.5)
        .build()
    
    val result = policy.execute {
        injector.execute { "success" }
    }
    
    assertEquals("success", result)
}
```

**Available builders:**
- `retryPolicy(scope, maxAttempts = 3, initialDelay = 10ms, maxDelay = 100ms, shouldRetry = { true })`
- `timeoutPolicy(scope, timeout = 1.second)`
- `circuitBreakerPolicy(scope, failureThreshold = 3, successThreshold = 2, timeout = 5.seconds)`
- `bulkheadPolicy(scope, maxConcurrentCalls = 2, maxWaitingCalls = 4)`
- `rateLimiterPolicy(scope, maxCalls = 5, period = 1.second)`

### TestResilientScope

Use `TestResilientScope` to create a test-friendly scope for policies:

```kotlin
import com.santimattius.resilient.test.TestResilientScope
import kotlinx.coroutines.test.runTest

@Test
fun `test policy lifecycle`() = runTest {
    val scope = TestResilientScope(this)
    val policy = resilient(scope) {
        retry { maxAttempts = 3 }
    }
    
    // ... test logic
    
    scope.cancel() // cleanup
}
```

### Gradle Dependency

Add the `resilient-test` module to your test dependencies:

```kotlin
// build.gradle.kts
kotlin {
    sourceSets {
        commonTest.dependencies {
            implementation("io.github.santimattius.resilient:resilient-test:1.3.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
        }
    }
}
```

## Testing Notes
- Use `kotlinx-coroutines-test` for virtual time with `runTest` and `TestTimeSource`.
- Use `FaultInjector` to simulate realistic failure scenarios (intermittent errors, slow responses).
- Verify: retry attempts and delays, CB transitions, RL windows, BH limits, composition order, cancellation propagation, telemetry.

