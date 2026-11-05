package com.santimattius.resilient.benchmark

import com.santimattius.resilient.ResilientPolicy
import com.santimattius.resilient.composition.ResilientScope
import com.santimattius.resilient.composition.TypedResilientPolicy
import com.santimattius.resilient.composition.resilient
import com.santimattius.resilient.composition.resilientFor
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.TearDown
import kotlin.time.Duration.Companion.seconds

/**
 * Compares the composed legacy pipeline (`resilient { }`) with the typed one (`resilientFor<Input> { }`)
 * under an identical configuration, so the difference is the cost of carrying the explicit input.
 *
 * Both run the same shared engine. Policies are configured so nothing ever triggers: the timeout is
 * far above the workload, the retry never retries and the rate limit is effectively unbounded. What is
 * measured is pure pipeline orchestration, not waiting or rejection.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
class PipelineBenchmark {

    private lateinit var scope: ResilientScope
    private lateinit var legacy: ResilientPolicy
    private lateinit var typed: TypedResilientPolicy<Int>

    @Setup
    fun setup() {
        scope = ResilientScope()
        legacy = resilient(scope) {
            timeout { timeout = 30.seconds }
            retry { maxAttempts = 3 }
            rateLimiter {
                maxCalls = UNBOUNDED_CALLS
                period = 1.seconds
            }
        }
        typed = resilientFor<Int>(scope) {
            timeout { timeout = 30.seconds }
            retry { maxAttempts = 3 }
            rateLimiter {
                maxCalls = UNBOUNDED_CALLS
                period = 1.seconds
            }
        }
    }

    @TearDown
    fun tearDown() {
        legacy.close()
        typed.close()
        scope.close()
    }

    @Benchmark
    fun legacyPipeline(): Int = blockingUnconfined { legacy.execute { unit() } }

    @Benchmark
    fun typedPipeline(): Int = blockingUnconfined { typed.execute(1) { unit() } }
}
