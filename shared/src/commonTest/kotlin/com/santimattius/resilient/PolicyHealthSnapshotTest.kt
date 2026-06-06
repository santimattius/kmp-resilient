package com.santimattius.resilient

import com.santimattius.resilient.composition.ResilientScope
import com.santimattius.resilient.composition.resilient
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for [PolicyHealthSnapshot] richer snapshot fields:
 * [RateLimiterSnapshot], [RetrySnapshot], [CacheSnapshot].
 */
class PolicyHealthSnapshotTest {

    // -------------------------------------------------------------------------
    // S1: rate-limiter snapshot populated when configured
    // -------------------------------------------------------------------------

    @Test
    fun `S1 given policy with rateLimiter maxCalls 10 after 3 calls when getHealthSnapshot then remainingCalls is 7`() =
        runTest {
            // given
            val scope = ResilientScope()
            val policy = resilient(scope) {
                rateLimiter {
                    maxCalls = 10
                    period = 60.seconds
                }
            }

            // when — consume 3 tokens
            repeat(3) { policy.execute { "ok" } }

            // then
            val snap = policy.getHealthSnapshot()
            val rl = assertNotNull(snap.rateLimiter, "rateLimiter snapshot must not be null")
            assertEquals(7, rl.remainingCalls)
        }

    @Test
    fun `S1 triangulation given policy with rateLimiter maxCalls 5 after 0 calls when getHealthSnapshot then remainingCalls equals maxCalls`() =
        runTest {
            // given
            val scope = ResilientScope()
            val policy = resilient(scope) {
                rateLimiter {
                    maxCalls = 5
                    period = 60.seconds
                }
            }

            // when — no calls made
            val snap = policy.getHealthSnapshot()

            // then
            val rl = assertNotNull(snap.rateLimiter)
            assertEquals(5, rl.remainingCalls)
        }

    // -------------------------------------------------------------------------
    // S2: null fields for unconfigured policies
    // -------------------------------------------------------------------------

    @Test
    fun `S2 given policy with only retry maxAttempts 3 when getHealthSnapshot then rateLimiter and cache are null and retry has maxAttempts 3`() =
        runTest {
            // given
            val scope = ResilientScope()
            val policy = resilient(scope) {
                retry {
                    maxAttempts = 3
                }
            }

            // when
            val snap = policy.getHealthSnapshot()

            // then
            assertNull(snap.rateLimiter, "rateLimiter must be null when not configured")
            assertNull(snap.cache, "cache must be null when not configured")
            val retry = assertNotNull(snap.retry, "retry snapshot must not be null")
            assertEquals(3, retry.maxAttempts)
        }

    @Test
    fun `S2 triangulation given policy with only timeout when getHealthSnapshot then all new snapshot fields are null`() =
        runTest {
            // given
            val scope = ResilientScope()
            val policy = resilient(scope) {
                timeout { timeout = 1.seconds }
            }

            // when
            val snap = policy.getHealthSnapshot()

            // then
            assertNull(snap.rateLimiter)
            assertNull(snap.retry)
            assertNull(snap.cache)
        }

    // -------------------------------------------------------------------------
    // S3: cache hit-rate calculation
    // -------------------------------------------------------------------------

    @Test
    fun `S3 given policy with cache after 2 hits and 8 misses when getHealthSnapshot then hitRate is 0_2`() =
        runTest {
            // given — use keyProvider so each unique-key call is a miss, revisiting is a hit
            var callKey = "key-0"
            val scope = ResilientScope()
            val policy = resilient(scope) {
                cache {
                    keyProvider = { callKey }
                    ttl = 60.seconds
                }
            }

            // 8 misses (unique keys 0..7)
            // Explicit type annotation forces T=String on all targets (Kotlin/Native requires
            // explicit T when the result is discarded to avoid inferring T=Unit)
            repeat(8) { i ->
                callKey = "key-$i"
                val result: String = policy.execute { "v$i" }
                assertEquals("v$i", result)
            }
            // 2 hits (revisit key-0 and key-1) — cache returns the stored value
            callKey = "key-0"
            assertEquals("v0", policy.execute { "hit0" })
            callKey = "key-1"
            assertEquals("v1", policy.execute { "hit1" })

            // then: 2 hits, 8 misses → hitRate = 2/10 = 0.2
            val snap = policy.getHealthSnapshot()
            val cache = assertNotNull(snap.cache, "cache snapshot must not be null")
            val expected = 0.2
            assertTrue(
                abs(cache.hitRate - expected) < 0.001,
                "hitRate expected $expected but was ${cache.hitRate}"
            )
        }

    @Test
    fun `S3 triangulation given cache with no calls when getHealthSnapshot then hitRate is NaN`() =
        runTest {
            // given
            val scope = ResilientScope()
            val policy = resilient(scope) {
                cache {
                    key = "k"
                    ttl = 60.seconds
                }
            }

            // when — no calls made
            val snap = policy.getHealthSnapshot()

            // then
            val cache = assertNotNull(snap.cache)
            assertTrue(cache.hitRate.isNaN(), "hitRate must be NaN when no calls made")
        }

    // -------------------------------------------------------------------------
    // S4: existing consumers unaffected — compile check via destructuring
    // -------------------------------------------------------------------------

    @Test
    fun `S4 given code that accesses only circuitBreaker and bulkhead fields when compiled and run then works without modification`() =
        runTest {
            // given
            val scope = ResilientScope()
            val policy = resilient(scope) {
                circuitBreaker { failureThreshold = 5; timeout = 10.seconds }
                bulkhead { maxConcurrentCalls = 2 }
            }

            // when — consumer only destructures the two original fields (source-compatible check)
            val snap = policy.getHealthSnapshot()
            val (cb, bh) = snap  // destructuring first two positional fields

            // then — compiles and returns correct types
            assertNotNull(cb)
            assertNotNull(bh)
        }
}
