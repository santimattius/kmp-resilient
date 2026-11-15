package com.santimattius.resilient.benchmark

import kotlinx.benchmark.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

private fun <T> blocking(body: suspend () -> T): T = runBlocking(Dispatchers.Unconfined) { body() }

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
class CircuitBreakerBenchmark {
    private val fixture = BreakerFixture()

    @Setup fun setup() {
        fixture.setup()
        blocking { fixture.prepareOpen() }
    }

    @Benchmark fun closedExecute(): Int = blocking { fixture.closedExecute() }

    @Benchmark fun openReject() {
        blocking { fixture.openReject() }
    }
}

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
class PipelineBenchmark {
    private val fixture = PipelineFixture()

    @Setup fun setup() = fixture.setup()

    @TearDown fun tearDown() = fixture.tearDown()

    @Benchmark fun legacyPipeline(): Int = blocking { fixture.legacyPipeline() }

    @Benchmark fun typedPipeline(): Int = blocking { fixture.typedPipeline() }
}

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
class RateLimiterBenchmark {
    @Param("1", "64")
    var keyCount: Int = 1
    private val fixture = RateLimiterFixture()

    @Setup fun setup() {
        fixture.keyCount = keyCount
        fixture.setup()
    }

    @Benchmark fun globalLimiter(): Int = blocking { fixture.globalLimiter() }

    @Benchmark fun keyedLimiter(): Int = blocking { fixture.keyedLimiter() }
}

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
class RateLimiterContentionBenchmark {
    @Param("8")
    var callers: Int = 8
    private val fixture = ContentionFixture()

    @Setup fun setup() {
        fixture.callers = callers
        fixture.setup()
    }

    @Benchmark fun globalContended(): Int = blocking { fixture.globalContended() }

    @Benchmark fun keyedSameKey(): Int = blocking { fixture.keyedSameKey() }

    @Benchmark fun keyedDistinctKeys(): Int = blocking { fixture.keyedDistinctKeys() }
}

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
class GlobalAndKeyedCompositionBenchmark {
    @Param("64")
    var keyCount: Int = 64
    private val fixture = CompositionFixture()

    @Setup fun setup() {
        fixture.keyCount = keyCount
        fixture.setup()
    }

    @TearDown fun tearDown() = fixture.tearDown()

    @Benchmark fun typedGlobalAndKeyed(): Int = blocking { fixture.typedGlobalAndKeyed() }

    @Benchmark fun nestedGlobalAndKeyed(): Int = blocking { fixture.nestedGlobalAndKeyed() }
}

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
class RetryPolicyBenchmark {
    @Param("1", "3", "5")
    var localMaxAttempts: Int = 1
    private val fixture = RetryFixture()

    @Setup fun setup() = fixture.setup(localMaxAttempts)

    @Benchmark fun happyPath(): Int = blocking { fixture.happyPath() }
}

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
class TimeoutBenchmark {
    private val fixture = TimeoutFixture()

    @Setup fun setup() = fixture.setup()

    @Benchmark fun withinDeadline(): Int = blocking { fixture.withinDeadline() }
}
