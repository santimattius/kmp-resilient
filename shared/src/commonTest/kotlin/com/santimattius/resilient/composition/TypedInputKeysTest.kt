package com.santimattius.resilient.composition

import app.cash.turbine.test
import com.santimattius.resilient.telemetry.ResilientEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class TypedInputKeysTest {

    private data class UserId(val value: String)

    @Test
    fun `given cache with keyFor when inputs differ then entries are not shared`() = runTest {
        val policy = resilientFor<UserId>(ResilientScope()) {
            cache { ttl = 1.minutes }
            cacheKeyFor { "user:${it.value}" }
        }
        var calls = 0

        val a = policy.execute(UserId("a")) { calls++; "data-${it.value}" }
        val b = policy.execute(UserId("b")) { calls++; "data-${it.value}" }

        assertEquals("data-a", a)
        assertEquals("data-b", b)
        assertEquals(2, calls)
    }

    @Test
    fun `given cache with keyFor when same input repeats then second call hits`() = runTest {
        val policy = resilientFor<UserId>(ResilientScope()) {
            cache { ttl = 1.minutes }
            cacheKeyFor { "user:${it.value}" }
        }
        var calls = 0

        policy.execute(UserId("a")) { calls++; "x" }
        policy.execute(UserId("a")) { calls++; "x" }

        assertEquals(1, calls)
    }

    @Test
    fun `given cache with keyFor when events observed then derived key is never emitted`() = runTest {
        val policy = resilientFor<UserId>(ResilientScope()) {
            cache { ttl = 1.minutes }
            cacheKeyFor { "secret-${it.value}" }
        }
        policy.events.test {
            policy.execute(UserId("a")) { "x" }
            policy.execute(UserId("a")) { "x" }
            val seen = mutableListOf<ResilientEvent>()
            repeat(4) { seen += awaitItem() }
            assertTrue(seen.any { it is ResilientEvent.CacheMiss })
            assertTrue(seen.any { it is ResilientEvent.CacheHit })
            assertFalse(seen.joinToString().contains("secret"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `given coalesce with keyFor when inputs differ then blocks run separately and same input joins`() =
        runTest {
            val policy = resilientFor<UserId>(ResilientScope()) {
                coalesce { }
                coalesceKeyFor { it.value }
            }
            val gate = CompletableDeferred<Unit>()
            var calls = 0
            val a1 = async { policy.execute(UserId("a")) { calls++; gate.await(); "r-${it.value}" } }
            val a2 = async { policy.execute(UserId("a")) { calls++; gate.await(); "r-${it.value}" } }
            val b = async { policy.execute(UserId("b")) { calls++; gate.await(); "r-${it.value}" } }
            repeat(5) { yield() }
            gate.complete(Unit)

            assertEquals("r-a", a1.await())
            assertEquals("r-a", a2.await())
            assertEquals("r-b", b.await())
            assertEquals(2, calls)
        }

    @Test
    fun `given cache without keyFor when built then it fails closed`() {
        val e = assertFailsWith<IllegalArgumentException> {
            resilientFor<UserId>(ResilientScope()) { cache { ttl = 1.minutes } }
        }
        assertTrue(e.message!!.contains("cacheKeyFor"))
    }

    @Test
    fun `given coalesce without keyFor when built then it fails closed`() {
        val e = assertFailsWith<IllegalArgumentException> {
            resilientFor<UserId>(ResilientScope()) { coalesce { } }
        }
        assertTrue(e.message!!.contains("coalesceKeyFor"))
    }

    @Test
    fun `given template with cache when included with local keyFor then keys are per input`() = runTest {
        val template = resilienceTemplate { cache { ttl = 1.minutes } }
        val policy = resilientFor<UserId>(ResilientScope()) {
            include(template)
            cacheKeyFor { it.value }
        }
        var calls = 0

        policy.execute(UserId("a")) { calls++; "x" }
        policy.execute(UserId("b")) { calls++; "x" }
        policy.execute(UserId("a")) { calls++; "x" }

        assertEquals(2, calls)
    }

    @Test
    fun `given template with cache when included without keyFor then typed build fails closed`() {
        val template = resilienceTemplate { cache { ttl = 1.minutes } }
        assertFailsWith<IllegalArgumentException> {
            resilientFor<UserId>(ResilientScope()) { include(template) }
        }
    }

    @Test
    fun `given legacy resilient cache when executed then behavior is unchanged`() = runTest {
        val policy = resilient(ResilientScope()) { cache { ttl = 1.minutes; key = "k" } }
        var calls = 0
        policy.execute { calls++; "x" }
        policy.execute { calls++; "x" }
        assertEquals(1, calls)
    }
}
