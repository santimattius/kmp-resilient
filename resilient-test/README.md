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
            implementation("io.github.santimattius.resilient:resilient-test:1.3.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
        }
    }
}
```

## Usage

### Fault Injection

```kotlin
import com.santimattius.resilient.test.FaultInjector
import kotlin.time.Duration.Companion.milliseconds

@Test
fun `test with fault injection`() = runTest {
    val injector = FaultInjector.builder()
        .failureRate(0.3)           // 30% failure rate
        .delay(50.milliseconds)      // 50ms delay
        .delayJitter(true)           // ±20% jitter
        .exception { CustomException() }
        .build()

    val result = injector.execute {
        fetchData() // may fail or delay
    }
}
```

### Policy Builders

```kotlin
import com.santimattius.resilient.test.PolicyBuilders
import com.santimattius.resilient.test.TestResilientScope

@Test
fun `test retry with fast defaults`() = runTest {
    val scope = TestResilientScope()
    val policy = PolicyBuilders.retryPolicy(
        scope,
        maxAttempts = 3,
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

### Integration Testing

Combine fault injection with resilience policies:

```kotlin
@Test
fun `test retry with intermittent failures`() = runTest {
    val scope = TestResilientScope()
    val policy = PolicyBuilders.retryPolicy(scope, maxAttempts = 5)
    
    val injector = FaultInjector.builder()
        .failureRate(0.6) // 60% failure rate
        .build()
    
    val result = policy.execute {
        injector.execute { "success" }
    }
    
    assertEquals("success", result)
}
```

## Available Policy Builders

- `retryPolicy(scope, maxAttempts, initialDelay, maxDelay, shouldRetry)`
- `timeoutPolicy(scope, timeout)`
- `circuitBreakerPolicy(scope, failureThreshold, successThreshold, timeout)`
- `bulkheadPolicy(scope, maxConcurrentCalls, maxWaitingCalls)`
- `rateLimiterPolicy(scope, maxCalls, period)`

All builders use fast defaults optimized for testing (short delays, small thresholds).

## License

Apache 2.0
