package com.santimattius.resilient.benchmark

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Scope
import kotlinx.benchmark.State

// Temporary noop benchmark for PR1 build wiring verification.
// This class will be removed in PR2 when real policy benchmark suites are added.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
class NoopBenchmark {
    @Benchmark
    fun noop(): Int = 1
}
