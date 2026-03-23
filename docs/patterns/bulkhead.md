# Bulkhead

## Definition

The **Bulkhead** pattern isolates resources used by different operations so that a failure or overload in one area does not cascade into other areas. The name comes from the watertight compartments in a ship's hull: if one compartment floods, the rest of the ship remains afloat.

In software, Bulkhead limits the number of concurrent executions of an operation and the size of its waiting queue. When both limits are reached, new callers are rejected immediately (or after a brief wait), protecting the shared resource — a database connection pool, an HTTP thread pool, a downstream service — from total exhaustion.

> "Compartmentalize your resources. Let one component fail without sinking the whole system."

---

## How It Works

1. A **semaphore** with `maxConcurrentCalls` permits is created at policy construction time.
2. An incoming call attempts to **acquire a permit**:
   - If a permit is available → the block runs immediately.
   - If no permit is available and the waiting queue (`maxWaitingCalls`) has room → the call waits.
   - If the queue is also full → `BulkheadFullException` is thrown immediately.
3. If `timeout` is configured, a waiting call that does not acquire a permit within the timeout receives `BulkheadFullException`.
4. When the block finishes (success or failure), the permit is **released**, allowing the next waiting call to proceed.

```
Incoming calls ──►  [ waiting queue (maxWaitingCalls) ]  ──►  [ active slots (maxConcurrentCalls) ]  ──►  block
                                                                                                   ◄──  release
```

---

## When to Apply

| Scenario | Recommendation |
|---|---|
| Database connection pool with a fixed size | **Recommended.** Set `maxConcurrentCalls` ≤ pool size. |
| Calling a slow external service with limited threads | **Recommended.** Prevents thread starvation. |
| Background job workers with limited parallelism | Useful to cap maximum parallelism without using a fixed dispatcher. |
| Fan-out operations (e.g. N parallel requests) | Bulkhead caps concurrency across all of them. |
| Fast in-memory operations | Not needed — they complete so quickly that contention is negligible. |
| Controlling call **rate** (calls/second) | Use [Rate Limiter](rate-limiter.md) instead — Bulkhead controls concurrency, not rate. |

---

## Configuration

| Property | Type | Default | Description |
|---|---|---|---|
| `maxConcurrentCalls` | `Int` | `10` | Maximum number of blocks executing simultaneously. |
| `maxWaitingCalls` | `Int` | `100` | Maximum number of callers that may wait for a permit. |
| `timeout` | `Duration?` | `null` | If set, a waiting caller that does not acquire a permit within this duration receives `BulkheadFullException`. If `null`, callers wait indefinitely until a permit is released or the queue is full. |

---

## Example

### Basic bulkhead

```kotlin
import com.santimattius.resilient.composition.resilient
import kotlin.time.Duration.Companion.seconds

val policy = resilient {
    bulkhead {
        maxConcurrentCalls = 8
        maxWaitingCalls    = 32
    }
}

suspend fun queryDatabase(sql: String): ResultSet = policy.execute {
    database.execute(sql)
}
```

### With acquire timeout

```kotlin
val policy = resilient {
    bulkhead {
        maxConcurrentCalls = 10
        maxWaitingCalls    = 50
        timeout            = 2.seconds   // give up after 2 s waiting for a slot
    }
}
```

### Tight bulkhead for a critical resource

```kotlin
val policy = resilient {
    bulkhead {
        maxConcurrentCalls = 3    // only 3 concurrent calls allowed
        maxWaitingCalls    = 0    // no waiting — fail immediately if all slots are taken
        timeout            = null
    }
}
```

### Combined with Rate Limiter and Circuit Breaker

```kotlin
val policy = resilient {
    circuitBreaker { failureThreshold = 5; timeout = 30.seconds }
    rateLimiter    { maxCalls = 50; period = 1.seconds }
    bulkhead       { maxConcurrentCalls = 10; maxWaitingCalls = 100; timeout = 2.seconds }
}
```

---

## Health & Readiness Probes

Bulkhead usage metrics are available via `getHealthSnapshot()`:

```kotlin
val snapshot = policy.getHealthSnapshot()

snapshot.bulkhead?.let { bh ->
    println("Active calls : ${bh.activeConcurrentCalls} / ${bh.maxConcurrentCalls}")
    println("Waiting calls: ${bh.waitingCalls} / ${bh.maxWaitingCalls}")
}
```

Use this to expose saturation metrics in your observability platform or to drive autoscaling decisions.

```kotlin
// Example: Ktor health endpoint
get("/health") {
    val bh = policy.getHealthSnapshot().bulkhead
    val saturated = bh != null && bh.activeConcurrentCalls >= bh.maxConcurrentCalls
    call.respond(if (saturated) HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK)
}
```

---

## Observing Bulkhead Events

```kotlin
import com.santimattius.resilient.telemetry.ResilientEvent

policy.events.collect { event ->
    if (event is ResilientEvent.BulkheadRejected) {
        println("Bulkhead rejected: ${event.reason}")
        metrics.increment("bulkhead.rejected")
    }
}
```

---

## Bulkhead vs Rate Limiter

| Dimension | Bulkhead | Rate Limiter |
|---|---|---|
| Controls | **Concurrent** active calls | Call **rate** (per period) |
| Analogous to | Connection pool | API quota governor |
| Typical metric | `activeConcurrentCalls` | `callsPerSecond` |
| Failure model | Rejects when queue is full | Rejects (or waits) when quota is exhausted |

Use Bulkhead when you need to cap *how many operations run at the same time*. Use [Rate Limiter](rate-limiter.md) when you need to cap *how many operations are called per unit of time*.

---

## Sizing Guidance

- **`maxConcurrentCalls`**: match (or be slightly less than) the capacity of the resource you are protecting. For a DB pool of 20 connections, `maxConcurrentCalls = 15` leaves headroom for administrative connections.
- **`maxWaitingCalls`**: reflect acceptable back-pressure. A large waiting queue acts as a buffer; a small queue fails fast. For interactive user requests, prefer a small queue with a [Fallback](fallback.md).
- **`timeout`**: set to a value that allows brief spikes to be absorbed but prevents indefinite waiting. Match or be lower than your overall request SLA.

---

## Best Practices

- Monitor `BulkheadRejected` events in production. Frequent rejections signal that `maxConcurrentCalls` needs to increase or the downstream resource needs scaling.
- Use separate policy instances per resource type (DB, HTTP client, message broker) so that saturation in one does not affect others.
- Combine with [Fallback](fallback.md) to handle `BulkheadFullException` gracefully instead of surfacing a 503 to the user.
- Avoid very large waiting queues (`maxWaitingCalls` ≫ `maxConcurrentCalls`); waiting callers still hold application memory and file descriptors.

---

## See Also

- [Rate Limiter](rate-limiter.md) — rate-based throttling
- [Circuit Breaker](circuit-breaker.md) — stop calling a failing dependency
- [Fallback](fallback.md) — graceful degradation when bulkhead rejects
- [INDEX](../INDEX.md) — full pattern index
