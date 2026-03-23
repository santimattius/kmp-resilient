# Hedging

## Definition

The **Hedging** pattern reduces tail latency by issuing multiple redundant parallel requests and returning whichever one succeeds first, cancelling the remaining ones. It is a speculative execution technique: instead of waiting for a slow first attempt to time out before retrying, you launch additional attempts while the first is still running.

> "Don't wait for a slow response. Bet on multiple horses and take the first one to finish."

Hedging is particularly effective against the *long tail* of latency distributions — the p95 and p99 percentiles where a small fraction of requests are much slower than the median. Sending a second attempt after a short stagger dramatically reduces the probability of observing the tail.

---

## How It Works

1. Attempt 1 is launched immediately.
2. After `stagger` time, if attempt 1 has not yet completed, attempt 2 is launched.
3. After another `stagger`, attempt 3 is launched (if `attempts = 3`), and so on.
4. The first attempt that completes **successfully** has its result returned; all other in-flight attempts are cancelled.
5. If an attempt fails *before* a later attempt succeeds, that failure is noted but does not stop the other attempts.
6. If **all** attempts fail, the first failure is rethrown.

```
t=0ms   ──────── attempt 1 ──────────────────────► success? ─► return
t=50ms  ─────────────────── attempt 2 ──►success ─► return (cancels attempt 1)
t=100ms ──────────────────────────────── attempt 3 (launched but may be cancelled)
```

---

## When to Apply

| Scenario | Recommendation |
|---|---|
| Latency-sensitive, idempotent reads (profile lookup, config fetch) | **Recommended.** High p99 improvement at modest extra load. |
| Operations behind a CDN or multi-region endpoint | Hedging lets the fastest region win. |
| Database read replicas (multiple available) | Fire against multiple replicas, take the first response. |
| Non-idempotent writes (POST, payment, order creation) | **Do not use.** Multiple executions would create duplicate side-effects. |
| Operations that are cheap and fast | Hedging adds load without meaningful benefit. |
| Operations where extra load could trigger throttling | Use with caution; the extra requests count against your rate limits. |

**Key prerequisite:** the operation must be **safe to execute multiple times in parallel** (idempotent and side-effect free, or designed to tolerate concurrent calls with the same arguments).

---

## Configuration

| Property | Type | Default | Description |
|---|---|---|---|
| `attempts` | `Int` | `2` | Total number of parallel attempts to launch (must be ≥ 1). |
| `stagger` | `Duration` | — | Delay between consecutive attempt launches. Each subsequent attempt starts `stagger` after the previous one (cumulative). |

---

## Example

### Basic hedging — 2 attempts, 50 ms stagger

```kotlin
import com.santimattius.resilient.composition.resilient
import kotlin.time.Duration.Companion.milliseconds

val policy = resilient {
    hedging {
        attempts = 2
        stagger  = 50.milliseconds
    }
}

suspend fun fetchConfig(): Config = policy.execute {
    configService.get()
}
// If attempt 1 responds in < 50 ms → only 1 request sent.
// If attempt 1 is still pending at 50 ms → attempt 2 fires in parallel.
```

### Three attempts for high-p99 reduction

```kotlin
val policy = resilient {
    hedging {
        attempts = 3
        stagger  = 30.milliseconds
    }
}
// Attempt 1 at t=0, attempt 2 at t=30ms, attempt 3 at t=60ms
// Whichever responds first wins.
```

### Combined with Cache and Timeout

```kotlin
import kotlin.time.Duration.Companion.seconds

val policy = resilient {
    cache   { key = "user:profile"; ttl = 60.seconds }
    timeout { timeout = 500.milliseconds }
    hedging { attempts = 2; stagger = 50.milliseconds }
}
// Cache hit → no network call at all.
// Cache miss → up to 2 parallel attempts; timeout after 500 ms.
```

### Hedging with circuit breaker (protect downstream from amplified load)

```kotlin
val policy = resilient {
    circuitBreaker {
        failureThreshold = 5
        timeout          = 30.seconds
    }
    hedging {
        attempts = 2
        stagger  = 80.milliseconds
    }
}
// The circuit breaker prevents hedged calls from amplifying failures
// when the downstream is already struggling.
```

---

## Observing Hedging Events

```kotlin
import com.santimattius.resilient.telemetry.ResilientEvent

policy.events.collect { event ->
    if (event is ResilientEvent.HedgingUsed) {
        println("Hedging used — winning attempt index: ${event.attemptIndex}")
        metrics.increment("hedging.used", tag("attempt", event.attemptIndex))
    }
}
```

If `attemptIndex` is consistently > 0 in production, your p50 latency might be higher than expected and the stagger threshold may warrant adjustment.

---

## Latency vs Load Trade-off

Hedging trades **additional load** for **lower tail latency**:

- With `attempts = 2` and a stagger shorter than the p50, most requests are still served by the first attempt (no extra load). The second attempt only fires for the slow tail.
- With `attempts = 3`, more load is introduced but the p99 improvement is more aggressive.
- Monitor `HedgingUsed` frequency. If > 50% of requests trigger the second attempt, reduce the stagger or investigate why the p50 is high.

```
p50=20ms, stagger=50ms  →  most requests: 1 attempt (stagger never reached)
p95=80ms, stagger=50ms  →  ~5% of requests fire attempt 2 at 50ms
p99=200ms               →  p99 becomes ≈ max(50ms, time for attempt 2) ≈ 50+p50 ≈ 70ms
```

---

## Best Practices

- Only use hedging for **idempotent, side-effect-free** operations.
- Set `stagger` based on your latency SLO and p50. A common rule of thumb: stagger ≈ p75 of the operation.
- Combine with [Circuit Breaker](circuit-breaker.md) to prevent hedging from amplifying a degraded downstream.
- Combine with [Cache](cache.md) to avoid hedged calls entirely for cache-hot keys.
- Monitor `HedgingUsed` rate. A high rate signals either a consistently slow downstream or a stagger that is too short.
- Do not use hedging as a substitute for fixing slow operations; use it to reduce observable tail latency while addressing root causes separately.

---

## See Also

- [Cache](cache.md) — avoid calls entirely for recently seen results
- [Timeout](timeout.md) — enforce a hard deadline on each hedged attempt
- [Circuit Breaker](circuit-breaker.md) — protect downstream from amplified hedging load
- [Retry](retry.md) — sequential re-attempts vs parallel hedging
- [INDEX](../INDEX.md) — full pattern index
