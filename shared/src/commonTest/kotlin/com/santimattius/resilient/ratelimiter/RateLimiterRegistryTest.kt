package com.santimattius.resilient.ratelimiter

import com.santimattius.resilient.composition.ResilientScope
import com.santimattius.resilient.composition.resilient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RateLimiterRegistryTest {

    // ---------- Scenario 1: First registration creates the limiter ----------

    @Test
    fun `given empty registry when getOrCreate called then new limiter is created and returned`() {
        val registry = RateLimiterRegistry()

        val limiter = registry.getOrCreate(
            name = "payments",
            configure = {
                maxCalls = 10
                period = 1.seconds
            }
        )

        assertNotNull(limiter)
    }

    // Triangulation: a different name creates a different instance
    @Test
    fun `given empty registry when getOrCreate called with different names then distinct limiters created`() {
        val registry = RateLimiterRegistry()

        val paymentsLimiter = registry.getOrCreate("payments", configure = { maxCalls = 10 })
        val dbLimiter = registry.getOrCreate("db", configure = { maxCalls = 5 })

        assertTrue(paymentsLimiter !== dbLimiter, "Different names must produce distinct instances")
    }

    // ---------- Scenario 2: Subsequent call returns the same instance ----------

    @Test
    fun `given existing payments entry when getOrCreate called again then original instance is returned`() {
        val registry = RateLimiterRegistry()

        val first = registry.getOrCreate("payments", configure = { maxCalls = 10 })
        val second = registry.getOrCreate("payments", configure = { maxCalls = 999 })

        assertSame(first, second, "First-name-wins: same instance must be returned on second call")
    }

    // Triangulation: same instance is returned — original config (maxCalls=2) is preserved
    @Test
    fun `given existing limiter when getOrCreate called with different maxCalls then original config is preserved`() = runTest {
        val registry = RateLimiterRegistry()

        registry.getOrCreate("api", configure = {
            maxCalls = 2
            period = 1.seconds
        })
        // Second call with higher maxCalls — original config (maxCalls=2) wins
        val limiter = registry.getOrCreate("api", configure = {
            maxCalls = 100
            period = 10.seconds
        })

        // maxCalls=2 is in effect: executing twice should succeed, third must be rejected
        limiter.execute { "first" }
        limiter.execute { "second" }
        assertFailsWith<RateLimitExceededException> {
            limiter.execute { "third should be rejected" }
        }
    }

    // ---------- Scenario 3: Two policies share the same rate-limiter quota ----------

    @Test
    fun `given two policies with same named limiter when quota exhausted through policy A then policy B is rejected`() = runTest {
        val registry = RateLimiterRegistry()

        val limiterA = registry.getOrCreate("db", configure = {
            maxCalls = 5
            period = 1.seconds
        })
        val limiterB = registry.getOrCreate("db", configure = {
            maxCalls = 5   // ignored — same instance
            period = 1.seconds
        })

        // Both must be the exact same instance
        assertSame(limiterA, limiterB, "Same name must yield same instance for quota sharing")

        // Exhaust 5 calls through limiterA
        repeat(5) { limiterA.execute { "call" } }

        // Next call through limiterB (== limiterA) must be rejected
        assertFailsWith<RateLimitExceededException> {
            limiterB.execute { "should be rejected" }
        }
    }

    // ---------- Scenario 4: Combining rateLimiterNamed with rateLimiter throws ----------

    @Test
    fun `given builder when rateLimiter then rateLimiterNamed called then IllegalArgumentException thrown`() {
        val registry = RateLimiterRegistry()
        val scope = ResilientScope()

        assertFailsWith<IllegalArgumentException> {
            resilient(scope) {
                rateLimiter { maxCalls = 10 }
                rateLimiterNamed(registry, "db", configure = { maxCalls = 5 })
            }
        }
    }

    // Triangulation: reversed order — rateLimiterNamed first, then rateLimiter
    @Test
    fun `given builder when rateLimiterNamed then rateLimiter called then IllegalArgumentException thrown`() {
        val registry = RateLimiterRegistry()
        val scope = ResilientScope()

        assertFailsWith<IllegalArgumentException> {
            resilient(scope) {
                rateLimiterNamed(registry, "db", configure = { maxCalls = 5 })
                rateLimiter { maxCalls = 10 }
            }
        }
    }

    // ---------- Scenario 5: onLimited callback forwarded on rejection ----------

    @Test
    fun `given registry limiter with onLimited callback when rate limit exceeded then callback invoked`() = runTest {
        var callbackInvoked = false
        val registry = RateLimiterRegistry()

        val limiter = registry.getOrCreate(
            name = "payments",
            configure = {
                maxCalls = 1
                period = 1.seconds
            },
            onRateLimited = { _ ->
                callbackInvoked = true
            }
        )

        // Exhaust the quota
        limiter.execute { "first" }

        // This call exceeds the limit — callback must fire before exception is thrown
        assertFailsWith<RateLimitExceededException> {
            limiter.execute { "second" }
        }

        assertTrue(callbackInvoked, "onLimited callback must be invoked when rate limit is exceeded")
    }

    // Triangulation: callback receives a positive Duration
    @Test
    fun `given registry limiter with onLimited callback when rate limit exceeded then callback receives positive duration`() = runTest {
        var receivedDuration: kotlin.time.Duration? = null
        val registry = RateLimiterRegistry()

        val limiter = registry.getOrCreate(
            name = "api",
            configure = {
                maxCalls = 1
                period = 500.milliseconds
            },
            onRateLimited = { wait ->
                receivedDuration = wait
            }
        )

        limiter.execute { "first" }

        assertFailsWith<RateLimitExceededException> {
            limiter.execute { "second" }
        }

        val duration = receivedDuration
        assertNotNull(duration, "Callback must receive the wait Duration")
        assertTrue(
            duration > 0.milliseconds,
            "Callback Duration must be positive, got $duration"
        )
    }
}
