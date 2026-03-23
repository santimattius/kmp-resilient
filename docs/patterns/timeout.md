# Timeout

## Definition

The **Timeout** pattern enforces a hard deadline on any operation. If the operation does not complete within the configured duration the execution is cancelled and the caller receives a failure signal. This prevents a slow dependency from blocking a thread (or coroutine) indefinitely, which would otherwise cascade into resource exhaustion across the whole system.

> "A system that can wait forever will eventually wait forever."

Timeout is one of the most fundamental resilience patterns. It is the first line of defence against latency blow-ups and is commonly composed with [Retry](retry.md) and [Circuit Breaker](circuit-breaker.md) for a layered safety net.

---

## How It Works

1. The operation block is started.
2. A coroutine-level deadline (`kotlinx.coroutines.withTimeout`) is set.
3. If the block finishes before the deadline → the result is returned normally.
4. If the deadline expires → the coroutine is cancelled, the optional `onTimeout` callback fires (e.g. to record a metric), and a `TimeoutCancellationException` propagates to the caller.

In the composition pipeline, Timeout sits **outside** Retry by default, meaning the deadline covers all retry attempts combined. If you need a deadline *per attempt*, use `perAttemptTimeout` in [Retry](retry.md) or swap the composition order.

---

## When to Apply

| Scenario | Recommendation |
|---|---|
| Calling any external HTTP / gRPC / database endpoint | **Always** set a timeout |
| Operations where partial results are not safe | Timeout to fail fast and let [Fallback](fallback.md) return a safe default |
| SLA-bound flows (e.g. a user-facing API must respond in < 500 ms) | Set timeout slightly below SLA budget |
| Background jobs without latency requirements | A timeout may still be useful to prevent runaway tasks |
| Operations that are **not** cancellable (e.g. blocking I/O without dispatcher bridging) | Timeout will not help; fix the blocking call first |

**When NOT to use:**
- Do not rely solely on timeout when the downstream has its own keep-alive or long-polling semantics. Set the timeout slightly above the longest acceptable response time, not just below the SLA.

---

## Configuration

| Property | Type | Default | Description |
|---|---|---|---|
| `timeout` | `Duration` | `30.seconds` | Maximum allowed duration for the block. |
| `onTimeout` | `suspend () -> Unit` | no-op | Callback invoked only when the timeout actually triggers (not on normal completion). Useful for recording metrics. |

---

## Example

### Basic usage

```kotlin
import com.santimattius.resilient.composition.resilient
import kotlin.time.Duration.Companion.seconds

val policy = resilient {
    timeout {
        timeout = 3.seconds
    }
}

suspend fun fetchUser(id: String): User = policy.execute {
    userService.get(id) // cancelled if it takes more than 3 s
}
```

### With a metrics callback

```kotlin
val policy = resilient {
    timeout {
        timeout = 2.seconds
        onTimeout = {
            metrics.increment("timeout.triggered", tag("operation", "fetchUser"))
        }
    }
}
```

### Global timeout wrapping retries (default)

```kotlin
// Timeout is outside Retry → 5 s budget shared across ALL attempts
val policy = resilient {
    timeout { timeout = 5.seconds }
    retry  { maxAttempts = 3 }
}
```

### Per-attempt timeout via Retry

```kotlin
import com.santimattius.resilient.retry.ExponentialBackoff
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// Each individual attempt has its own 2 s limit
val policy = resilient {
    retry {
        maxAttempts = 3
        perAttemptTimeout = 2.seconds
        backoffStrategy = ExponentialBackoff(initialDelay = 100.milliseconds)
    }
}
```

### Custom composition order (Retry outer, Timeout inner)

```kotlin
import com.santimattius.resilient.composition.OrderablePolicyType

val policy = resilient {
    compositionOrder(listOf(
        OrderablePolicyType.CACHE,
        OrderablePolicyType.RETRY,          // Retry is now outer…
        OrderablePolicyType.TIMEOUT,        // …Timeout is inner (per-attempt)
        OrderablePolicyType.CIRCUIT_BREAKER,
        OrderablePolicyType.RATE_LIMITER,
        OrderablePolicyType.BULKHEAD,
        OrderablePolicyType.HEDGING
    ))
    timeout { timeout = 2.seconds }
    retry   { maxAttempts = 3 }
}
```

---

## Observing Timeout Events

```kotlin
import com.santimattius.resilient.telemetry.ResilientEvent

policy.events.collect { event ->
    if (event is ResilientEvent.TimeoutTriggered) {
        println("Timeout after ${event.timeout}")
    }
}
```

---

## Best Practices

- **Always set a timeout** on calls to external services. The default (30 s) is a safety net, not a tuned value.
- Set timeout to a value derived from your SLA budget, not an arbitrary large number.
- Use `onTimeout` to emit a metric so you can track timeout rates in your observability platform.
- Prefer `perAttemptTimeout` inside Retry when you want each attempt to have its own deadline and a set number of attempts to fit within a budget.
- Remember that `TimeoutCancellationException` is a subtype of `CancellationException`. Code that catches `CancellationException` will also catch timeout errors.

---

## See Also

- [Retry](retry.md) — re-execute on failure, with optional `perAttemptTimeout`
- [Circuit Breaker](circuit-breaker.md) — stop calling a downstream that is already failing
- [Fallback](fallback.md) — produce a safe value after timeout propagates
- [INDEX](../INDEX.md) — full pattern index
