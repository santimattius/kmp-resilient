# resilient-test

Testing utilities for the `kmp-resilient` library, providing fault injection and simplified policy builders for unit tests.

## Features

- **FaultInjector**: Simulate failures, delays, and intermittent behavior
- **PolicyBuilders**: Pre-configured resilience policies with sensible test defaults
- **TestResilientScope**: Factory for creating test-friendly scopes

## Installation

Add to your test dependencies:

```kotlin
kotlin {
    sourceSets {
        commonTest.dependencies {
            implementation("io.github.santimattius.resilient:resilient-test:1.5.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
        }
    }
}
```

## Usage

### Fault Injection

Use `FaultInjector` to simulate failures, delays, and intermittent behavior. Prefer **`failCount`** over `failureRate` for deterministic tests — probabilistic failure rates can cause intermittent test failures.

#### Deterministic failure count (recommended)

```kotlin
import com.santimattius.resilient.test.FaultInjector

@Test
fun `test retry recovers after failures`() = runTest {
    val injector = FaultInjector.builder()
        .failCount(3)   // fail the first 3 calls, succeed on the 4th
        .build()

    val result = injector.execute { "success" }
    assertEquals("success", result)
}
```

#### Probabilistic failure rate

Use `failureRate` only when you intentionally want non-deterministic behavior (e.g. chaos / load tests, not unit tests).

```kotlin
val injector = FaultInjector.builder()
    .failureRate(0.3)           // 30% chance of failure per call
    .delay(50.milliseconds)     // add 50ms delay
    .delayJitter(true)          // randomize delay ±20%
    .exception { CustomException() }
    .build()
```

#### FaultInjector configuration reference

| Builder method | Type | Default | Description |
|---|---|---|---|
| `failCount(n)` | `Int` | `0` | Fail the first `n` calls deterministically, then always succeed. Takes precedence over `failureRate` when > 0. **Preferred for unit tests.** |
| `failureRate(rate)` | `Double` | `0.0` | Probability of throwing an exception per call (0.0 = never, 1.0 = always). Ignored when `failCount > 0`. |
| `exception(block)` | `() -> Throwable` | `FaultInjectedException` | Factory for the exception to throw. |
| `delay(duration)` | `Duration` | `Duration.ZERO` | Fixed delay added before executing the block. |
| `delayJitter(enable)` | `Boolean` | `false` | Randomize delay ±20%. |

### Policy Builders

Use `PolicyBuilders` to create policies with sensible test defaults (short delays, small thresholds):

```kotlin
import com.santimattius.resilient.test.PolicyBuilders
import com.santimattius.resilient.test.TestResilientScope
import kotlinx.coroutines.test.runTest

@Test
fun `test retry with fast defaults`() = runTest {
    val scope = TestResilientScope()
    val policy = PolicyBuilders.retryPolicy(
        scope,
        maxAttempts  = 3,
        initialDelay = 10.milliseconds
    )

    var attempts = 0
    val result = policy.execute {
        attempts++
        if (attempts < 3) throw RuntimeException("fail")
        "success"
    }

    assertEquals("success", result)
    assertEquals(3, attempts)
}
```

#### Available builders

| Builder | Parameters (defaults) |
|---|---|
| `retryPolicy(scope, maxAttempts = 3, initialDelay = 10ms, maxDelay = 100ms, shouldRetry = { true })` | Fast exponential backoff, retries all exceptions |
| `timeoutPolicy(scope, timeout = 1.second)` | Short timeout for unit tests |
| `circuitBreakerPolicy(scope, failureThreshold = 3, successThreshold = 2, timeout = 5.seconds)` | Low thresholds to trip quickly in tests |
| `bulkheadPolicy(scope, maxConcurrentCalls = 2, maxWaitingCalls = 4)` | Tight concurrency limits |
| `rateLimiterPolicy(scope, maxCalls = 5, period = 1.second)` | Small quota for easy exhaustion in tests |

### Integration Testing

Combine fault injection with resilience policies. Always use `failCount` for deterministic outcomes:

```kotlin
@Test
fun `retry policy recovers after intermittent failures`() = runTest {
    val scope  = TestResilientScope()
    val policy = PolicyBuilders.retryPolicy(scope, maxAttempts = 5, initialDelay = 10.milliseconds)

    val injector = FaultInjector.builder()
        .failCount(3)  // fail first 3 attempts, succeed on 4th
        .build()

    var attempts = 0
    val result = policy.execute {
        attempts++
        injector.execute { "success after retries" }
    }

    assertEquals("success after retries", result)
    assertEquals(4, attempts)  // 3 failures + 1 success = exactly 4 attempts
}
```

### TestResilientScope

Use `TestResilientScope` to create a test-friendly scope compatible with `runTest` and virtual time:

```kotlin
import com.santimattius.resilient.test.TestResilientScope
import kotlinx.coroutines.test.runTest

@Test
fun `test policy lifecycle`() = runTest {
    val scope  = TestResilientScope()
    val policy = resilient(scope) {
        retry { maxAttempts = 3 }
    }

    // ... test logic

    scope.cancel() // cleanup
}
```

## License

Apache 2.0
