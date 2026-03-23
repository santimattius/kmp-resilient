# Circuit Breaker

## Definition

The **Circuit Breaker** pattern is a fault-detection mechanism inspired by electrical engineering. It monitors calls to a downstream dependency and, when the failure rate exceeds a threshold, *opens* the circuit — rejecting all subsequent calls immediately without even attempting to reach the downstream. After a configurable recovery period it enters a *half-open* state to probe whether the dependency has recovered.

> "Fail fast. Give the downstream time to heal. Probe recovery. Close when ready."

Without a circuit breaker, a series of slow or failing calls can cascade: threads pile up waiting for timeouts, retry storms amplify the load, and the caller is degraded for the entire duration. The circuit breaker short-circuits this feedback loop.

---

## State Machine

```
         failures >= failureThreshold
CLOSED ─────────────────────────────────────────► OPEN
  ▲                                                 │
  │  successes >= successThreshold                  │ timeout (recovery period) expires
  │                                                 ▼
  └───────────────────────────────────────── HALF_OPEN
                                                    │
                                              failure occurs
                                                    │
                                                    ▼
                                                  OPEN (reset timeout)
```

| State | Behaviour |
|---|---|
| **CLOSED** | Normal operation. Failures are counted. |
| **OPEN** | All calls are rejected immediately with `CircuitBreakerOpenException`. |
| **HALF_OPEN** | A limited number of probe calls (`halfOpenMaxCalls`) are allowed through to test recovery. |

---

## How It Works

1. **CLOSED** — every call executes normally. Each failure increments the failure counter.
2. When `failureCount >= failureThreshold` → transitions to **OPEN** and starts the recovery timer.
3. **OPEN** — calls are rejected without executing the block. The `CircuitBreakerOpenException` is thrown immediately.
4. After `timeout` elapses → transitions to **HALF_OPEN**.
5. **HALF_OPEN** — up to `halfOpenMaxCalls` attempts are allowed.
   - All succeed (>= `successThreshold`) → transitions back to **CLOSED**. Counters reset.
   - Any failure → transitions back to **OPEN**. Recovery timer resets.

---

## When to Apply

| Scenario | Recommendation |
|---|---|
| Calls to external HTTP / gRPC services | **Recommended.** Prevents cascade failures. |
| Database connections | **Recommended.** Avoids overwhelming a struggling database. |
| Message broker / event bus publishing | Useful when the broker is occasionally overloaded. |
| Internal in-process calls | Generally not needed unless they call external resources. |
| One-shot operations where fast-fail is acceptable | Circuit breaker is a good fit. |
| Operations that must always try (e.g. audit logging) | Consider a fallback or queue-based approach instead. |

**When NOT to use:**
- Do not use circuit breaker as a substitute for proper capacity planning.
- Avoid very low thresholds (e.g. `failureThreshold = 1`) in noisy systems — you will get false-positive opens.

---

## Configuration

| Property | Type | Default | Description |
|---|---|---|---|
| `failureThreshold` | `Int` | `5` | Number of consecutive failures in CLOSED state before opening. |
| `successThreshold` | `Int` | `2` | Number of consecutive successes in HALF_OPEN state before closing. |
| `timeout` | `Duration` | `60.seconds` | How long to stay OPEN before transitioning to HALF_OPEN. |
| `halfOpenMaxCalls` | `Int` | `3` | Maximum probe calls allowed while HALF_OPEN. |
| `shouldRecordFailure` | `(Throwable) -> Boolean` | `true` for all exceptions | Predicate to decide whether a given exception counts as a failure. Use this to exclude 4xx errors from the failure counter. |
| `onStateChange` | `(from: CircuitState, to: CircuitState) -> Unit` | no-op | Callback invoked on every state transition. |

---

## Example

### Basic circuit breaker

```kotlin
import com.santimattius.resilient.composition.resilient
import kotlin.time.Duration.Companion.seconds

val policy = resilient {
    circuitBreaker {
        failureThreshold  = 5
        successThreshold  = 2
        halfOpenMaxCalls  = 1
        timeout           = 30.seconds
    }
}

suspend fun fetchProfile(id: String): Profile = policy.execute {
    profileService.get(id)
}
```

### Exclude non-transient errors from the failure counter

```kotlin
val policy = resilient {
    circuitBreaker {
        failureThreshold     = 5
        successThreshold     = 2
        timeout              = 60.seconds
        shouldRecordFailure  = { error ->
            // 4xx client errors should NOT open the circuit
            error !is ClientException || error.statusCode >= 500
        }
    }
}
```

### Logging state transitions

```kotlin
val policy = resilient {
    circuitBreaker {
        failureThreshold = 5
        successThreshold = 2
        timeout          = 30.seconds
        onStateChange    = { from, to ->
            logger.warn("Circuit breaker: $from → $to")
            metrics.gauge("circuit_breaker.state", circuitStateToInt(to))
        }
    }
}
```

### Combined with Retry and Timeout

```kotlin
import com.santimattius.resilient.retry.ExponentialBackoff
import kotlin.time.Duration.Companion.milliseconds

val policy = resilient {
    timeout        { timeout = 5.seconds }
    retry          {
        maxAttempts = 3
        backoffStrategy = ExponentialBackoff(initialDelay = 100.milliseconds)
        shouldRetry = { it is IOException }
    }
    circuitBreaker {
        failureThreshold = 5
        successThreshold = 2
        timeout          = 30.seconds
    }
}
```

---

## Health & Readiness Probes

The circuit breaker state is exposed via `getHealthSnapshot()`, making it a natural source for health endpoints (Kubernetes readiness probes, `/health` routes):

```kotlin
import com.santimattius.resilient.circuitbreaker.CircuitState

val snapshot = policy.getHealthSnapshot()

val isHealthy = snapshot.circuitBreaker?.state != CircuitState.OPEN

// Use in a Ktor route:
get("/health") {
    val snap = policy.getHealthSnapshot()
    val ready = snap.circuitBreaker?.state != CircuitState.OPEN
    call.respond(if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable)
}
```

---

## Observing Circuit Breaker Events

```kotlin
import com.santimattius.resilient.telemetry.ResilientEvent

policy.events.collect { event ->
    if (event is ResilientEvent.CircuitStateChanged) {
        println("Circuit: ${event.from} → ${event.to}")
    }
}
```

---

## Best Practices

- Tune `failureThreshold` and `timeout` based on your dependency's typical recovery time. A too-short open period may result in repeated half-open probing.
- Use `shouldRecordFailure` to exclude expected client errors (4xx). Otherwise, badly formatted requests will trip the circuit.
- Log and alert on CLOSED → OPEN transitions. They signal that a downstream dependency is degraded.
- Combine Circuit Breaker with [Retry](retry.md) (Retry inside, Circuit Breaker outside): retries attempt recovery from transient errors; the circuit breaker stops the attempts when the dependency is clearly down.
- Monitor circuit state in your observability stack via `onStateChange` or `policy.events`.
- Expose circuit state on `/health` or `/readiness` endpoints for orchestration platforms.

---

## See Also

- [Retry](retry.md) — retry transient failures before the circuit opens
- [Timeout](timeout.md) — prevent slow calls from blocking indefinitely
- [Fallback](fallback.md) — return a safe value when the circuit is open
- [INDEX](../INDEX.md) — full pattern index
