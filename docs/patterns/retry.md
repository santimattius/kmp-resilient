# Retry

## Definition

The **Retry** pattern automatically re-executes a failed operation a configurable number of times before giving up. It assumes that some failures are *transient* — momentary network hiccups, brief resource contention, transient 5xx errors — and that repeating the call shortly after the failure may succeed.

> "A transient failure is one that disappears on its own. A permanent failure does not."

Retry must always be paired with a **shouldRetry predicate** to avoid amplifying non-transient errors (e.g. repeating a 400 Bad Request only wastes resources and time). It is complemented by back-off strategies that space retries apart to avoid hammering an already-stressed system.

---

## How It Works

1. The block is executed for the first time (attempt 1 of N).
2. On success → result is returned immediately.
3. On failure → `shouldRetry(error)` is evaluated.
   - If `false` → the error propagates without retrying.
   - If `true` → the policy waits for the back-off delay, then tries again.
4. After `maxAttempts` failures the last exception propagates to the caller.
5. If `perAttemptTimeout` is set, each individual attempt is cancelled if it exceeds the per-attempt limit.

---

## Back-off Strategies

| Strategy | Formula | Use Case |
|---|---|---|
| `ExponentialBackoff` | `delay = min(maxDelay, initialDelay × factor^(attempt−1))` | General purpose. Reduces contention by spreading retries apart. |
| `LinearBackoff` | `delay = initialDelay × attempt` | When linear growth in wait time is preferable. |
| `FixedBackoff` | `delay = constant` | When the downstream recovery time is predictable. |

`ExponentialBackoff` supports **jitter** (randomized ±delay), which is strongly recommended in high-concurrency scenarios to prevent *retry storms* where many callers retry at exactly the same moment.

---

## When to Apply

| Scenario | Recommendation |
|---|---|
| Network I/O (HTTP, gRPC, TCP) | **Recommended.** Transient connection errors are common. |
| Database reads (SELECT) | **Recommended.** Connection pool exhaustion and deadlocks can be transient. |
| External API calls with 429 / 503 responses | Retry with back-off; respect `Retry-After` headers if possible. |
| Idempotent write operations (PUT, PATCH with natural key) | Safe to retry; ensure server-side idempotency. |
| Non-idempotent writes (POST, financial transactions) | **Avoid** unless the server guarantees idempotency (e.g. idempotency key). |
| 4xx client errors (400, 401, 403, 404, 409) | **Do not retry** — they indicate a permanent error in the request itself. |
| CPU-bound local computation | Retrying will rarely help; fix the bug instead. |

---

## Configuration

| Property | Type | Default | Description |
|---|---|---|---|
| `maxAttempts` | `Int` | `3` | Total number of attempts (first call + retries). |
| `backoffStrategy` | `BackoffStrategy` | `ExponentialBackoff()` | Strategy for computing the delay between attempts. |
| `shouldRetry` | `(Throwable) -> Boolean` | `true` for all exceptions | Predicate that decides whether a given error should trigger a retry. |
| `perAttemptTimeout` | `Duration?` | `null` | If set, each individual attempt is cancelled if it exceeds this duration. Independent of the global [Timeout](timeout.md) policy. |
| `onRetry` | `suspend (attempt: Int, error: Throwable) -> Unit` | no-op | Callback invoked before each retry (not before the first attempt). |

---

## Example

### Basic retry with exponential back-off

```kotlin
import com.santimattius.resilient.composition.resilient
import com.santimattius.resilient.retry.ExponentialBackoff
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

val policy = resilient {
    retry {
        maxAttempts = 4
        backoffStrategy = ExponentialBackoff(
            initialDelay = 200.milliseconds,
            maxDelay     = 5.seconds,
            factor       = 2.0,
            jitter       = true
        )
    }
}

suspend fun loadData(): Data = policy.execute { remoteApi.fetchData() }
```

### Selective retry — only transient errors

```kotlin
import java.io.IOException

val policy = resilient {
    retry {
        maxAttempts = 3
        shouldRetry = { error ->
            error is IOException || error is ServiceUnavailableException
            // Do NOT retry ClientErrorException (4xx)
        }
        backoffStrategy = ExponentialBackoff(initialDelay = 100.milliseconds)
    }
}
```

### Per-attempt timeout

```kotlin
val policy = resilient {
    retry {
        maxAttempts = 3
        perAttemptTimeout = 2.seconds     // each attempt has its own 2 s limit
        backoffStrategy = ExponentialBackoff(initialDelay = 100.milliseconds)
    }
}
// Total wall-clock time: up to (3 × 2 s) + back-off delays
```

### With a metrics callback

```kotlin
val policy = resilient {
    retry {
        maxAttempts = 3
        onRetry = { attempt, error ->
            metrics.increment("retry.attempt", tag("attempt", attempt), tag("error", error::class.simpleName))
        }
        backoffStrategy = ExponentialBackoff(initialDelay = 200.milliseconds)
    }
}
```

### Combined with Circuit Breaker and Timeout

```kotlin
val policy = resilient {
    timeout        { timeout = 5.seconds }
    retry          { maxAttempts = 3; backoffStrategy = ExponentialBackoff(initialDelay = 100.milliseconds) }
    circuitBreaker { failureThreshold = 5; successThreshold = 2; timeout = 30.seconds }
}
```

---

## Timeout × Retry Interaction

This is the most important composition decision for Retry:

| Order | Behaviour |
|---|---|
| **Timeout outer, Retry inner** (default) | Single budget shared across ALL attempts. One slow attempt can consume the entire timeout, leaving no budget for subsequent retries. |
| **Retry outer, Timeout inner** | Each attempt has its own deadline. You can fit more attempts within the same total wall-clock time. |

```kotlin
// Default: single global timeout
// compositionOrder: Timeout → Retry → …
val policy = resilient {
    timeout { timeout = 6.seconds }
    retry   { maxAttempts = 3 }
}

// Custom: per-attempt timeout via composition order
import com.santimattius.resilient.composition.OrderablePolicyType

val policy = resilient {
    compositionOrder(listOf(
        OrderablePolicyType.CACHE,
        OrderablePolicyType.RETRY,          // outer
        OrderablePolicyType.TIMEOUT,        // inner — each attempt gets its own 2 s
        OrderablePolicyType.CIRCUIT_BREAKER,
        OrderablePolicyType.RATE_LIMITER,
        OrderablePolicyType.BULKHEAD,
        OrderablePolicyType.HEDGING
    ))
    timeout { timeout = 2.seconds }
    retry   { maxAttempts = 3 }
}
```

Alternatively, use `perAttemptTimeout` directly in the retry config to avoid manually reordering policies.

---

## Observing Retry Events

```kotlin
import com.santimattius.resilient.telemetry.ResilientEvent

policy.events.collect { event ->
    if (event is ResilientEvent.RetryAttempt) {
        println("Retry attempt ${event.attempt}: ${event.error.message}")
    }
}
```

---

## Best Practices

- **Always configure `shouldRetry`** in production code. The default (`true` for all errors) will retry 4xx errors, which is almost never correct.
- **Use jitter** with `ExponentialBackoff` to avoid retry storms.
- Keep `maxAttempts` small (2–4). Large values extend the blast radius of a partially failed request.
- Monitor retry rates via telemetry. A spike in retries is an early warning sign of downstream degradation.
- Combine with [Circuit Breaker](circuit-breaker.md): when retries keep failing, the circuit opens and stops the cascade.
- For non-idempotent operations, implement an idempotency key before adding Retry.

---

## See Also

- [Timeout](timeout.md) — global or per-attempt deadline
- [Circuit Breaker](circuit-breaker.md) — stop retrying when a dependency is down
- [Fallback](fallback.md) — produce a safe value after all retries are exhausted
- [INDEX](../INDEX.md) — full pattern index
