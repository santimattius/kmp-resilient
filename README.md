# Resilient  - Kotlin Multiplatform Library

A Kotlin Multiplatform library providing resilience patterns (Timeout, Retry, Circuit Breaker, Rate Limiter, Bulkhead, Hedging, Cache, Fallback) for suspend functions. Compose them declaratively with a small DSL and observe runtime telemetry via Flow.

## Table of Contents

- [Quick Start](#quick-start)
- [Telemetry](#telemetry)
- [Composition Order](#composition-order)
  - [Custom Composition Order](#custom-composition-order)
  - [Timeout vs Retry order](#timeout-vs-retry-order)
- [Features](#features)
  - [Timeout](#timeout)
  - [Retry](#retry)
  - [Circuit Breaker](#circuit-breaker)
  - [Rate Limiter](#rate-limiter)
  - [Bulkhead](#bulkhead)
  - [Hedging](#hedging)
  - [Cache (In-Memory TTL)](#cache-in-memory-ttl)
  - [Coalescing (Request Deduplication)](#coalescing-request-deduplication)
  - [CoroutineScope Binding](#coroutinescope-binding)
  - [Health / Readiness](#health--readiness)
  - [Fallback](#fallback)
- [Combined Example](#combined-example)
- [Ktor HTTP Client Integration](#ktor-http-client-integration)
- [Android (Compose) Example](#android-compose-example)
- [Best Practices](#best-practices)
- [Testing with `resilient-test`](#testing-with-resilient-test)
- [Pattern Reference](#pattern-reference)
- [Contributing](#contributing)

---

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
→ [In-depth documentation](docs/patterns/timeout.md)

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
Supported backoffs: `ExponentialBackoff`, `LinearBackoff`, `FixedBackoff`, `DecorrelatedJitterBackoff`.

**`DecorrelatedJitterBackoff`** implements the AWS decorrelated jitter formula — each caller's delay sequence is independent of every other caller's, which breaks synchronized retry waves in high-concurrency scenarios:
```kotlin
import com.santimattius.resilient.retry.DecorrelatedJitterBackoff

val policy = resilient {
    retry {
        maxAttempts     = 4
        backoffStrategy = DecorrelatedJitterBackoff(base = 100.milliseconds, cap = 10.seconds)
    }
}
```
→ [In-depth documentation](docs/patterns/retry.md)

### Circuit Breaker
Prevents hammering failing downstreams. States: CLOSED → OPEN → HALF_OPEN.

Three trip modes are available:

- **Consecutive** (default): `failureThreshold` consecutive failures open the circuit; a success resets the count.
- **Sliding window** (`slidingWindow`): `failureThreshold` failures within a time window open the circuit.
- **Failure-rate** (`failureRateThreshold`): circuit opens when the failure rate over the last `minimumNumberOfCalls` outcomes meets or exceeds a percentage.

```kotlin
val policy = resilient(scope) {
    circuitBreaker {
        failureThreshold = 5
        successThreshold = 2
        halfOpenMaxCalls = 1
        timeout          = 60.seconds
        // slidingWindow = 30.seconds       // time-based mode (mutually exclusive with failureRateThreshold)
        // failureRateThreshold = 50.0      // failure-rate mode — opens at 50% over last minimumNumberOfCalls
        // minimumNumberOfCalls = 10        // ring-buffer size (default 10)
    }
}
```

**`shouldRecordResult`** counts a successful return value as a failure (while still returning it to the caller) — useful when the downstream signals errors inside HTTP 200 responses:
```kotlin
val policy = resilient(scope) {
    circuitBreaker {
        failureThreshold   = 3
        shouldRecordResult = { result -> result is ApiResponse && result.code == 503 }
    }
}
```

**Named / shared circuit breaker:** use `CircuitBreakerRegistry` so multiple policies share the same breaker state (e.g. all `"payments"` calls trip a single breaker). Cannot combine `circuitBreaker { }` and `circuitBreakerNamed(...)` in the same policy.
```kotlin
import com.santimattius.resilient.circuitbreaker.CircuitBreakerRegistry

val circuitBreakers = CircuitBreakerRegistry()

val policyA = resilient(scope) {
    circuitBreakerNamed(circuitBreakers, "payments") {
        failureThreshold = 5
        timeout          = 30.seconds
    }
}
val policyB = resilient(scope) {
    circuitBreakerNamed(circuitBreakers, "payments") { } // same instance as policyA
}
```
→ [In-depth documentation](docs/patterns/circuit-breaker.md)

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
**Named / shared rate limiter:** use `RateLimiterRegistry` so multiple policies share the same token-bucket quota (e.g. all `"payments"` calls consume from the same pool). Cannot combine `rateLimiter { }` and `rateLimiterNamed(...)` in the same policy.
```kotlin
import com.santimattius.resilient.ratelimiter.RateLimiterRegistry

val rateLimiters = RateLimiterRegistry()

val policyA = resilient(scope) {
    rateLimiterNamed(rateLimiters, "payments") { maxCalls = 10; period = 1.seconds }
}
val policyB = resilient(scope) {
    rateLimiterNamed(rateLimiters, "payments") { } // same instance as policyA
}
```
→ [In-depth documentation](docs/patterns/rate-limiter.md)

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
→ [In-depth documentation](docs/patterns/bulkhead.md)

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
→ [In-depth documentation](docs/patterns/hedging.md)

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
→ [In-depth documentation](docs/patterns/cache.md)

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

### CoroutineScope Binding

By default you pass a `ResilientScope` explicitly. When working inside a framework that already manages a `CoroutineScope` (e.g. `viewModelScope`, `lifecycleScope`, `rememberCoroutineScope`), you can bind the policy lifecycle directly to that scope — no manual `ResilientScope` creation or `policy.close()` needed.

**`CoroutineScope.asResilientScope()`** — wraps an existing scope as a `ResilientScope`. Cancelling the outer scope automatically cancels all internal background jobs (cache cleanup, coalescing deduplication):

```kotlin
import com.santimattius.resilient.composition.asResilientScope

// Android ViewModel
class ProfileViewModel : ViewModel() {
    private val scope  = viewModelScope.asResilientScope()
    private val policy = resilient(scope) {
        cache { key = "profile"; ttl = 60.seconds }
        retry { maxAttempts = 3 }
    }
    // When the ViewModel is cleared, viewModelScope is cancelled → scope and all policy
    // background jobs are cancelled automatically. No policy.close() needed.
}
```

**`CoroutineScope.resilient { }`** — shorthand that combines `.asResilientScope()` and `resilient(scope) { }` in one call:

```kotlin
import com.santimattius.resilient.composition.resilient

class ProfileViewModel : ViewModel() {
    private val policy = viewModelScope.resilient {
        cache { key = "profile"; ttl = 60.seconds }
        retry { maxAttempts = 3 }
    }
}
```

**Lifecycle contract:**
- When the outer `CoroutineScope` is cancelled → the derived scope and all its background jobs are cancelled automatically via structured concurrency.
- Calling `policy.close()` stops only the internal policy jobs — it does **not** cancel the outer scope.
- Do not call these on an already-cancelled scope; internal coroutine launches will fail with `CancellationException`.

### Health / Readiness
Use `policy.getHealthSnapshot()` to build health or readiness endpoints (e.g. Kubernetes probes, `/health` API). The snapshot includes circuit breaker state, bulkhead usage, rate limiter quota, retry config, and cache stats when the corresponding policies are configured.
```kotlin
import com.santimattius.resilient.circuitbreaker.CircuitState

val snapshot = policy.getHealthSnapshot()

// Circuit breaker state
val healthy = snapshot.circuitBreaker?.state != CircuitState.OPEN
// snapshot.circuitBreaker?.failureCount, successCount

// Bulkhead: active and waiting counts
snapshot.bulkhead?.let { bh ->
    // bh.activeConcurrentCalls, bh.waitingCalls, bh.maxConcurrentCalls, bh.maxWaitingCalls
}

// Rate limiter: remaining tokens and time until next refill
snapshot.rateLimiter?.let { rl ->
    // rl.remainingCalls, rl.timeToRefill
}

// Retry: configured max attempts
snapshot.retry?.let { r ->
    // r.maxAttempts
}

// Cache: entry count and cumulative hit rate (Double.NaN when no calls yet)
snapshot.cache?.let { c ->
    // c.entryCount, c.hitRate
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
→ [In-depth documentation](docs/patterns/fallback.md)

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

## Ktor HTTP Client Integration

The `resilient-ktor` module provides a Ktor 3.x `HttpClient` plugin that applies a `ResilientPolicy` transparently to every outgoing request.

```kotlin
// Gradle (commonMain)
implementation("io.github.santimattius.resilient:resilient-ktor:<version>")
```

### Inline DSL (plugin creates and owns the policy)
```kotlin
val client = HttpClient {
    install(ResilientPlugin) {
        scope = viewModelScope.asResilientScope()
        retry { maxAttempts = 3 }
        circuitBreaker { failureThreshold = 5 }
        timeout { timeout = 10.seconds }
        shouldRetryOnStatus = { it.value >= 500 } // default
        retryOnlyIdempotent = true                // default: POST/PATCH bypass policy
    }
}
```

### BYO policy (bring your own pre-built policy)
```kotlin
val policy = viewModelScope.resilient {
    retry {
        maxAttempts = 3
        shouldRetryResult = { result ->
            (result as? HttpClientCall)?.response?.status?.value?.let { it >= 500 } ?: false
        }
    }
    circuitBreaker { failureThreshold = 5 }
}

val client = HttpClient {
    install(ResilientPlugin) { policy = policy }
}

// Observe telemetry directly from the policy
scope.launch { policy.events.collect { println(it) } }
```

**Notes:**
- Requires Ktor 3.x (`io.ktor:ktor-client-core:3.2.0+`). Ktor 2.x is not supported.
- `shouldRetryOnStatus` only applies in inline DSL mode. BYO policy users must wire `shouldRetryResult` inside the policy's own `RetryPolicyConfig`.
- `retryOnlyIdempotent = true` skips the entire policy (retry, circuit breaker, timeout) for POST and PATCH — not just retry — to preserve at-most-once semantics.
- `hedging` is not supported in Ktor plugin context (incompatible with single-request `on(Send)` hook).

## Android (Compose) Example
A ready-to-run sample is available at:
- `androidApp/src/main/kotlin/com/santimattius/kmp/ResilientExample.kt` — core policy demo
- `androidApp/src/main/kotlin/com/santimattius/kmp/KtorDemoScreen.kt` — Ktor plugin demo (503 → 503 → 200 retry sequence)

Use it from `MainActivity`:
```kotlin
setContent { DemoApp() } // tab navigation: Policy Demo | Ktor Plugin
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

Use `FaultInjector` to simulate failures, delays, and intermittent behavior in your tests. Prefer **`failCount`** for unit tests — it produces deterministic results and avoids intermittent failures.

```kotlin
import com.santimattius.resilient.test.FaultInjector
import kotlin.time.Duration.Companion.milliseconds

// Deterministic (preferred for unit tests): fail the first N calls, then succeed
val injector = FaultInjector.builder()
    .failCount(3)   // fails calls 1–3, succeeds on call 4
    .build()

// Probabilistic: use only for chaos / load tests, not unit tests
val chaosInjector = FaultInjector.builder()
    .failureRate(0.3)           // 30% chance of failure per call
    .delay(50.milliseconds)     // Add 50ms delay
    .delayJitter(true)          // Randomize delay ±20%
    .exception { CustomException() }
    .build()
```

**Configuration:**
- `failCount(n: Int)`: Fail the first `n` calls deterministically, then always succeed. Takes precedence over `failureRate`. **Preferred for unit tests.**
- `failureRate(rate: Double)`: Probability of throwing an exception per call (0.0 = never, 1.0 = always). Ignored when `failCount > 0`.
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
    val scope = TestResilientScope()
    val policy = PolicyBuilders.retryPolicy(
        scope,
        maxAttempts = 3,
        initialDelay = 10.milliseconds
    )

    val injector = FaultInjector.builder()
        .failCount(2)  // fail first 2 calls, succeed on the 3rd
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
            implementation("io.github.santimattius.resilient:resilient-test:1.5.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
        }
    }
}
```

## Testing Notes
- Use `kotlinx-coroutines-test` for virtual time with `runTest` and `TestTimeSource`.
- Use `FaultInjector` to simulate realistic failure scenarios (intermittent errors, slow responses).
- Verify: retry attempts and delays, CB transitions, RL windows, BH limits, composition order, cancellation propagation, telemetry.

---

## Pattern Reference

Theoretical and technical deep-dive documentation for each pattern — definition, when to apply, configuration reference, and extended examples:

| Pattern | Document |
|---|---|
| Timeout | [docs/patterns/timeout.md](docs/patterns/timeout.md) |
| Retry | [docs/patterns/retry.md](docs/patterns/retry.md) |
| Circuit Breaker | [docs/patterns/circuit-breaker.md](docs/patterns/circuit-breaker.md) |
| Rate Limiter | [docs/patterns/rate-limiter.md](docs/patterns/rate-limiter.md) |
| Bulkhead | [docs/patterns/bulkhead.md](docs/patterns/bulkhead.md) |
| Hedging | [docs/patterns/hedging.md](docs/patterns/hedging.md) |
| Cache | [docs/patterns/cache.md](docs/patterns/cache.md) |
| Fallback | [docs/patterns/fallback.md](docs/patterns/fallback.md) |

→ [Full index with descriptions](docs/README.md)

---

## Contributing

Contributions are welcome. Please read [CONTRIBUTING.md](CONTRIBUTING.md) for development setup, coding guidelines, and the pull request process.

- [Code of Conduct](CODE_OF_CONDUCT.md)
- [Security policy](SECURITY.md)
- [Changelog](CHANGELOG.md)

This project is licensed under the [Apache License 2.0](LICENSE).

