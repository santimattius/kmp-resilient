package com.santimattius.resilient.benchmark

import com.santimattius.resilient.composition.ResilientScope
import com.santimattius.resilient.composition.TypedResilientPolicy
import com.santimattius.resilient.composition.resilientFor
import com.santimattius.resilient.ratelimiter.DefaultRateLimiter
import com.santimattius.resilient.ratelimiter.KeyedRateLimiter
import com.santimattius.resilient.ratelimiter.RateLimiterConfig
import com.santimattius.resilient.ratelimiter.keyedRateLimiter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

private fun createGlobalLimiter() =
    DefaultRateLimiter(
        RateLimiterConfig().apply {
            maxCalls = UNBOUNDED_CALLS
            period = 1.seconds
        },
    )

private fun createKeyedLimiter(): KeyedRateLimiter<Int, Int> =
    keyedRateLimiter {
        keyProvider = { it }
        maxCalls = UNBOUNDED_CALLS
        period = 1.seconds
    }

internal class RateLimiterFixture {
    private lateinit var global: DefaultRateLimiter
    private lateinit var keyed: KeyedRateLimiter<Int, Int>
    private var counter = 0
    var keyCount = 1

    fun setup() {
        global = createGlobalLimiter()
        keyed = createKeyedLimiter()
    }

    suspend fun globalLimiter(): Int = global.execute { unit() }

    suspend fun keyedLimiter(): Int = keyed.execute((counter++ and Int.MAX_VALUE) % keyCount) { unit() }
}

internal class ContentionFixture {
    private lateinit var global: DefaultRateLimiter
    private lateinit var keyed: KeyedRateLimiter<Int, Int>
    var callers = 8

    fun setup() {
        global = createGlobalLimiter()
        keyed = createKeyedLimiter()
    }

    private suspend fun fanOut(call: suspend (Int) -> Int): Int =
        withContext(Dispatchers.Default) {
            List(callers) { index -> async { call(index) } }.awaitAll().size
        }

    suspend fun globalContended(): Int = fanOut { global.execute { unit() } }

    suspend fun keyedSameKey(): Int = fanOut { keyed.execute(0) { unit() } }

    suspend fun keyedDistinctKeys(): Int = fanOut { index -> keyed.execute(index) { unit() } }
}

internal class CompositionFixture {
    private lateinit var scope: ResilientScope
    private lateinit var typed: TypedResilientPolicy<Int>
    private lateinit var global: DefaultRateLimiter
    private lateinit var keyed: KeyedRateLimiter<Int, Int>
    private var counter = 0
    var keyCount = 64

    fun setup() {
        scope = ResilientScope()
        typed =
            resilientFor<Int>(scope) {
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
        global = createGlobalLimiter()
        keyed = createKeyedLimiter()
    }

    fun tearDown() {
        typed.close()
        scope.close()
    }

    private fun nextKey() = (counter++ and Int.MAX_VALUE) % keyCount

    suspend fun typedGlobalAndKeyed(): Int = typed.execute(nextKey()) { unit() }

    suspend fun nestedGlobalAndKeyed(): Int {
        val key = nextKey()
        return global.execute { keyed.execute(key) { unit() } }
    }
}
