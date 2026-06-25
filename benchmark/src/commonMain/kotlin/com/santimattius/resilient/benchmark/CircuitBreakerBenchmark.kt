package com.santimattius.resilient.benchmark

import com.santimattius.resilient.circuitbreaker.CircuitBreaker
import com.santimattius.resilient.circuitbreaker.CircuitBreakerConfig
import com.santimattius.resilient.circuitbreaker.DefaultCircuitBreaker
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.coroutines.runBlocking

/**
 * Measures the overhead of [CircuitBreaker] in two states:
 *
 * - **closedExecute**: breaker is CLOSED with a very high failure threshold so it never trips
 *   during the benchmark. Measures the pass-through cost of the CLOSED fast-path.
 *
 * - **openReject**: breaker is pre-tripped to OPEN in [setup]. Measures the cost of the
 *   immediate rejection path (no block is executed, just the state check + exception creation).
 *   The exception is caught inside the body so JMH measures the full rejection cycle, not
 *   just exception propagation to the caller.
 *
 * Both breakers are created fresh in [setup] so benchmark iterations are stateless with respect
 * to each other. The open breaker is tripped with a very long [CircuitBreakerConfig.timeout]
 * so it does not transition back to HALF_OPEN during the benchmark run.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
class CircuitBreakerBenchmark {

    private lateinit var closedBreaker: CircuitBreaker
    private lateinit var openBreaker: CircuitBreaker

    @Setup
    fun setup() {
        // CLOSED breaker: threshold set high enough that it will never trip during the benchmark.
        closedBreaker = DefaultCircuitBreaker(
            CircuitBreakerConfig().apply {
                failureThreshold = Int.MAX_VALUE
            }
        )

        // OPEN breaker: failureThreshold=1 means one failure trips it.
        // timeout is very large so it does not transition to HALF_OPEN during the benchmark.
        openBreaker = DefaultCircuitBreaker(
            CircuitBreakerConfig().apply {
                failureThreshold = 1
                timeout = kotlin.time.Duration.INFINITE
            }
        )
        // Trip the breaker by executing a failing block once.
        runBlocking {
            runCatching { openBreaker.execute { error("trip") } }
        }
    }

    /**
     * Measures CLOSED-state pass-through overhead: the block executes and succeeds.
     */
    @Benchmark
    fun closedExecute(): Int = blockingUnconfined { closedBreaker.execute { unit() } }

    /**
     * Measures OPEN-state immediate-rejection overhead: the block is never executed.
     * The exception is caught inside the body so the cost measured is the full rejection cycle
     * (state check + exception creation) without propagation cost to the caller.
     *
     * Returns [Unit] explicitly — [Result] is an inline value class and its mangled JVM name
     * is rejected by JMH's bytecode generator (kotlinx-benchmark issue #92 / JMH limitation).
     */
    @Benchmark
    fun openReject() {
        blockingUnconfined { runCatching { openBreaker.execute { unit() } } }
    }
}
