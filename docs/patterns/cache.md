# Cache

## Definition

The **Cache** pattern stores the result of a successful operation against a key and returns the stored result on subsequent calls with the same key, without executing the underlying block again. This eliminates redundant calls to slow or expensive resources for recently computed or fetched data.

> "The fastest request is the one you never have to make."

The cache provided by Resilient is an **in-memory, TTL-based** (Time-To-Live) cache. It is intentionally simple and designed to compose seamlessly with the other resilience patterns in the pipeline. Custom backends (persistent storage, Redis, etc.) can be introduced by implementing the `CachePolicy` interface.

---

## How It Works

1. The `keyProvider` (or fixed `key`) is evaluated to produce a cache key.
2. If a **non-expired entry** exists for the key → its value is returned immediately. A `CacheHit` event is emitted.
3. If no entry exists (or it has expired) → a `CacheMiss` event is emitted and the underlying block is executed.
4. The result is stored in the cache with the configured `ttl`, then returned.
5. **Thundering-herd prevention**: if multiple coroutines concurrently request the same cold key, only one executes the block; the others await the same `Deferred` result. All receive the value once computed.

```
caller A ─► key "user:42" → MISS → execute block → store → return result
caller B ─► key "user:42" → (concurrent, awaits A's Deferred)         → return same result
caller C ─► key "user:42" → HIT  → return cached result (next request)
```

---

## When to Apply

| Scenario | Recommendation |
|---|---|
| Idempotent read operations (user profile, config, reference data) | **Recommended.** High hit rate if TTL is set appropriately. |
| Expensive computations with stable inputs | Cache the result for the duration of its validity. |
| Remote calls with predictable data staleness | Match `ttl` to the acceptable staleness window. |
| Write operations (POST, PUT, DELETE) | **Do not cache** the write itself. Invalidate related cache entries after write. |
| Highly dynamic data that must always be fresh | Cache adds complexity without benefit; skip it. |
| Data with security constraints (access per user) | Use per-user keys (dynamic `keyProvider`) and validate access before caching. |

---

## Configuration

| Property | Type | Default | Description |
|---|---|---|---|
| `key` | `String` | `"default"` | Fixed cache key for the policy. Mutually exclusive with `keyProvider`. |
| `keyProvider` | `suspend () -> String` | — | Suspend lambda that produces a dynamic key per call. Useful for per-user or per-request caching. |
| `ttl` | `Duration` | `60.seconds` | Time-To-Live. Entries older than this are treated as expired and evicted on the next access. |
| `cleanupInterval` | `Duration?` | `null` | If set, a background coroutine periodically evicts expired entries. Useful for long-running processes to control memory. |
| `cleanupBatch` | `Int` | `100` | Maximum number of entries inspected per cleanup cycle. |

> **Note:** `key` and `keyProvider` are mutually exclusive. Use `key` for a single-entry cache; use `keyProvider` when the cache key depends on runtime input.

---

## Example

### Fixed key cache

```kotlin
import com.santimattius.resilient.composition.resilient
import kotlin.time.Duration.Companion.seconds

val policy = resilient(scope) {
    cache {
        key = "app:config"
        ttl = 30.seconds
    }
}

suspend fun getConfig(): AppConfig = policy.execute {
    configService.fetch()  // called at most once per 30 s
}
```

### Dynamic key — per-user caching

```kotlin
val policy = resilient(scope) {
    cache {
        keyProvider = { "user:${currentUserId()}" }
        ttl         = 5.minutes
    }
}

suspend fun getUserProfile(): Profile = policy.execute {
    profileService.get(currentUserId())
}
```

### With background cleanup (long-running process)

```kotlin
import kotlin.time.Duration.Companion.minutes

val policy = resilient(scope) {
    cache {
        keyProvider       = { "product:${productId}" }
        ttl               = 10.minutes
        cleanupInterval   = 5.minutes   // evict expired entries every 5 min
        cleanupBatch      = 200
    }
}
```

### Cache invalidation after write

```kotlin
val policy = resilient(scope) {
    cache {
        keyProvider = { "user:${userId}" }
        ttl         = 60.seconds
    }
}

suspend fun updateProfile(userId: String, data: ProfileData) {
    profileService.update(userId, data)
    policy.cacheHandle?.invalidate("user:$userId")   // evict stale entry
}

suspend fun getProfile(userId: String): Profile = policy.execute {
    profileService.get(userId)
}
```

### Invalidate by prefix (e.g. flush all user cache entries)

```kotlin
policy.cacheHandle?.invalidatePrefix("user:")  // removes all "user:*" entries
```

### Full resilient composition with cache

```kotlin
import com.santimattius.resilient.retry.ExponentialBackoff
import kotlin.time.Duration.Companion.milliseconds

val policy = resilient(scope) {
    cache          { key = "profile:42"; ttl = 20.seconds }
    timeout        { timeout = 2.seconds }
    retry          {
        maxAttempts     = 3
        backoffStrategy = ExponentialBackoff(initialDelay = 100.milliseconds)
    }
    circuitBreaker { failureThreshold = 5; successThreshold = 2 }
}

suspend fun loadProfile(): Profile = policy.execute { fetchProfile(42) }
// Execution path:
//   1. Cache hit  → returns immediately
//   2. Cache miss → Timeout → Retry → CircuitBreaker → block → cache result
```

---

## Observing Cache Events

```kotlin
import com.santimattius.resilient.telemetry.ResilientEvent

policy.events.collect { event ->
    when (event) {
        is ResilientEvent.CacheHit  -> {
            metrics.increment("cache.hit",  tag("key", event.key))
        }
        is ResilientEvent.CacheMiss -> {
            metrics.increment("cache.miss", tag("key", event.key))
        }
        else -> {}
    }
}
```

Track the **hit ratio** (`hits / (hits + misses)`) over time. A low hit ratio indicates the TTL is too short, the key space is too large, or cache entries are being invalidated too aggressively.

---

## Lifecycle — `ResilientScope`

When `cleanupInterval` is configured, the cache starts a background coroutine for periodic eviction. This coroutine lives inside the `CoroutineScope` passed to `resilient(scope)`:

```kotlin
// Provide a scope tied to your component lifecycle
val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
val policy = resilient(scope) { cache { ... } }

// When the component is destroyed:
policy.close()   // cancels background cleanup jobs
```

If `cleanupInterval` is not set, no background job is started and `close()` is a no-op for the cache.

---

## Best Practices

- **Cache only idempotent reads.** Never cache write operations or operations with side effects.
- Set `ttl` based on the acceptable staleness of the data, not an arbitrary large value.
- Always **invalidate** the cache after a successful write to prevent stale reads.
- Use `keyProvider` for per-entity or per-user caching. Fixed `key` is only suitable when a single global cached value is correct.
- Monitor cache hit ratio. Aim for > 80% in steady state for a cache to justify its complexity.
- For distributed systems, the in-memory cache is local to each instance. Use a shared backend (e.g. Redis) by implementing `CachePolicy` if cross-instance consistency is required.
- Do not cache security-sensitive data unless the cache key is scoped to the authenticated principal.

---

## See Also

- [Retry](retry.md) — retry the underlying call on cache miss failure
- [Timeout](timeout.md) — enforce a deadline on the cache-miss execution path
- [Fallback](fallback.md) — return stale or default data when the underlying call fails
- [Hedging](hedging.md) — reduce latency on cache misses with parallel attempts
- [INDEX](../INDEX.md) — full pattern index
