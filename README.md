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
- RetryAttempt(attempt, error)
- CircuitStateChanged(from, to)
- RateLimited(waitTime)
- OperationSuccess(duration) / OperationFailure(error, duration)

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

---

## Features

### Timeout
Aborts the operation after a configured duration. `onTimeout` runs only on actual timeouts.
```kotlin
val policy = resilient {
    timeout {
        timeout = 3.seconds
        onTimeout = { /* log metric */ }
    }
}
```

### Retry
Retries failing operations according to a backoff strategy and predicate.
```kotlin
import com.santimattius.resilient.retry.*

val policy = resilient {
    retry {
        maxAttempts = 4
        shouldRetry = { it is java.io.IOException }
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

### Rate Limiter
Token-bucket limiting, optional max wait when limited.
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
val policy = resilient {
    bulkhead {
        maxConcurrentCalls = 8
        maxWaitingCalls = 32
        timeout = 2.seconds
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
Cache successful results per key with TTL.
```kotlin
val policy = resilient {
    cache {
        key = "users:123"
        ttl = 30.seconds
    }
}
```

### Fallback
Return a fallback value when the operation fails.
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
- Rate limiter for external APIs; Bulkhead for database or threadpool protection.
- Hedging reduces tail latency but raises load—use selectively.
- Cache only idempotent reads; set TTL appropriately.
- Always observe telemetry for visibility and tuning.

## Testing Notes
- Use kotlinx-coroutines-test for virtual time.
- Verify: retry attempts and delays, CB transitions, RL windows, BH limits, composition order, cancellation propagation, telemetry.

