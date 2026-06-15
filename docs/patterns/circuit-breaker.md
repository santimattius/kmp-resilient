# Circuit Breaker

## Definition

The **Circuit Breaker** pattern is a fault-detection mechanism inspired by electrical engineering. It monitors calls to a downstream dependency and, when the failure rate exceeds a threshold, *opens* the circuit — rejecting all subsequent calls immediately without even attempting to reach the downstream. After a configurable recovery period it enters a *half-open* state to probe whether the dependency has recovered.

> "Fail fast. Give the downstream time to heal. Probe recovery. Close when ready."

Without a circuit breaker, a series of slow or failing calls can cascade: threads pile up waiting for timeouts, retry storms amplify the load, and the caller is degraded for the entire duration. The circuit breaker short-circuits this feedback loop.

---

## State Machine

```
         failures >= failureThreshold (or failure rate >= failureRateThreshold)
CLOSED ─────────────────────────────────────────────────────────────────────► OPEN
  ▲                                                                             │
  │  successes >= successThreshold                                              │ timeout (recovery period) expires
  │                                                                             ▼
  └─────────────────────────────────────────────────────────────────────── HALF_OPEN
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

## Trip Modes

The circuit breaker supports three mutually exclusive trip modes:

| Mode | Configuration | Opens when |
|---|---|---|
| **Consecutive failures** (default) | `failureThreshold` only | `failureThreshold` consecutive failures occur (a success resets the count). |
| **Time-based sliding window** | `slidingWindow` | `failureThreshold` failures fall within the `slidingWindow` duration. |
| **Failure-rate ring buffer** | `failureRateThreshold` | Failure rate over the last `minimumNumberOfCalls` outcomes meets or exceeds `failureRateThreshold` %. |

`slidingWindow` and `failureRateThreshold` are mutually exclusive — combining both throws `IllegalArgumentException` at construction time.

---

## Configuration

| Property | Type | Default | Description |
|---|---|---|---|
| `failureThreshold` | `Int` | `5` | Number of consecutive failures in CLOSED state before opening (consecutive mode). Also the failure count threshold for other modes. |
| `successThreshold` | `Int` | `2` | Number of consecutive successes in HALF_OPEN state before closing. |
| `timeout` | `Duration` | `60.seconds` | How long to stay OPEN before transitioning to HALF_OPEN. |
| `halfOpenMaxCalls` | `Int` | `3` | Maximum probe calls allowed while HALF_OPEN. |
| `shouldRecordFailure` | `(Throwable) -> Boolean` | `true` for all | Predicate to decide whether a given exception counts as a failure. Use this to exclude 4xx errors from the failure counter. |
| `shouldRecordResult` | `((Any?) -> Boolean)?` | `null` | When set, successful return values are evaluated; if `true`, the result counts as a failure (while still being returned to the caller). Independent of `shouldRecordFailure`. |
| `slidingWindow` | `Duration?` | `null` | Enables time-based mode. The circuit opens when `failureThreshold` failures occur within this window. Cannot be combined with `failureRateThreshold`. |
| `failureRateThreshold` | `Double?` | `null` | Enables failure-rate mode. Opens when failure rate over the last `minimumNumberOfCalls` outcomes meets or exceeds this percentage (0.0–100.0). Cannot be combined with `slidingWindow`. |
| `minimumNumberOfCalls` | `Int` | `10` | Minimum outcomes before `failureRateThreshold` is evaluated. Also defines the ring-buffer size. Only relevant when `failureRateThreshold` is set. |
| `onStateChange` | `(from, to) -> Unit` | no-op | Callback invoked on every state transition. |

---

## Examples

### Basic circuit breaker (consecutive mode)

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

### Time-based sliding window

```kotlin
val policy = resilient {
    circuitBreaker {
        failureThreshold = 5           // 5 failures within the window opens the circuit
        successThreshold = 2
        timeout          = 30.seconds
        slidingWindow    = 60.seconds  // time-based failure window
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

## Failure-Rate Mode (failureRateThreshold)

In consecutive mode a single success resets the counter, which can mask a degraded dependency that still occasionally succeeds. **Failure-rate mode** evaluates the ratio of failures over the last N outcomes and opens the circuit when it exceeds a percentage threshold.

```kotlin
val policy = resilient {
    circuitBreaker {
        failureRateThreshold = 50.0   // open when >= 50% of recent calls fail
        minimumNumberOfCalls = 10     // wait for at least 10 outcomes before evaluating
        successThreshold     = 2
        timeout              = 30.seconds
    }
}
```

**How it works:**
- A ring buffer of size `minimumNumberOfCalls` records each outcome (success / failure).
- The rate is only evaluated once `minimumNumberOfCalls` outcomes have been recorded.
- Each new call evicts the oldest outcome from the buffer (sliding window).
- Half-open recovery (`successThreshold`) is unchanged regardless of how the circuit was tripped.

```
minimumNumberOfCalls = 5, failureRateThreshold = 60%:

Outcomes:  F F F S F  →  3/5 failures = 60%  →  OPEN
           └─────────── ring buffer (5 entries) ─────────┘

Next call after recovery:  S S  (successThreshold = 2)  →  CLOSED
```

> Do not combine `failureRateThreshold` with `slidingWindow` — they are mutually exclusive and the builder throws `IllegalArgumentException` if both are set.

---

## Result-Based Failure Recording (shouldRecordResult)

Sometimes a downstream returns a successful HTTP 200 with a body that signals degradation (e.g. `{ "status": "unavailable" }`, an HTTP 503 wrapped in a 200, or a gRPC status code encoded in the payload). **`shouldRecordResult`** lets you count those as failures without throwing an exception.

```kotlin
data class ApiResponse(val code: Int, val body: String)

val policy = resilient {
    circuitBreaker {
        failureThreshold   = 3
        successThreshold   = 2
        timeout            = 30.seconds
        shouldRecordResult = { result ->
            // Count HTTP-503-style responses as failures even though no exception was thrown
            result is ApiResponse && result.code == 503
        }
    }
}

// The value is still returned to the caller — only the failure counter changes
val response: ApiResponse = policy.execute { apiClient.call() }
```

**Key semantics:**
- `shouldRecordResult` is evaluated **after** the block returns without throwing.
- If `shouldRecordResult` returns `true`, the failure counter is incremented exactly as if an exception had been thrown.
- The return value is **still returned** to the caller unchanged.
- Exceptions continue to be governed by `shouldRecordFailure` — both predicates are independent.

---

## Named / Shared Circuit Breaker (CircuitBreakerRegistry)

By default each `resilient { }` policy owns its own breaker instance. When multiple call sites target the same downstream (e.g. different ViewModels calling the same `"payments"` service) you want a **single shared breaker** so that failures from any caller contribute to — and are protected by — the same state machine.

Use `CircuitBreakerRegistry` for this:

```kotlin
import com.santimattius.resilient.circuitbreaker.CircuitBreakerRegistry

val circuitBreakers = CircuitBreakerRegistry()

val policyA = resilient(scope) {
    circuitBreakerNamed(circuitBreakers, "payments") {
        failureThreshold = 5
        successThreshold = 2
        timeout          = 30.seconds
    }
}

val policyB = resilient(scope) {
    circuitBreakerNamed(circuitBreakers, "payments") {
        // First registration wins — this configure block is ignored
        failureThreshold = 5
    }
}

// Five failures through policyA open the circuit for policyB as well
```

**Contract:**
- First-name-wins: the first call to `getOrCreate("payments", configure)` creates the breaker; subsequent calls with the same name return the same instance and ignore `configure`.
- The registry is **not thread-safe** — it is intended as a startup-time construct, created once before any coroutine uses it (same contract as `BulkheadRegistry`).
- `circuitBreakerNamed` and `circuitBreaker { }` are mutually exclusive in the same builder; combining both throws `IllegalArgumentException`.
- Only the first policy that registers a name receives `CircuitStateChanged` telemetry events via `policy.events`. Subsequent policies sharing the same entry share breaker state but not the telemetry callback — observe shared state directly via the registry or `DefaultCircuitBreaker.state`.

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
- Use `shouldRecordResult` when the downstream signals errors inside successful HTTP 200 responses — common in legacy APIs and some gRPC patterns.
- Use `failureRateThreshold` instead of consecutive-failure mode when your dependency intermittently succeeds, masking an underlying degradation.
- Log and alert on CLOSED → OPEN transitions. They signal that a downstream dependency is degraded.
- Combine Circuit Breaker with [Retry](retry.md) (Retry inside, Circuit Breaker outside): retries attempt recovery from transient errors; the circuit breaker stops the attempts when the dependency is clearly down.
- Monitor circuit state in your observability stack via `onStateChange` or `policy.events`.
- Expose circuit state on `/health` or `/readiness` endpoints for orchestration platforms.
- Use `CircuitBreakerRegistry` when multiple independent call sites target the same downstream — a single shared state machine is more accurate than N isolated ones.

---

## See Also

- [Retry](retry.md) — retry transient failures before the circuit opens
- [Timeout](timeout.md) — prevent slow calls from blocking indefinitely
- [Fallback](fallback.md) — return a safe value when the circuit is open
- [INDEX](../README.md) — full pattern index
