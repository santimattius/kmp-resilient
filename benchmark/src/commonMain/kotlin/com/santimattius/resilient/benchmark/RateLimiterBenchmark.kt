package com.santimattius.resilient.benchmark

import com.santimattius.resilient.composition.ResilientScope
import com.santimattius.resilient.composition.TypedResilientPolicy
import com.santimattius.resilient.composition.resilientFor
import com.santimattius.resilient.ratelimiter.DefaultRateLimiter
import com.santimattius.resilient.ratelimiter.KeyedRateLimiter
import com.santimattius.resilient.ratelimiter.RateLimiterConfig
import com.santimattius.resilient.ratelimiter.keyedRateLimiter
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.TearDown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

/** Effectively unbounded quota so no benchmark ever hits a rejection or a wait. */
internal const val UNBOUNDED_CALLS = 1_000_000_000

/**
 * Uncontended cost of the global [DefaultRateLimiter] and of [KeyedRateLimiter].
 *
 * [keyCount] is the number of distinct keys cycled through, so `1` measures a single hot bucket and
 * `64` adds bucket lookup across a populated map. Quotas are unbounded: this is the permit-acquisition
 * path, not rejection.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
class RateLimiterBenchmark {

    @Param("1", "64")
    var keyCount: Int = 1

    private lateinit var global: DefaultRateLimiter
    private lateinit var keyed: KeyedRateLimiter<Int, Int>
    private var counter = 0

    @Setup
    fun setup() {
        global = DefaultRateLimiter(
            RateLimiterConfig().apply {
                maxCalls = UNBOUNDED_CALLS
                period = 1.seconds
            }
        )
        keyed = keyedRateLimiter {
            keyProvider = { it }
            maxCalls = UNBOUNDED_CALLS
            period = 1.seconds
        }
    }

    @Benchmark
    fun globalLimiter(): Int = blockingUnconfined { global.execute { unit() } }

    @Benchmark
    fun keyedLimiter(): Int = blockingUnconfined {
        keyed.execute((counter++ and Int.MAX_VALUE) % keyCount) { unit() }
    }
}

/**
 * Contended cost: [callers] coroutines acquire a permit concurrently on `Dispatchers.Default`.
 *
 * Compares the global limiter, a keyed limiter where every caller shares one key (one contended
 * bucket) and a keyed limiter where each caller has its own key (contention only on the registry
 * lock). The numbers include coroutine dispatch, so compare the three against each other, not
 * against [RateLimiterBenchmark].
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
class RateLimiterContentionBenchmark {

    @Param("8")
    var callers: Int = 8

    private lateinit var global: DefaultRateLimiter
    private lateinit var keyed: KeyedRateLimiter<Int, Int>

    @Setup
    fun setup() {
        global = DefaultRateLimiter(
            RateLimiterConfig().apply {
                maxCalls = UNBOUNDED_CALLS
                period = 1.seconds
            }
        )
        keyed = keyedRateLimiter {
            keyProvider = { it }
            maxCalls = UNBOUNDED_CALLS
            period = 1.seconds
        }
    }

    private suspend fun fanOut(call: suspend (Int) -> Int): Int =
        withContext(Dispatchers.Default) {
            List(callers) { index -> async { call(index) } }.awaitAll().size
        }

    @Benchmark
    fun globalContended(): Int = blockingUnconfined { fanOut { global.execute { unit() } } }

    @Benchmark
    fun keyedSameKey(): Int = blockingUnconfined { fanOut { keyed.execute(0) { unit() } } }

    @Benchmark
    fun keyedDistinctKeys(): Int = blockingUnconfined { fanOut { index -> keyed.execute(index) { unit() } } }
}

/**
 * Global + keyed limiting declared in one typed policy versus the same two limiters nested by hand.
 * This is the migration path documented in `docs/patterns/typed-policies.md`; it shows what the single
 * `execute(input)` call costs relative to `global.execute { keyed.execute(input) { } }`.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
class GlobalAndKeyedCompositionBenchmark {

    @Param("64")
    var keyCount: Int = 64

    private lateinit var scope: ResilientScope
    private lateinit var typed: TypedResilientPolicy<Int>
    private lateinit var nestedGlobal: DefaultRateLimiter
    private lateinit var nestedKeyed: KeyedRateLimiter<Int, Int>
    private var counter = 0

    @Setup
    fun setup() {
        scope = ResilientScope()
        typed = resilientFor<Int>(scope) {
            rateLimiter {
                maxCalls = UNBOUNDED_CALLS
                period = 1.seconds
            }
            keyedRateLimiter<Int> {
                keyProvider = { it }
                maxCalls = UNBOUNDED_CALLS
                period = 1.seconds
            }
        }
        nestedGlobal = DefaultRateLimiter(
            RateLimiterConfig().apply {
                maxCalls = UNBOUNDED_CALLS
                period = 1.seconds
            }
        )
        nestedKeyed = keyedRateLimiter {
            keyProvider = { it }
            maxCalls = UNBOUNDED_CALLS
            period = 1.seconds
        }
    }

    @TearDown
    fun tearDown() {
        typed.close()
        scope.close()
    }

    private fun nextKey(): Int = (counter++ and Int.MAX_VALUE) % keyCount

    @Benchmark
    fun typedGlobalAndKeyed(): Int = blockingUnconfined { typed.execute(nextKey()) { unit() } }

    @Benchmark
    fun nestedGlobalAndKeyed(): Int = blockingUnconfined {
        val key = nextKey()
        nestedGlobal.execute { nestedKeyed.execute(key) { unit() } }
    }
}
