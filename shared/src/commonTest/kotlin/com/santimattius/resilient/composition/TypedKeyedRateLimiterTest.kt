package com.santimattius.resilient.composition

import com.santimattius.resilient.circuitbreaker.TestTimeSource
import com.santimattius.resilient.annotations.ResilientExperimentalApi
import com.santimattius.resilient.ratelimiter.KeyedRateLimiterCapacityExceededException
import com.santimattius.resilient.ratelimiter.KeyedRateLimiterRegistry
import com.santimattius.resilient.ratelimiter.RateLimitExceededException
import com.santimattius.resilient.telemetry.ResilientEvent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

@OptIn(ResilientExperimentalApi::class, ExperimentalCoroutinesApi::class)
class TypedKeyedRateLimiterTest {

    private data class Request(val tenant: String, val payload: String = "p")

    @Test
    fun `given global and keyed limiters when executed once then both apply in a single call`() = runTest {
        val policy = resilientFor<Request>(ResilientScope()) {
            rateLimiter { maxCalls = 3; period = 10.minutes }
            keyedRateLimiter<String> {
                keyProvider = { it.tenant }
                maxCalls = 1
                period = 10.minutes
            }
        }

        assertEquals("a", policy.execute(Request("a")) { it.tenant })
        assertEquals("b", policy.execute(Request("b")) { it.tenant })
        // Same key exhausted its own quota while the global quota still has one permit.
        assertFailsWith<RateLimitExceededException> { policy.execute(Request("a")) { "x" } }
        // Rejected by the keyed limiter inside the global one: the global permit was still consumed.
        assertEquals(0, policy.getHealthSnapshot().rateLimiter!!.remainingCalls)
    }

    @Test
    fun `given default order when global limiter rejects then keyed bucket is not created`() = runTest {
        val policy = resilientFor<Request>(ResilientScope()) {
            rateLimiter { maxCalls = 1; period = 10.minutes }
            keyedRateLimiter<String> {
                keyProvider = { it.tenant }
                maxCalls = 5
                period = 10.minutes
            }
        }

        policy.execute(Request("a")) { }
        assertFailsWith<RateLimitExceededException> { policy.execute(Request("b")) { } }

        assertEquals(1, policy.getKeyedRateLimiterSnapshot()!!.residentBuckets)
    }

    @Test
    fun `given keyed configured outside global when global rejects then keyed bucket exists`() = runTest {
        val policy = resilientFor<Request>(ResilientScope()) {
            compositionOrder(
                listOf(OrderablePolicyType.KEYED_RATE_LIMITER, OrderablePolicyType.RATE_LIMITER)
            )
            rateLimiter { maxCalls = 1; period = 10.minutes }
            keyedRateLimiter<String> {
                keyProvider = { it.tenant }
                maxCalls = 5
                period = 10.minutes
            }
        }

        policy.execute(Request("a")) { }
        assertFailsWith<RateLimitExceededException> { policy.execute(Request("b")) { } }

        assertEquals(2, policy.getKeyedRateLimiterSnapshot()!!.residentBuckets)
    }

    @Test
    fun `given both limiters when each rejects then telemetry is separate`() = runTest {
        val policy = resilientFor<Request>(ResilientScope()) {
            rateLimiter { maxCalls = 2; period = 10.minutes }
            keyedRateLimiter<String> {
                keyProvider = { it.tenant }
                maxCalls = 1
                period = 10.minutes
            }
        }
        val seen = mutableListOf<ResilientEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { policy.events.collect { seen += it } }

        policy.execute(Request("a")) { }
        assertFailsWith<RateLimitExceededException> { policy.execute(Request("a")) { } }
        assertFailsWith<RateLimitExceededException> { policy.execute(Request("b")) { } }
        runCurrent()
        job.cancelAndJoin()

        assertEquals(1, seen.count { it is ResilientEvent.KeyedRateLimited })
        assertEquals(1, seen.count { it is ResilientEvent.RateLimited })
    }

    @Test
    fun `given two policies sharing a named keyed limiter when the second is limited then only the second emits KeyedRateLimited`() =
        runTest {
            val registry = KeyedRateLimiterRegistry()
            fun build() = resilientFor<Request>(ResilientScope()) {
                keyedRateLimiterNamed<String>(registry, "api") {
                    keyProvider = { it.tenant }
                    maxCalls = 1
                    period = 10.minutes
                }
            }
            val first = build()
            val second = build()
            val firstSeen = mutableListOf<ResilientEvent>()
            val secondSeen = mutableListOf<ResilientEvent>()
            val firstJob = launch(UnconfinedTestDispatcher(testScheduler)) { first.events.collect { firstSeen += it } }
            val secondJob = launch(UnconfinedTestDispatcher(testScheduler)) { second.events.collect { secondSeen += it } }

            second.execute(Request("a")) { }
            assertFailsWith<RateLimitExceededException> { second.execute(Request("a")) { } }
            runCurrent()
            firstJob.cancelAndJoin()
            secondJob.cancelAndJoin()

            assertEquals(1, secondSeen.count { it is ResilientEvent.KeyedRateLimited })
            assertEquals(0, firstSeen.count { it is ResilientEvent.KeyedRateLimited })
        }

    @Test
    fun `given keyed rejection when executed then exactly one operation outcome is emitted`() = runTest {
        val policy = resilientFor<Request>(ResilientScope()) {
            keyedRateLimiter<String> {
                keyProvider = { it.tenant }
                maxCalls = 1
                period = 10.minutes
            }
        }
        val seen = mutableListOf<ResilientEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { policy.events.collect { seen += it } }

        policy.execute(Request("a")) { }
        assertFailsWith<RateLimitExceededException> { policy.execute(Request("a")) { } }
        runCurrent()
        job.cancelAndJoin()

        assertEquals(1, seen.count { it is ResilientEvent.OperationSuccess })
        assertEquals(1, seen.count { it is ResilientEvent.OperationFailure })
    }

    @Test
    fun `given sensitive input when limited then events snapshots and exceptions never expose input or key`() =
        runTest {
            val secret = "tenant-SECRET-42"
            val policy = resilientFor<Request>(ResilientScope()) {
                rateLimiter { maxCalls = 10; period = 10.minutes }
                keyedRateLimiter<String> {
                    keyProvider = { it.tenant }
                    maxCalls = 1
                    maxBuckets = 1
                    period = 10.minutes
                }
            }
            val seen = mutableListOf<ResilientEvent>()
            val job = launch(UnconfinedTestDispatcher(testScheduler)) { policy.events.collect { seen += it } }

            policy.execute(Request(secret, payload = "PAYLOAD-SECRET")) { }
            val limited = assertFailsWith<RateLimitExceededException> {
                policy.execute(Request(secret, payload = "PAYLOAD-SECRET")) { }
            }
            // Capacity: a different key cannot evict the busy bucket; it must not leak either key.
            val rendered = buildString {
                seen.forEach { append(it.toString()) }
                append(policy.getHealthSnapshot().toString())
                append(policy.getKeyedRateLimiterSnapshot().toString())
                append(limited.toString())
            }
            runCurrent()
            job.cancelAndJoin()

            assertFalse(rendered.contains("SECRET"), rendered)
            assertFalse(KeyedRateLimiterCapacityExceededException().toString().contains("SECRET"))
        }

    @Test
    fun `given keyed snapshot when keys are used then it reports only aggregate counts`() = runTest {
        val policy = resilientFor<Request>(ResilientScope()) {
            keyedRateLimiter<String> {
                keyProvider = { it.tenant }
                maxBuckets = 7
                period = 10.minutes
            }
        }
        policy.execute(Request("a")) { }
        policy.execute(Request("b")) { }

        val snapshot = assertNotNull(policy.getKeyedRateLimiterSnapshot())
        assertEquals(2, snapshot.residentBuckets)
        assertEquals(7, snapshot.maxBuckets)
        assertNull(policy.getHealthSnapshot().rateLimiter)
    }

    @Test
    fun `given policy without keyed limiter when snapshot requested then it is null`() = runTest {
        val policy = resilientFor<Request>(ResilientScope()) { rateLimiter { maxCalls = 1 } }

        assertNull(policy.getKeyedRateLimiterSnapshot())
    }

    @Test
    fun `given template with global limiter when included in two typed policies then quotas are independent`() =
        runTest {
            val template = resilienceTemplate { rateLimiter { maxCalls = 1; period = 10.minutes } }
            val scope = ResilientScope()
            val first = resilientFor<Request>(scope) { include(template) }
            val second = resilientFor<Request>(scope) {
                include(template)
                keyedRateLimiter<String> { keyProvider = { it.tenant }; period = 10.minutes }
            }

            first.execute(Request("a")) { }
            second.execute(Request("a")) { }

            assertFailsWith<RateLimitExceededException> { first.execute(Request("a")) { } }
            assertFailsWith<RateLimitExceededException> { second.execute(Request("a")) { } }
        }

    @Test
    fun `given ordinary builds when same key used then keyed state is independent`() = runTest {
        fun build() = resilientFor<Request>(ResilientScope()) {
            keyedRateLimiter<String> { keyProvider = { it.tenant }; maxCalls = 1; period = 10.minutes }
        }
        val first = build()
        val second = build()

        first.execute(Request("a")) { }
        second.execute(Request("a")) { }

        assertFailsWith<RateLimitExceededException> { first.execute(Request("a")) { } }
    }

    @Test
    fun `given named keyed registry when two builds use same name then quota is shared and survives close`() =
        runTest {
            val registry = KeyedRateLimiterRegistry()
            fun build() = resilientFor<Request>(ResilientScope()) {
                keyedRateLimiterNamed<String>(registry, "tenants") {
                    keyProvider = { it.tenant }
                    maxCalls = 1
                    period = 10.minutes
                }
            }
            val first = build()
            val second = build()

            first.execute(Request("a")) { }
            first.close()

            assertFailsWith<RateLimitExceededException> { second.execute(Request("a")) { } }
            // Registry state is still alive for a brand new build after close.
            assertFailsWith<RateLimitExceededException> { build().execute(Request("a")) { } }
        }

    @Test
    fun `given named keyed registry when different names then quotas are distinct`() = runTest {
        val registry = KeyedRateLimiterRegistry()
        val one = resilientFor<Request>(ResilientScope()) {
            keyedRateLimiterNamed<String>(registry, "one") { keyProvider = { it.tenant }; maxCalls = 1; period = 10.minutes }
        }
        val two = resilientFor<Request>(ResilientScope()) {
            keyedRateLimiterNamed<String>(registry, "two") { keyProvider = { it.tenant }; maxCalls = 1; period = 10.minutes }
        }

        one.execute(Request("a")) { }
        two.execute(Request("a")) { }
    }

    @Test
    fun `given keyed limiter without key provider when building then it fails fast`() {
        assertFailsWith<IllegalArgumentException> {
            resilientFor<Request>(ResilientScope()) { keyedRateLimiter<String> { maxCalls = 1 } }
        }
    }

    @Test
    fun `given inline and named keyed limiter or duplicates when configuring then it is rejected`() {
        val registry = KeyedRateLimiterRegistry()
        assertFailsWith<IllegalArgumentException> {
            resilientFor<Request>(ResilientScope()) {
                keyedRateLimiter<String> { keyProvider = { it.tenant } }
                keyedRateLimiterNamed<String>(registry, "x") { keyProvider = { it.tenant } }
            }
        }
        assertFailsWith<IllegalArgumentException> {
            resilientFor<Request>(ResilientScope()) {
                keyedRateLimiter<String> { keyProvider = { it.tenant } }
                keyedRateLimiter<String> { keyProvider = { it.tenant } }
            }
        }
    }

    @Test
    fun `given cancelled execution inside keyed limiter when retried then bucket is released`() = runTest {
        val clock = TestTimeSource()
        val policy = resilientFor<Request>(ResilientScope()) {
            keyedRateLimiter<String> {
                keyProvider = { it.tenant }
                maxBuckets = 1
                maxCalls = 5
                period = 10.minutes
                timeSource = clock
            }
        }
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            policy.execute(Request("a")) { awaitCancellation() }
        }
        runCurrent()
        job.cancelAndJoin()

        // maxBuckets = 1: a leaked active reservation would make a new key fail with capacity exceeded.
        // Once released, the bucket is only discarded after its window ends.
        clock.advanceTimeBy(10.minutes.inWholeMilliseconds)
        policy.execute(Request("b")) { }
        assertTrue(policy.getKeyedRateLimiterSnapshot()!!.residentBuckets <= 1)
    }

    @Test
    fun `given legacy resilient with rate limiter when composed then behavior is unchanged`() = runTest {
        val policy = resilient(ResilientScope()) { rateLimiter { maxCalls = 1; period = 10.minutes } }

        policy.execute { }
        assertFailsWith<RateLimitExceededException> { policy.execute { } }
    }
}
