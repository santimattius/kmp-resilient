package com.santimattius.resilient.cache

import com.santimattius.resilient.composition.ResilientScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.seconds

class InMemoryCachePolicyTest {

    @Test
    fun `given cached value when execute twice before ttl expires then returns stored result`() =
        runTest {
            val givenConfig = CacheConfig().apply {
                key = "cached-key"
                ttl = 5.seconds
            }
            val policy = InMemoryCachePolicy(givenConfig)
            var executions = 0

            val first = policy.execute {
                executions++
                "value"
            }
            val second = policy.execute {
                executions++
                "new-value"
            }

            assertEquals("value", first)
            assertEquals("value", second)
            assertEquals(1, executions)
        }

    @Test
    fun `given zero ttl when execute twice then recomputes value`() = runTest {
        val givenConfig = CacheConfig().apply {
            key = "ephemeral-key"
            ttl = ZERO
        }
        val policy = InMemoryCachePolicy(givenConfig)
        var executions = 0

        val first = policy.execute {
            executions++
            "first"
        }
        val second = policy.execute {
            executions++
            "second"
        }

        assertEquals("first", first)
        assertEquals("second", second)
        assertEquals(2, executions)
    }

    @Test
    fun `given block throws when execute then error is not cached`() = runTest {
        val givenConfig = CacheConfig().apply {
            key = "error-key"
            ttl = 5.seconds
        }
        val policy = InMemoryCachePolicy(givenConfig)
        var executions = 0

        assertFailsWith<IllegalStateException> {
            policy.execute {
                executions++
                throw IllegalStateException("boom")
            }
        }

        val result = policy.execute {
            executions++
            "recovered"
        }

        assertEquals("recovered", result)
        assertEquals(2, executions)
    }

    @Test
    fun `given cancellation during execution when execute again then recomputes value`() = runTest {
        val givenConfig = CacheConfig().apply {
            key = "cancel-key"
            ttl = 5.seconds
        }
        val policy = InMemoryCachePolicy(givenConfig)
        var executions = 0
        val gate = CompletableDeferred<Unit>()
        val blocker = CompletableDeferred<String>()

        val deferred = async {
            policy.execute {
                executions++
                gate.complete(Unit)
                blocker.await()
            }
        }

        gate.await()
        deferred.cancel()
        assertFailsWith<CancellationException> { deferred.await() }

        val result = policy.execute {
            executions++
            "after-cancel"
        }

        assertEquals("after-cancel", result)
        assertEquals(2, executions)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `given cleanup interval and provided test scope when executing then operates without leaks and recomputes on zero ttl`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val providedScope = ResilientScope(testDispatcher)

        val givenConfig = CacheConfig().apply {
            key = "scoped-key"
            ttl = ZERO
            cleanupInterval = Duration.parse("1ms")
        }

        val policy = InMemoryCachePolicy(givenConfig, providedScope)

        var executions = 0
        val first = policy.execute {
            executions++
            "v1"
        }
        testScheduler.advanceTimeBy(1)
        val second = policy.execute {
            executions++
            "v2"
        }

        assertEquals("v1", first)
        assertEquals("v2", second)
        assertEquals(2, executions)

        // cancel external scope and ensure policy can still be closed safely
        providedScope.close()
        policy.close()
        policy.close() // idempotent close
    }

    @Test
    fun `given cached value when many concurrent executes then block is not re-executed`() = runTest {
        val givenConfig = CacheConfig().apply {
            key = "concurrent-key"
            ttl = 10.seconds
        }
        val policy = InMemoryCachePolicy(givenConfig)
        var executions = 0

        // Warm the cache first so concurrent executions hit the cache
        val warmed = policy.execute {
            executions++
            "result"
        }
        assertEquals("result", warmed)
        executions = 0

        val results = coroutineScope {
            (1..10).map {
                async {
                    policy.execute {
                        executions++ // should not be invoked due to cached value
                        "new"
                    }
                }
            }
        }

        val allResults = results.awaitAll()

        assertEquals(0, executions)
        allResults.forEach {
            assertEquals("result", it)
        }
    }
}