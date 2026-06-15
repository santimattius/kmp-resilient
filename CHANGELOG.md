# Changelog

All notable changes to the Resilient KMP library are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---
## [Unreleased]

### Added

- **Deadline Propagation** (`@ResilientExperimentalApi`)
  - New `ResilientDeadline` coroutine context element for propagating a deadline across the resilient policy pipeline.
  - `ResilientDeadline.after(duration)` factory creates a deadline that expires after the given duration from now.
  - When `ResilientDeadline` is present in the coroutine context, `ResilientPolicy.execute` enforces it automatically:
    - If the deadline has already expired, `TimeoutCancellationException` is thrown immediately without invoking the block.
    - If both a deadline and an explicit `timeout { }` are configured, the shorter of the two wins (`min(timeout, remaining)`).
  - Fully KMP — implemented in `commonMain` using `kotlin.time.TimeMark` and `withTimeout`.

---

## [1.5.0] - 2026-06-06

### Added

- **Retry — DecorrelatedJitterBackoff**
  - New `DecorrelatedJitterBackoff(base: Duration, cap: Duration)` backoff strategy implementing `BackoffStrategy`.
  - Uses a stateless approximation of the AWS decorrelated jitter formula: `min(cap, random(base, base * 3^(attempt-1)))`, safe for use across concurrent `execute()` calls on shared policy instances.
  - Enforces `base > Duration.ZERO` and `cap >= base` at construction time; violating either throws `IllegalArgumentException`.
  - Available in `commonMain` with no platform-specific dependencies.
- **Richer PolicyHealthSnapshot**
  - `RateLimiterSnapshot(remainingCalls: Int, timeToRefill: Duration)` — exposes current token count and time until the next bucket refill.
  - `RetrySnapshot(maxAttempts: Int)` — exposes the configured maximum attempt count.
  - `CacheSnapshot(entryCount: Int, hitRate: Double)` — exposes current cache entry count and cumulative hit rate (`Double.NaN` when no calls have been made yet).
  - `PolicyHealthSnapshot` gains three nullable trailing fields (`rateLimiter`, `retry`, `cache`) with `null` defaults — source-compatible with existing consumers.
  - `DefaultRateLimiter.snapshot()` reads `@Volatile` token state for a non-blocking, approximation-safe snapshot.
  - `DefaultRetryPolicy.snapshot()` reflects the immutable `maxAttempts` configuration.
  - `InMemoryCachePolicy` tracks cumulative `hitCount` and `missCount` (written under the internal mutex); `snapshot()` computes `hitRate` from those counters as an approximation suitable for health endpoints.
- **CircuitBreakerRegistry**
  - `CircuitBreakerRegistry` for named, shared circuit breaker instances so multiple policies can share the same breaker state (e.g. all calls to `"payments"` trip a single breaker).
  - `ResilientBuilder.circuitBreakerNamed(registry, name, config)` DSL — mutually exclusive with `circuitBreaker { }`.
- **Telemetry note:** only the first policy that registers a name in the registry receives `CircuitStateChanged` telemetry events via its `events` SharedFlow. Subsequent policies reusing the same entry share the breaker state but not its telemetry callback. Observe shared state directly via `DefaultCircuitBreaker.state`.
- **Circuit Breaker — result-based failure recording**
  - `CircuitBreakerConfig.shouldRecordResult`: optional predicate `((Any?) -> Boolean)?` evaluated after the block returns without throwing. When `true`, the returned value counts as a failure (incrementing the failure counter and potentially opening the circuit) while the value is still returned to the caller unchanged. Default `null` preserves existing behaviour — zero regression.
  - This predicate is independent of `shouldRecordFailure`: `shouldRecordFailure` governs the exception path; `shouldRecordResult` governs the success-value path.
- **Circuit Breaker — failure-rate sliding window mode**
  - `CircuitBreakerConfig.failureRateThreshold: Double?` (0.0–100.0, default `null`): enables count-based failure-rate mode. The circuit opens when the failure rate computed over the last `minimumNumberOfCalls` outcomes meets or exceeds this percentage.
  - `CircuitBreakerConfig.minimumNumberOfCalls: Int` (default `10`): minimum number of call outcomes that must be recorded before the failure rate is evaluated. Also defines the ring-buffer window size.
  - Setting both `failureRateThreshold` and `slidingWindow` in the same config throws `IllegalArgumentException`.
  - Half-open recovery (`successThreshold`) and the existing consecutive-failure and time-based sliding-window modes are unchanged.
- `CoroutineScope.asResilientScope()` — creates a child `ResilientScope` linked to an existing `CoroutineScope`. Cancelling the outer scope cancels all internal background jobs (cache cleanup, coalescing). Use with `viewModelScope` or `lifecycleScope` to eliminate manual `ResilientScope` lifecycle management.
- `CoroutineScope.resilient(block)` — shorthand extension that wraps `.asResilientScope()` + `resilient(scope, block)` in one call.
- **RateLimiterRegistry**
  - `RateLimiterRegistry` for named, shared rate-limiter instances so multiple policies can share the same token-bucket quota (e.g. all `"payments"` calls consume from the same pool).
  - `getOrCreate(name, configure, onRateLimited?)` follows a first-name-wins contract: the first call for a given name creates the limiter; subsequent calls return the same instance and ignore the configure block. Not synchronized across threads (startup-time construct, matching `BulkheadRegistry` contract).
  - `ResilientBuilder.rateLimiterNamed(registry, name, configure)` DSL — mutually exclusive with `rateLimiter { }`. Throws `IllegalArgumentException` at build time if both are configured.
- **resilient-test — FaultInjector: deterministic `failCount`**
  - `FaultInjector.Builder.failCount(n: Int)`: fails the first `n` calls deterministically, then always succeeds. Takes precedence over `failureRate` when > 0.
  - Preferred over `failureRate` for unit tests — eliminates probabilistic flakiness (e.g. `failureRate(0.6)` with `maxAttempts = 5` had a ~7.8% chance of exhausting all retries).

## [1.4.0] - 2026-03-22

### Added

- **Coalescing Policy**
  - New `CoalescingPolicy` for in-flight request deduplication: concurrent executions with the same key share a single underlying call instead of all running independently.
  - `CoalesceConfig` with a `keyProvider` to derive the deduplication key from context.
  - New `OrderablePolicyType.COALESCE` / `PolicyType.COALESCE` integrated in the default composition order.
  - `ResilientBuilder.coalesce { }` DSL and `ResilientScope.async` helper for launching coalesced work without cancellation propagation.
- **Retry — result-based retries**
  - `RetryPolicyConfig.shouldRetryResult`: predicate that triggers another attempt when the block *succeeds* but the returned value is not acceptable (e.g. HTTP 202, empty body, "not ready" flag).
  - Shares the same `maxAttempts` budget as exception-based retries. If attempts are exhausted while the predicate stays `true`, the last returned value is returned (unlike exception exhaustion, which rethrows).
  - `onRetry` receives `RetryableResultException` (with `lastValue`) for telemetry/observability.
- **Circuit Breaker — sliding-window mode**
  - `CircuitBreakerConfig.slidingWindow`: optional `Duration` that switches the breaker from consecutive-failure counting to a time-based window. The breaker opens when `failureThreshold` failures occur within the window; successes prune expired timestamps from the window.
- **BulkheadRegistry**
  - `BulkheadRegistry` for named, shared bulkhead instances so multiple policies can share the same concurrency pool (e.g. all `"database"` calls).
  - `ResilientBuilder.bulkheadNamed(registry, name, config)` DSL — mutually exclusive with `bulkhead { }`.
- **resilient-test module** *(new artifact: `resilient-test`)*
  - `FaultInjector`: simulate failures, delays, and intermittent behaviour in tests via a fluent builder (`failureRate`, `delay`, `delayJitter`, `exception`).
  - `PolicyBuilders`: pre-configured resilience policies with sensible, fast test defaults (`retryPolicy`, `timeoutPolicy`, `circuitBreakerPolicy`, `bulkheadPolicy`, `rateLimiterPolicy`).
  - `TestResilientScope`: factory for creating test-friendly `ResilientScope` instances compatible with `runTest` and virtual time.

### Changed

- **Build**
  - Maven publishing configuration centralised in `gradle.properties` (group, version, POM metadata, signing). The legacy `resilient-maven-publishing.gradle` script has been removed; all modules now use `mavenPublishing.coordinates(...)` driven from root properties.

---

## [1.3.0] - 2026-03-18

Official release.

## [1.3.0-ALPHA01] - 2026-03-04

Planned items: cache (dynamic key, invalidation), timeout (documentation and per-attempt), health/readiness, Circuit Breaker snapshot, and Rate Limiter documentation. Some of these may already be in `main` / 1.2.0; confirm in code and README.

### Added

- **Cache**
  - Dynamic key: support for `keyProvider` (or similar) to derive the key from context (e.g. arguments of `execute`), allowing reuse of the same cache policy for different resources.
  - Invalidation API by key and, if needed, by prefix (e.g. invalidate all keys starting with `user:`).
- **Health / Readiness**
  - API to expose current policy state: `policy.getHealthSnapshot(): PolicyHealthSnapshot`.
  - Circuit Breaker in snapshot: `state` (CLOSED/OPEN/HALF_OPEN), `failureCount`, `successCount`.
  - Bulkhead in snapshot (optional): `activeConcurrentCalls`, `waitingCalls`, configured maximums.
- **Circuit Breaker**
  - Exposure of counters via `CircuitBreaker.snapshot(): CircuitBreakerSnapshot` (`state`, `failureCount`, `successCount`) for dashboards and health checks.

### Changed

- **Timeout**
  - Explicit documentation of current behaviour (composition order with Retry, impact on “per-attempt” vs “total” timeout).
  - Evaluation of “per-attempt timeout” as an option in Retry or as a dedicated policy.

### Documentation

- Documentation of the `CachePolicy` interface for custom implementations (persistent, Redis, multiplatform settings, etc.).
- Rate Limiter: KDoc and README documentation of semantics (token-bucket, refill at the start of each period). Per-key limit under consideration for v2.

---

## [1.2.0] - 2026-02-26

### Added

- **Telemetry**
  - `ResilientEvent`: `CacheHit(key)`, `CacheMiss(key)`, `TimeoutTriggered(timeout)`, `FallbackTriggered(error)`, `HedgingUsed(attemptIndex)` in addition to existing events (`RetryAttempt`, `CircuitStateChanged`, `RateLimited`, `BulkheadRejected`, `OperationSuccess`, `OperationFailure`).
- **Retry**
  - `perAttemptTimeout` in `RetryPolicyConfig`: timeout per attempt (each attempt has its own limit).
- **Cache**
  - Support for dynamic key (`keyProvider`) in addition to fixed key (`key`).
  - Invalidation API: `policy.cacheHandle?.invalidate(key)` and `policy.cacheHandle?.invalidatePrefix(prefix)`.
- **Health / Readiness**
  - `policy.getHealthSnapshot()`: snapshot with Circuit Breaker state (`state`, `failureCount`, `successCount`) and Bulkhead (`activeConcurrentCalls`, `waitingCalls`, maximums).

### Changed

- Policy lifecycle and validation improvements.
- Telemetry event format and configuration.
- Cache cleanup configuration.

### Documentation

- README: composition order (Timeout vs Retry, Fallback always outermost) and its impact on per-attempt vs total timeout.
- README: `shouldRetry` best practice (do not retry on 4xx; retry only on transient failures).
- README and KDoc: Rate Limiter semantics (token-bucket, refill at the start of each period).
- `FallbackConfig`: return type documentation to avoid `ClassCastException` when fallback does not match block type.

### Fixed

- Timeout tests adjusted for `TimeoutTriggered`; cache telemetry tests.

---

## [1.1.0] - 2026-02-18

### Added

- **Composition**
  - Configurable composition order via `compositionOrder(listOf(OrderablePolicyType.CACHE, ...))`. Fallback is not in the list and remains the outermost policy.
- **Example**
  - ViewModel and UI state for the Android example (ResilientExample).
- **Build**
  - Configuration for additional KMP targets.

### Changed

- `build.gradle.kts` and dependency updates.
- Coroutine code improvements and adjustments (reverted and refined in 1.2.0).

---

## [1.0.0] - 2026-01-10

### Added

- **Core**
  - Single entry point: `policy.execute { block }` with declarative DSL `resilient(scope) { ... }`.
- **Policies**
  - **Timeout:** aborts the operation after a configured duration; `onTimeout` callback.
  - **Retry:** retries with `shouldRetry`, backoffs (`ExponentialBackoff`, `LinearBackoff`, `FixedBackoff`), jitter.
  - **Circuit Breaker:** states CLOSED → OPEN → HALF_OPEN; `failureThreshold`, `successThreshold`, `halfOpenMaxCalls`, open timeout.
  - **Rate Limiter:** token-bucket with `maxCalls` and `period`; `timeoutWhenLimited`, `onRateLimited`.
  - **Bulkhead:** limit on concurrent executions and waiting calls; acquire timeout.
  - **Hedging:** parallel attempts with `stagger`; returns the first successful result.
  - **Cache:** in-memory TTL per key; thundering-herd protection per key.
  - **Fallback:** fallback value when the operation fails; `CancellationException` is rethrown.
- **Telemetry**
  - `policy.events` (`SharedFlow<ResilientEvent>`): `RetryAttempt`, `CircuitStateChanged`, `RateLimited`, `BulkheadRejected`, `OperationSuccess`, `OperationFailure`.
- **Composition**
  - Default order: Fallback → Cache → Timeout → Retry → Circuit Breaker → Rate Limiter → Bulkhead → Hedging → Block.
- **Testing**
  - Tests with `runTest` and virtual time (kotlinx-coroutines-test).
- **Platform**
  - Kotlin Multiplatform (Android, JVM, etc.); Android/Compose example.

---

[1.5.0]: https://github.com/santimattius/kmp-resilient/compare/1.4.0...1.5.0
[1.4.0]: https://github.com/santimattius/kmp-resilient/compare/1.3.0...1.4.0
[1.3.0-ALPHA01]: https://github.com/santimattius/kmp-resilient/compare/1.2.0...1.3.0-ALPHA01
[1.2.0]: https://github.com/santimattius/kmp-resilient/compare/1.1.0...1.2.0
[1.1.0]: https://github.com/santimattius/kmp-resilient/compare/1.0.0...1.1.0
[1.0.0]: https://github.com/santimattius/kmp-resilient/compare/1.0.0-ALPHA04...1.0.0
