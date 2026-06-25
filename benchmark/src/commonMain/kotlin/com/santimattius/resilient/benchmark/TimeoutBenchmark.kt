package com.santimattius.resilient.benchmark

import com.santimattius.resilient.timeout.DefaultTimeoutPolicy
import com.santimattius.resilient.timeout.TimeoutConfig
import com.santimattius.resilient.timeout.TimeoutPolicy
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlin.time.Duration.Companion.seconds

/**
 * Measures the overhead of [TimeoutPolicy] when the block always completes within the deadline.
 *
 * The timeout is set to 30 seconds, which is far larger than the nanosecond-range execution time
 * of [unit]. This means [kotlinx.coroutines.withTimeout] sets up its cancellation handle and then
 * tears it down immediately — no real timeout fires. What is measured is the pure
 * [kotlinx.coroutines.withTimeout] wrapper cost.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
class TimeoutBenchmark {

    private lateinit var policy: TimeoutPolicy

    @Setup
    fun setup() {
        policy = DefaultTimeoutPolicy(
            TimeoutConfig().apply {
                timeout = 30.seconds
            }
        )
    }

    @Benchmark
    fun withinDeadline(): Int = blockingUnconfined { policy.execute { unit() } }
}
