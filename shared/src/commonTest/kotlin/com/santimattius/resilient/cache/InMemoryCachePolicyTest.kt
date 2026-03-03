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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
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

    @Test
    fun `given keyProvider when two different keys then two executions and distinct values`() = runTest {
        var callCount = 0
        val givenConfig = CacheConfig().apply {
            keyProvider = {
                callCount++
                if (callCount == 1) "key-a" else "key-b"
            }
            ttl = 10.seconds
        }
        val policy = InMemoryCachePolicy(givenConfig)
        var executions = 0

        val a = policy.execute { executions++; "val-a" }
        val b = policy.execute { executions++; "val-b" }

        assertEquals("val-a", a)
        assertEquals("val-b", b)
        assertEquals(2, executions)
        assertEquals(2, callCount)
    }

    @Test
    fun `given keyProvider when same key twice then one execution and cached return`() = runTest {
        val givenConfig = CacheConfig().apply {
            keyProvider = { "fixed-key" }
            ttl = 10.seconds
        }
        val policy = InMemoryCachePolicy(givenConfig)
        var executions = 0

        val first = policy.execute { executions++; "value-1" }
        val second = policy.execute { executions++; "value-2" }

        assertEquals("value-1", first)
        assertEquals("value-1", second)
        assertEquals(1, executions)
    }

    @Test
    fun `given cached value when invalidate then next execute recomputes`() = runTest {
        val givenConfig = CacheConfig().apply {
            key = "inv-key"
            ttl = 10.seconds
        }
        val policy = InMemoryCachePolicy(givenConfig)
        val handle = policy as com.santimattius.resilient.cache.CacheHandle
        var executions = 0

        val first = policy.execute {
            executions++
            "first"
        }
        handle.invalidate("inv-key")
        val second = policy.execute {
            executions++
            "second"
        }

        assertEquals("first", first)
        assertEquals("second", second)
        assertEquals(2, executions)
    }

    @Test
    fun `given multiple keys when invalidatePrefix then only matching entries removed`() = runTest {
        val givenConfig = CacheConfig().apply {
            key = "prefix:ignored"
            ttl = 10.seconds
        }
        val policy = InMemoryCachePolicy(givenConfig)
        val handle = policy as com.santimattius.resilient.cache.CacheHandle
        policy.execute { "v1" }
        givenConfig.key = "prefix:a"
        policy.execute { "v2" }
        givenConfig.key = "other:x"
        policy.execute { "v3" }

        handle.invalidatePrefix("prefix:")

        givenConfig.key = "prefix:ignored"
        var runs = 0
        val afterInv1 = policy.execute { runs++; "new1" }
        givenConfig.key = "prefix:a"
        val afterInv2 = policy.execute { runs++; "new2" }
        givenConfig.key = "other:x"
        val afterInv3 = policy.execute { runs++; "x" }

        assertEquals("new1", afterInv1)
        assertEquals("new2", afterInv2)
        assertEquals("v3", afterInv3)
        assertEquals(2, runs)
    }

    // --- Behavioral tests that validate the refactored execute() ---

    @Test
    fun `given cold cache when many concurrent executes then block is executed exactly once`() =
        runTest {
            // Validates thundering herd protection: concurrent cache misses must dedup via
            // `ongoing` and invoke block() only once, returning the same result to all callers.
            val givenConfig = CacheConfig().apply {
                key = "thundering-herd-key"
                ttl = 10.seconds
            }
            val policy = InMemoryCachePolicy(givenConfig)
            var executions = 0
            val gate = CompletableDeferred<Unit>()

            // All 10 coroutines enter execute() and suspend at gate.await() inside the block.
            // They must all attach to the same Deferred rather than each creating a new one.
            val jobs = (1..10).map {
                async {
                    policy.execute {
                        gate.await()
                        executions++
                        "result"
                    }
                }
            }

            gate.complete(Unit)
            val allResults = jobs.awaitAll()

            assertEquals(1, executions)
            allResults.forEach { assertEquals("result", it) }
        }

    @Test
    fun `given cold cache when concurrent executes and block throws then all callers receive error and ongoing is cleared`() =
        runTest {
            // Validates that on failure: (1) every concurrent caller receives the exception,
            // (2) ongoing is cleaned up so a subsequent call can retry successfully.
            val givenConfig = CacheConfig().apply {
                key = "concurrent-error-key"
                ttl = 10.seconds
            }
            val policy = InMemoryCachePolicy(givenConfig)
            var executions = 0
            val gate = CompletableDeferred<Unit>()

            val jobs = (1..5).map {
                async {
                    runCatching {
                        policy.execute {
                            gate.await()
                            executions++
                            throw IllegalStateException("boom")
                        }
                    }
                }
            }

            gate.complete(Unit)
            val results = jobs.awaitAll()

            // All callers must have received the failure — block ran exactly once
            assertEquals(1, executions)
            results.forEach { assertTrue(it.isFailure) }

            // ongoing must be cleared: the next call must re-execute block() successfully
            val recovery = policy.execute {
                executions++
                "recovered"
            }
            assertEquals("recovered", recovery)
            assertEquals(2, executions)
        }

    @Test
    fun `given onCacheMiss callback when cache miss then callback is invoked with the resolved key`() =
        runTest {
            var missedKey: String? = null
            val givenConfig = CacheConfig().apply {
                key = "miss-key"
                ttl = 10.seconds
            }
            val policy = InMemoryCachePolicy(
                config = givenConfig,
                onCacheMiss = { missedKey = it }
            )

            policy.execute { "value" }

            assertEquals("miss-key", missedKey)
        }

    @Test
    fun `given onCacheHit callback when cache is warm then callback is invoked with the resolved key`() =
        runTest {
            var hitKey: String? = null
            val givenConfig = CacheConfig().apply {
                key = "hit-key"
                ttl = 10.seconds
            }
            val policy = InMemoryCachePolicy(
                config = givenConfig,
                onCacheHit = { hitKey = it }
            )

            policy.execute { "value" } // populate cache — no hit yet
            assertNull(hitKey)

            policy.execute { "value" } // warm hit
            assertEquals("hit-key", hitKey)
        }
}