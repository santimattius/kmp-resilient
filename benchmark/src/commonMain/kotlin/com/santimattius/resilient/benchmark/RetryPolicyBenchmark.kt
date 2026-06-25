package com.santimattius.resilient.benchmark

import com.santimattius.resilient.retry.DefaultRetryPolicy
import com.santimattius.resilient.retry.FixedBackoff
import com.santimattius.resilient.retry.RetryPolicy
import com.santimattius.resilient.retry.RetryPolicyConfig
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlin.time.Duration

/**
 * Measures the overhead of [RetryPolicy] on the happy path (first attempt succeeds).
 *
 * [maxAttempts] is parameterised so we can observe whether the policy overhead scales
 * with the configured attempt count or is essentially constant (it should be constant on
 * the happy path because the retry loop body is only executed once regardless).
 *
 * The [FixedBackoff] with [Duration.ZERO] is used so that no real [kotlinx.coroutines.delay]
 * occurs — all measurement time is pure policy orchestration.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
class RetryPolicyBenchmark {

    @Param("1", "3", "5")
    var maxAttempts: Int = 1

    private lateinit var policy: RetryPolicy

    @Setup
    fun setup() {
        policy = DefaultRetryPolicy(
            RetryPolicyConfig().apply {
                this.maxAttempts = maxAttempts
                backoffStrategy = FixedBackoff(Duration.ZERO)
            }
        )
    }

    @Benchmark
    fun happyPath(): Int = blockingUnconfined { policy.execute { unit() } }
}
