# Resilience Patterns — Reference Index

This reference covers the eight resilience patterns provided by the **Resilient** library. Each document includes a theoretical definition, guidance on when to apply the pattern, and concrete examples using the `resilient { }` DSL.

---

## Patterns

| Pattern | Summary | Document |
|---|---|---|
| [Timeout](#timeout) | Aborts an operation that exceeds a maximum duration. | [timeout.md](patterns/timeout.md) |
| [Retry](#retry) | Re-executes a failing operation with configurable back-off strategies. | [retry.md](patterns/retry.md) |
| [Circuit Breaker](#circuit-breaker) | Stops sending requests to a failing dependency to allow it to recover. | [circuit-breaker.md](patterns/circuit-breaker.md) |
| [Rate Limiter](#rate-limiter) | Caps the call rate to a downstream resource within a sliding window. | [rate-limiter.md](patterns/rate-limiter.md) |
| [Bulkhead](#bulkhead) | Limits concurrent executions to isolate resource exhaustion. | [bulkhead.md](patterns/bulkhead.md) |
| [Hedging](#hedging) | Launches redundant parallel attempts and returns the first success. | [hedging.md](patterns/hedging.md) |
| [Cache](#cache) | Stores successful results with a TTL to avoid redundant calls. | [cache.md](patterns/cache.md) |
| [Fallback](#fallback) | Returns a safe default value when all other policies have failed. | [fallback.md](patterns/fallback.md) |

---

## Pattern Descriptions

### Timeout
Enforces a hard deadline on any suspend operation. If the block does not complete within the configured duration the coroutine is cancelled and, optionally, a callback fires for metrics. The timeout applies to the **entire** wrapped scope — all retry attempts combined unless you customize the composition order.

→ [Read full documentation](patterns/timeout.md)

---

### Retry
Automatically re-executes a block on failure, waiting between attempts according to a back-off strategy (Exponential, Linear, or Fixed). A `shouldRetry` predicate lets you distinguish transient errors (network, 5xx) from permanent ones (4xx) so you never amplify bad requests. An optional `perAttemptTimeout` gives each attempt its own deadline.

→ [Read full documentation](patterns/retry.md)

---

### Circuit Breaker
A three-state finite-state machine (CLOSED → OPEN → HALF_OPEN) that monitors failure rates and, once a threshold is crossed, short-circuits all calls for a configurable recovery period. This prevents a cascade of timeouts from overwhelming a struggling dependency and gives it time to heal.

→ [Read full documentation](patterns/circuit-breaker.md)

---

### Rate Limiter
A token-bucket governor that allows at most `maxCalls` requests per `period`. Tokens refill at the start of each period. Excess callers either wait (up to a configurable deadline) or receive a `RateLimitExceededException` immediately. Ideal for protecting third-party APIs with strict quota contracts.

→ [Read full documentation](patterns/rate-limiter.md)

---

### Bulkhead
Isolates a resource behind a concurrency limit and a bounded waiting queue. Inspired by ship compartmentalization, it prevents one slow or overloaded resource from consuming all available threads or coroutines. Excess requests are rejected rather than piling up indefinitely.

→ [Read full documentation](patterns/bulkhead.md)

---

### Hedging
Fires multiple redundant attempts in parallel, staggered by a small delay, and returns whichever succeeds first, cancelling the rest. This trades extra load for a significant reduction in tail latency (p95/p99). Use selectively for latency-sensitive, idempotent operations.

→ [Read full documentation](patterns/hedging.md)

---

### Cache
Stores the successful result of a suspend call against a key with a Time-To-Live (TTL). Subsequent calls with the same key return the cached value without hitting the underlying resource. Concurrent requests for a cold key are deduplicated (thundering-herd prevention). Supports invalidation by exact key or prefix.

→ [Read full documentation](patterns/cache.md)

---

### Fallback
The outermost safety net: when every other policy has been exhausted and the operation still fails, Fallback invokes a user-supplied function to produce an alternative value. Common uses include returning stale data, a sensible default, or an error model understood by the caller.

→ [Read full documentation](patterns/fallback.md)

---

## Composition Order

The default execution order (outer → inner) is:

```
Fallback → Cache → Timeout → Retry → Circuit Breaker → Rate Limiter → Bulkhead → Hedging → block
```

The order is fully customizable. See [Retry](patterns/retry.md) and the main [README](../README.md) for trade-off examples such as *global timeout* vs *per-attempt timeout*.
