# Typed Policies and Templates

## Definition

A **typed policy** executes a block with an explicit input value: `resilientFor<Input> { }` returns a `TypedResilientPolicy<Input>` and `execute(input) { input -> ... }` runs the block under the configured policies. The same input is passed to every attempt. A **resilience template** is an immutable, context-free set of policy definitions created with `resilienceTemplate { }` and pulled into any build with `include(template)`.

Both reuse the engine behind `resilient { }`: same default composition order, retry, timeout, fallback, cache, telemetry and cancellation behavior, and exactly one operation success/failure event per execution. `Input` is plain execution data; `ResilientScope` stays reserved for coroutine lifecycle.

## When to apply

- A policy needs per-call data (tenant, user id, request) to select a quota or to build the request.
- Several policies (possibly for unrelated result or input types) share a baseline such as `timeout` + `retry`.

## Typed policy

```kotlin
data class UserId(val value: String)

val users = resilientFor<UserId>(scope) {
    timeout { timeout = 2.seconds }
    retry { maxAttempts = 3 }
}

val name: String = users.execute(UserId("u-1")) { id -> api.loadName(id) }
```

`CoroutineScope.resilientFor<Input> { }` binds the policy to a coroutine scope. `events`, `cacheHandle`, `getHealthSnapshot()` and `close()` behave as on `ResilientPolicy`.

## Templates and `include`

```kotlin
val baseline = resilienceTemplate {
    timeout { timeout = 2.seconds }
    retry { maxAttempts = 3 }
}

val textPolicy = resilient(scope) { include(baseline) }
val userPolicy = resilientFor<UserId>(scope) {
    include(baseline)
    retry { maxAttempts = 2 } // overlay: only maxAttempts changes
}
```

| Rule | Behavior |
|---|---|
| Overlay | `include` clones the template configuration; a later local block modifies the clone property by property (local wins). |
| Order | The last explicit `compositionOrder(...)` wins. |
| Two templates, same ordinary policy | The second template **replaces** the first; templates are not merged with each other. |
| Named policies | Duplicate named policies fail validation; named-vs-unnamed conflicts keep the existing validation. |
| Independent state | Every build gets its own caches, buckets, breakers and bulkheads, even from the same template. |
| Shared state | Only through explicit named registries: `rateLimiterNamed`, `circuitBreakerNamed`, `bulkheadNamed`, `keyedRateLimiterNamed`. Closing a policy never destroys registry-owned state. |

Templates are context-free: keyed rate limiters depend on `Input`, so they are configured on the typed builder, not inside a template.

## Global and keyed rate limiting

```kotlin
data class Request(val tenant: String)

val search = resilientFor<Request>(scope) {
    rateLimiter { maxCalls = 500; period = 1.seconds }   // global quota
    keyedRateLimiter<String> {                            // per-key quota
        keyProvider = { it.tenant }
        maxCalls = 20
        period = 1.seconds
        maxBuckets = 10_000
        bucketIdleTtl = 10.minutes
    }
}

val result = search.execute(Request("acme")) { request -> api.search(request) }
```

Both limiters apply in one `execute` call. Ordering follows the composition order; by default the global limiter runs right outside the keyed one (`OrderablePolicyType.RATE_LIMITER`, then `OrderablePolicyType.KEYED_RATE_LIMITER`), so a globally rejected call never creates a key bucket. To consume per-key quota first:

```kotlin
compositionOrder(listOf(OrderablePolicyType.KEYED_RATE_LIMITER, OrderablePolicyType.RATE_LIMITER))
```

`compositionOrder` is experimental (`@ResilientExperimentalApi`). To share per-key quotas across policies use `keyedRateLimiterNamed(registry, name) { ... }` with a `KeyedRateLimiterRegistry`. A policy can configure only one of `keyedRateLimiter` and `keyedRateLimiterNamed`.

## Observability and privacy

- `getHealthSnapshot().rateLimiter` reports the global limiter; `getKeyedRateLimiterSnapshot()` reports the keyed one (aggregate counts only, `null` when not configured).
- Rate limiting by key emits the keyless `ResilientEvent.KeyedRateLimited`.
- With `keyedRateLimiterNamed`, the event is emitted on the `events` flow of the policy whose execution was limited, even when another policy created the shared limiter.
- Raw input and derived keys never appear in events, snapshots or exception messages (including `KeyedRateLimiterCapacityExceededException`). If you need key grouping in metrics, use an approved bounded or hashed dimension.

## Migrating from nested `execute` calls

Before typed policies, combining a global limiter with a keyed one meant wrapping one `execute` call in another (see [Rate Limiter](rate-limiter.md#keyed-rate-limiter)):

```kotlin
// Before: two standalone limiters, composition order fixed by the nesting
val global = DefaultRateLimiter(RateLimiterConfig().apply { maxCalls = 500; period = 1.seconds })
val perTenant = keyedRateLimiter<Request, String> {
    keyProvider = { it.tenant }
    maxCalls = 20
    period = 1.seconds
}

suspend fun search(request: Request): List<Result> =
    global.execute {
        perTenant.execute(request) { api.search(request) }
    }
```

The same behavior as one declarative policy:

```kotlin
// After: one pipeline, one execute call
val searchPolicy = resilientFor<Request>(scope) {
    rateLimiter { maxCalls = 500; period = 1.seconds }
    keyedRateLimiter<String> {
        keyProvider = { it.tenant }
        maxCalls = 20
        period = 1.seconds
    }
}

suspend fun search(request: Request): List<Result> =
    searchPolicy.execute(request) { api.search(request) }
```

Migration notes:

- **Type arguments:** the standalone `keyedRateLimiter<Request, String>` takes the input and key types; inside `resilientFor<Request>` the input type is already known, so only the key type is declared.
- **Order:** the nesting above consumes global quota first. The default typed order does the same (global outside keyed). To reverse it, use `compositionOrder(...)` instead of swapping the nesting.
- **Shared quotas:** a limiter shared by several call sites becomes a named registry entry (`rateLimiterNamed`, `keyedRateLimiterNamed`). Building the policy twice from an ordinary `rateLimiter { }` or `keyedRateLimiter { }` creates independent quotas.
- **Observability:** the pipeline emits one operation success/failure event per execution and exposes separate snapshots (`getHealthSnapshot().rateLimiter` and `getKeyedRateLimiterSnapshot()`).
- **Lifecycle:** `close()` releases state owned by the policy; registry-owned state survives.
- **Compatibility:** existing standalone limiters and `resilient { }` keep working unchanged; migrate call sites incrementally.

## See also

- [Rate Limiter](rate-limiter.md#keyed-rate-limiter)
- [Composition Order](../README.md#composition-order)

## Cache and coalescing keys

`cache { }` and `coalesce { }` normally use a build-time key, which in a typed policy would make different inputs share one cache entry or in-flight call. A typed policy therefore **fails closed**: if it configures cache or coalescing (directly or via an included template) without an input-derived key, `resilientFor` throws `IllegalArgumentException`.

```kotlin
val template = resilienceTemplate { cache { ttl = 30.seconds } }   // context-free: no key yet

val users = resilientFor<UserId>(scope) {
    include(template)
    cacheKeyFor { "user:${it.value}" }      // required with cache
    coalesce { }
    coalesceKeyFor { it.value }             // required with coalesce
}
```

- Different inputs never share entries; equal keys hit the cache or join the in-flight call.
- `cacheKeyFor`/`coalesceKeyFor` without the matching `cache { }`/`coalesce { }` also fail the build.
- Privacy: `CacheHit`/`CacheMiss` events of typed policies carry the redacted key `<input-key>`. The derived key does not appear in events, snapshots or exception messages. `cacheHandle.invalidate(key)` still takes the raw derived key.
- `resilient { }` behavior is unchanged.
