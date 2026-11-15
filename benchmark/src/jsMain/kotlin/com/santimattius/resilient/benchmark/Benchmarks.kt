package com.santimattius.resilient.benchmark

import kotlinx.benchmark.*
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlin.js.Promise

// The benchmark runner waits for fulfillment but does not observe a rejected promise.
// Install once at the Node boundary so unexpected failures terminate rather than hang.
private fun installFailFast() {
    js(
        """if (!process.__resilientBenchmarkFailFast) {
        process.__resilientBenchmarkFailFast = true;
        process.on('unhandledRejection', function (error) {
            console.error('Unexpected benchmark promise rejection:', error);
            process.exit(1);
        });
    }""",
    )
}

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
class CircuitBreakerBenchmark {
    private val fixture = BreakerFixture()
    private lateinit var owner: BenchmarkOwner
    private lateinit var preparation: Promise<Unit>

    @Setup fun setup() {
        installFailFast()
        owner = BenchmarkOwner()
        fixture.setup()
        preparation = owner.run { fixture.prepareOpen() }
    }

    @TearDown fun tearDown() = owner.close()

    @Benchmark fun closedExecute(): Promise<Int> = owner.run { fixture.closedExecute() }

    @Benchmark fun openReject(): Promise<Unit> =
        owner.run {
            preparation.await()
            fixture.openReject()
        }
}

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
class PipelineBenchmark {
    private val fixture = PipelineFixture()
    private lateinit var owner: BenchmarkOwner

    @Setup fun setup() {
        installFailFast()
        owner = BenchmarkOwner()
        fixture.setup()
    }

    @TearDown fun tearDown() {
        owner.close()
        fixture.tearDown()
    }

    @Benchmark fun legacyPipeline(): Promise<Int> = owner.run { fixture.legacyPipeline() }

    @Benchmark fun typedPipeline(): Promise<Int> = owner.run { fixture.typedPipeline() }
}

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
class RateLimiterBenchmark {
    @Param("1", "64")
    var keyCount: Int = 1
    private val fixture = RateLimiterFixture()
    private lateinit var owner: BenchmarkOwner

    @Setup fun setup() {
        installFailFast()
        owner = BenchmarkOwner()
        fixture.keyCount = keyCount
        fixture.setup()
    }

    @TearDown fun tearDown() = owner.close()

    @Benchmark fun globalLimiter(): Promise<Int> = owner.run { fixture.globalLimiter() }

    @Benchmark fun keyedLimiter(): Promise<Int> = owner.run { fixture.keyedLimiter() }
}

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
class RateLimiterContentionBenchmark {
    @Param("8")
    var callers: Int = 8
    private val fixture = ContentionFixture()
    private lateinit var owner: BenchmarkOwner

    @Setup fun setup() {
        installFailFast()
        owner = BenchmarkOwner()
        fixture.callers = callers
        fixture.setup()
    }

    @TearDown fun tearDown() = owner.close()

    @Benchmark fun globalContended(): Promise<Int> = owner.run { fixture.globalContended() }

    @Benchmark fun keyedSameKey(): Promise<Int> = owner.run { fixture.keyedSameKey() }

    @Benchmark fun keyedDistinctKeys(): Promise<Int> = owner.run { fixture.keyedDistinctKeys() }
}

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
class GlobalAndKeyedCompositionBenchmark {
    @Param("64")
    var keyCount: Int = 64
    private val fixture = CompositionFixture()
    private lateinit var owner: BenchmarkOwner

    @Setup fun setup() {
        installFailFast()
        owner = BenchmarkOwner()
        fixture.keyCount = keyCount
        fixture.setup()
    }

    @TearDown fun tearDown() {
        owner.close()
        fixture.tearDown()
    }

    @Benchmark fun typedGlobalAndKeyed(): Promise<Int> = owner.run { fixture.typedGlobalAndKeyed() }

    @Benchmark fun nestedGlobalAndKeyed(): Promise<Int> = owner.run { fixture.nestedGlobalAndKeyed() }
}

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
class RetryPolicyBenchmark {
    @Param("1", "3", "5")
    var localMaxAttempts: Int = 1
    private val fixture = RetryFixture()
    private lateinit var owner: BenchmarkOwner

    @Setup fun setup() {
        installFailFast()
        owner = BenchmarkOwner()
        fixture.setup(localMaxAttempts)
    }

    @TearDown fun tearDown() = owner.close()

    @Benchmark fun happyPath(): Promise<Int> = owner.run { fixture.happyPath() }
}

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
class TimeoutBenchmark {
    private val fixture = TimeoutFixture()
    private lateinit var owner: BenchmarkOwner

    @Setup fun setup() {
        installFailFast()
        owner = BenchmarkOwner()
        fixture.setup()
    }

    @TearDown fun tearDown() = owner.close()

    @Benchmark fun withinDeadline(): Promise<Int> = owner.run { fixture.withinDeadline() }
}

/** Fails if the generated runner invokes a second operation before awaiting the first. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
class AsyncDescriptorSmokeBenchmark {
    private lateinit var owner: BenchmarkOwner
    private var active = false

    @Setup fun setup() {
        installFailFast()
        owner = BenchmarkOwner()
    }

    @TearDown fun tearDown() = owner.close()

    @Benchmark fun delayed(): Promise<Int> {
        check(!active) { "Generated runner did not await the previous promise" }
        active = true
        return owner.run {
            delay(5)
            check(js("process.env.RESILIENT_BENCHMARK_FAIL_SMOKE !== '1'") as Boolean) { "intentional smoke rejection" }
            active = false
            42
        }
    }
}
