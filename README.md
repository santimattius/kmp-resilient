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
- Fallback → Cache → Timeout → Retry → Circuit Breaker → Rate Limiter → Bulkhead → Hedging → Block
- Fallback wraps outermost to handle failures after all policies.

### Custom Composition Order
The composition order is configurable. Use `compositionOrder()` to specify a custom order:

```kotlin
import com.santimattius.resilient.composition.OrderablePolicyType

val policy = resilient(scope) {
    compositionOrder(listOf(
        OrderablePolicyType.CACHE,        // Check cache first (after Fallback)
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
- All orderable policy types (7 total) must be included in the custom order exactly once
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
```kotlin
import com.santimattius.resilient.retry.*

val policy = resilient {
    retry {
        maxAttempts = 4
        shouldRetry = { it is java.io.IOException }  // e.g. only retry IO; do not retry 4xx
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
→ [In-depth documentation](docs/patterns/retry.md)

### Circuit Breaker
Prevents hammering failing downstreams. States: CLOSED → OPEN → HALF_OPEN.
```kotlin
val policy = resilient {
    circuitBreaker {
        failureThreshold = 5
        successThreshold = 2
        halfOpenMaxCalls = 1
        timeout = 60.seconds // OPEN duration
    }
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
→ [In-depth documentation](docs/patterns/rate-limiter.md)

### Bulkhead
Limit concurrent executions and queued waiters; optional acquire timeout.
```kotlin
val policy = resilient {
    bulkhead {
        maxConcurrentCalls = 8
        maxWaitingCalls = 32
        timeout = 2.seconds
    }
}
```
→ [In-depth documentation](docs/patterns/bulkhead.md)

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

## Testing Notes
- Use kotlinx-coroutines-test for virtual time.
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

→ [Full index with descriptions](docs/patterns/INDEX.md)

