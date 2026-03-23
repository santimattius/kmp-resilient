# Rate Limiter

## Definition

The **Rate Limiter** pattern controls how frequently an operation may be executed within a given time window. It acts as a governor between a caller and a downstream resource, ensuring that the call rate never exceeds a configured maximum. This protects third-party APIs with quota contracts, internal services with known capacity ceilings, and prevents individual callers from monopolising shared resources.

> "Rate limiting is the art of knowing when to wait rather than when to fail."

---

## Algorithm — Token Bucket (Fixed-Window Refill)

Resilient's rate limiter uses a **token-bucket** with **fixed-window refill**:

1. The bucket starts with `maxCalls` tokens.
2. Each call consumes one token.
3. At the **start of each new period**, the bucket is refilled to `maxCalls`.
4. If no token is available:
   - If `timeoutWhenLimited` is `null` → `RateLimitExceededException` is thrown immediately.
   - If `timeoutWhenLimited` is set → the caller waits until the next refill (up to the configured limit), then consumes a token.

**Important characteristic:** tokens refill all at once at the boundary (not continuously). With `maxCalls = 10, period = 1.second`, all 10 tokens are available at second 0, 1, 2, … Ten calls issued at 0.999 s plus ten calls at 1.001 s will both succeed — this is the nature of fixed-window refill and should be accounted for when setting thresholds.

```
Period boundary:  0 s          1 s          2 s
                  │            │            │
Tokens:           ████████████ ████████████ ████████████  (refill to maxCalls)
Consumed:         ████████──── ██████──────              (6 used, 4 remaining)
```

---

## When to Apply

| Scenario | Recommendation |
|---|---|
| Calling a third-party API with a quota (e.g. 100 req/min) | **Recommended.** Set `maxCalls`/`period` to match or stay below the quota. |
| Protecting a shared microservice from a noisy caller | **Recommended.** Enforce per-client rate limits. |
| SMS / email sending (prevent spam) | Useful with longer periods (e.g. 5 emails per hour). |
| Accessing a database connection pool with limited connections | Consider [Bulkhead](bulkhead.md) instead — it limits concurrency, not rate. |
| Internal in-memory operations with no capacity concern | Not needed. |
| Operations where waiting is acceptable | Use `timeoutWhenLimited` to queue callers instead of failing them. |

---

## Configuration

| Property | Type | Default | Description |
|---|---|---|---|
| `maxCalls` | `Int` | `100` | Maximum number of calls allowed per `period`. |
| `period` | `Duration` | `1.seconds` | Duration of each rate-limiting window. |
| `timeoutWhenLimited` | `Duration?` | `null` | If set, a call that finds no available token will wait up to this duration for the next refill before throwing. If `null`, the exception is thrown immediately. |
| `onRateLimited` | `suspend () -> Unit` | no-op | Callback invoked each time a call is rate-limited (whether it waits or fails). Useful for metrics. |

---

## Example

### Basic rate limiter — 50 calls per second

```kotlin
import com.santimattius.resilient.composition.resilient
import kotlin.time.Duration.Companion.seconds

val policy = resilient {
    rateLimiter {
        maxCalls = 50
        period   = 1.seconds
    }
}

suspend fun search(query: String): List<Result> = policy.execute {
    searchApi.query(query)
}
```

### Wait for the next window instead of failing immediately

```kotlin
val policy = resilient {
    rateLimiter {
        maxCalls           = 10
        period             = 1.seconds
        timeoutWhenLimited = 5.seconds   // wait up to 5 s for a token
    }
}
```

### With a metrics callback

```kotlin
val policy = resilient {
    rateLimiter {
        maxCalls       = 100
        period         = 1.seconds
        onRateLimited  = {
            metrics.increment("rate_limiter.rejected")
        }
    }
}
```

### Third-party API with minute-level quota

```kotlin
import kotlin.time.Duration.Companion.minutes

val policy = resilient {
    rateLimiter {
        maxCalls           = 60          // 60 calls per minute
        period             = 1.minutes
        timeoutWhenLimited = 30.seconds  // queue callers for up to 30 s
        onRateLimited      = { logger.warn("Rate limited — waiting for next minute window") }
    }
}
```

### Combined with Circuit Breaker and Bulkhead

```kotlin
val policy = resilient {
    circuitBreaker { failureThreshold = 5; timeout = 30.seconds }
    rateLimiter    { maxCalls = 50; period = 1.seconds }
    bulkhead       { maxConcurrentCalls = 10; maxWaitingCalls = 50 }
}
```

---

## Observing Rate Limiter Events

```kotlin
import com.santimattius.resilient.telemetry.ResilientEvent

policy.events.collect { event ->
    if (event is ResilientEvent.RateLimited) {
        println("Rate limited — suggested wait: ${event.waitTime}")
    }
}
```

---

## Rate Limiter vs Bulkhead

| Dimension | Rate Limiter | Bulkhead |
|---|---|---|
| Controls | Call **rate** (per period) | **Concurrency** (simultaneous active calls) |
| Typical use | External APIs with quota | Threadpool / DB connection protection |
| Queuing | Optional wait for next period | Optional wait for a permit |
| Metric | Calls/second | Active concurrent calls |

Use Rate Limiter when you care about *how many calls per unit of time*. Use [Bulkhead](bulkhead.md) when you care about *how many calls are running at the same time*.

---

## Best Practices

- Set `maxCalls` and `period` to values slightly below the actual downstream quota to leave a safety margin.
- Use `onRateLimited` to emit a metric. A sustained rate-limiting signal means your callers are outpacing your quota — time to either increase limits or redesign the calling pattern.
- If your workload is bursty, prefer `timeoutWhenLimited` so callers queue briefly rather than receiving an immediate error.
- One policy instance = one bucket (global across all coroutines using that policy). For per-user limits, create separate policy instances per user scope.
- For external APIs that return `429 Too Many Requests`, combine rate limiter (preventive) with [Retry](retry.md) using `shouldRetry` to handle the occasional 429 that slips through.

---

## See Also

- [Bulkhead](bulkhead.md) — concurrency limiting
- [Circuit Breaker](circuit-breaker.md) — stop calling a failing downstream
- [Retry](retry.md) — retry after a 429 / transient failure
- [INDEX](../INDEX.md) — full pattern index
