# Changelog

All notable changes to the Resilient KMP library are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---
## [Unreleased]

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

[1.3.0-ALPHA01]: https://github.com/santimattius/kmp-resilient/compare/1.2.0...1.3.0-ALPHA01
[1.2.0]: https://github.com/santimattius/kmp-resilient/compare/1.1.0...1.2.0
[1.1.0]: https://github.com/santimattius/kmp-resilient/compare/1.0.0...1.1.0
[1.0.0]: https://github.com/santimattius/kmp-resilient/compare/1.0.0-ALPHA04...1.0.0
