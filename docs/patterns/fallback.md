# Fallback

## Definition

The **Fallback** pattern provides a safe alternative result when an operation — and all other resilience policies in the chain — have failed. Instead of propagating an exception to the caller, Fallback invokes a user-supplied function to produce a substitute value: a cached result, a sensible default, an error model, or a degraded-mode response.

> "When everything else has failed, have a plan B."

Fallback is always the **outermost** policy in the Resilient composition chain. It wraps all other policies (Cache, Timeout, Retry, Circuit Breaker, Rate Limiter, Bulkhead, Hedging) so that any exception that escapes them can be caught and replaced with a safe result.

---

## How It Works

1. The inner pipeline (all other configured policies + the block) is executed.
2. If it succeeds → the result is returned normally. Fallback is transparent.
3. If it throws an exception (other than `CancellationException`) → the `onFallback` function is invoked with the error, and its return value is returned to the caller.
4. A `FallbackTriggered` telemetry event is emitted.

```
[ Fallback ] wraps → [ Cache → Timeout → Retry → CircuitBreaker → … → block ]
                                                                          │
                                                               exception ─┘
                                                                          │
                                              onFallback(error) ◄─────────┘
                                                       │
                                              alternative value ──► caller
```

---

## When to Apply

| Scenario | Recommendation |
|---|---|
| Showing stale / cached data when live fetch fails | **Recommended.** Return last known value from local storage. |
| Returning a sensible default (empty list, zero count, default config) | **Recommended.** Avoids a 500 error propagating to the UI. |
| Graceful degradation (full feature → degraded mode) | Useful when partial data is better than no data. |
| Error responses in an API gateway | Return a structured error model instead of an unhandled exception. |
| Critical paths where **no** alternative exists | Avoid masking errors. Re-throw or alert instead. |
| Authentication / authorisation failures | Do **not** fall back to a permissive default. Surface the error. |

**Important type-safety note:** The value returned by `onFallback` must match the return type `T` of the `execute` block. A mismatch results in a `ClassCastException` at runtime. Always parameterize your fallback to the same type as your operation.

---

## Configuration

| Property | Type | Description |
|---|---|---|
| `onFallback` | `suspend (Throwable) -> T` | Suspend function invoked on failure. Receives the triggering exception and must return a value of type `T`. |

The fallback is configured via `FallbackConfig`:

```kotlin
import com.santimattius.resilient.fallback.FallbackConfig

FallbackConfig { error ->
    // Produce a value of type T from the error
    defaultValue
}
```

---

## Example

### Basic fallback — return a default value

```kotlin
import com.santimattius.resilient.composition.resilient
import com.santimattius.resilient.fallback.FallbackConfig

val policy = resilient {
    fallback(FallbackConfig { _ -> emptyList<User>() })
}

suspend fun listUsers(): List<User> = policy.execute {
    userService.getAll()
}
// On failure: returns emptyList() instead of throwing
```

### Fallback with logging

```kotlin
val policy = resilient {
    fallback(FallbackConfig { error ->
        logger.error("listUsers failed, returning fallback", error)
        metrics.increment("fallback.triggered", tag("operation", "listUsers"))
        emptyList()
    })
}
```

### Stale-data fallback — last known value from a local repository

```kotlin
val policy = resilient {
    fallback(FallbackConfig { _ ->
        localRepository.getLastKnownUsers()   // stale but better than nothing
    })
}
```

### Typed fallback for complex return types

```kotlin
data class UserProfile(val id: String, val name: String, val degraded: Boolean = false)

val policy = resilient {
    fallback(FallbackConfig { _ ->
        UserProfile(id = userId, name = "Unknown", degraded = true)
    })
}

suspend fun loadProfile(userId: String): UserProfile = policy.execute {
    profileService.get(userId)
}
```

### Fallback combined with Retry and Circuit Breaker

```kotlin
import com.santimattius.resilient.retry.ExponentialBackoff
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

val policy = resilient {
    timeout        { timeout = 3.seconds }
    retry          {
        maxAttempts     = 3
        backoffStrategy = ExponentialBackoff(initialDelay = 100.milliseconds)
        shouldRetry     = { it is IOException }
    }
    circuitBreaker { failureThreshold = 5; timeout = 30.seconds }
    fallback(FallbackConfig { error ->
        logger.warn("All policies exhausted: ${error.message}")
        CachedData.lastKnownGoodValue()
    })
}
```

---

## Observing Fallback Events

```kotlin
import com.santimattius.resilient.telemetry.ResilientEvent

policy.events.collect { event ->
    if (event is ResilientEvent.FallbackTriggered) {
        println("Fallback triggered by: ${event.error::class.simpleName}")
        alerting.notify("Fallback activated", event.error)
    }
}
```

A spike in `FallbackTriggered` events is a strong signal that something is wrong upstream. Do not ignore it.

---

## Fallback Position in the Composition Chain

Fallback is **always outermost** and is not included in the `compositionOrder` configuration. This ensures it can catch exceptions from any policy in the chain:

```
Fallback
  └─ Cache
       └─ Timeout
            └─ Retry
                 └─ CircuitBreaker
                      └─ RateLimiter
                           └─ Bulkhead
                                └─ Hedging
                                     └─ block
```

If you configure a custom composition order, Fallback is still automatically placed outside. You do not need to include it in the list.

---

## Best Practices

- **Always log the triggering error** inside `onFallback`. Silent fallbacks hide real problems.
- **Always emit a metric** from `onFallback`. Use it to alert on sustained fallback activation.
- Ensure the fallback return type exactly matches `T` from `execute`. Use Kotlin's type system and IDE tooling to verify this at compile time where possible.
- Do **not** use Fallback as a general error handler for expected business errors (e.g. `NotFoundException`). Handle those directly in the calling code.
- A fallback that itself throws will propagate the exception to the caller. Make your fallback implementations robust (no I/O that can fail, or handle their own errors).
- Combine with [Circuit Breaker](circuit-breaker.md): when the circuit is open, calls fail immediately and Fallback catches them — this is the intended partnership.

---

## See Also

- [Circuit Breaker](circuit-breaker.md) — Fallback's primary partner: stops cascade, Fallback provides the alternative
- [Cache](cache.md) — a common fallback source: return cached/stale data
- [Retry](retry.md) — exhaust retries before Fallback activates
- [INDEX](../INDEX.md) — full pattern index
