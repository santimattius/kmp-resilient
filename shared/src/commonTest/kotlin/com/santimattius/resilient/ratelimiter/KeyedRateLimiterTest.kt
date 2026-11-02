package com.santimattius.resilient.ratelimiter

import com.santimattius.resilient.circuitbreaker.TestTimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class KeyedRateLimiterTest {
    @Test
    fun `different keys have independent quotas and the same key shares one quota`() =
        runTest {
            val limiter =
                keyedRateLimiter<Request, String> {
                    keyProvider = { it.tenant }
                    maxCalls = 1
                    period = 1.seconds
                }

            assertEquals("first-a", limiter.execute(Request("a")) { "first-a" })
            assertEquals("first-b", limiter.execute(Request("b")) { "first-b" })
            assertFailsWith<RateLimitExceededException> {
                limiter.execute(Request("a")) { "second-a" }
            }
        }

    @Test
    fun `when capacity is reached the least recently used inactive bucket is evicted`() =
        runTest {
            val clock = TestTimeSource()
            val limiter =
                keyedRateLimiter<Request, String> {
                    keyProvider = { it.tenant }
                    maxCalls = 2
                    period = 1.seconds
                    maxBuckets = 2
                    timeSource = clock
                }

            limiter.execute(Request("a")) { "a-1" }
            clock.advanceTimeBy(1)
            limiter.execute(Request("b")) { "b-1" }
            clock.advanceTimeBy(1)
            limiter.execute(Request("b")) { "b-2" }
            assertFailsWith<RateLimitExceededException> {
                limiter.execute(Request("b")) { "b-exhausted" }
            }
            clock.advanceTimeBy(1)
            limiter.execute(Request("a")) { "a-2" }
            clock.advanceTimeBy(1)
            limiter.execute(Request("c")) { "c-1" }

            assertEquals("b-fresh", limiter.execute(Request("b")) { "b-fresh" })
        }

    @Test
    fun `an idle bucket expires using the injected time source`() =
        runTest {
            val clock = TestTimeSource()
            val limiter =
                keyedRateLimiter<Request, String> {
                    keyProvider = { it.tenant }
                    maxCalls = 1
                    period = 1.seconds
                    maxBuckets = 2
                    bucketIdleTtl = 100.milliseconds
                    timeSource = clock
                }

            limiter.execute(Request("a")) { "a" }
            clock.advanceTimeBy(100)

            assertEquals("a-fresh", limiter.execute(Request("a")) { "a-fresh" })
        }

    @Test
    fun `active buckets are never evicted and capacity errors omit raw keys`() =
        runTest {
            val limiter =
                keyedRateLimiter<Request, String> {
                    keyProvider = { it.tenant }
                    maxBuckets = 1
                }
            val active =
                async {
                    limiter.execute(Request("secret-active-tenant")) {
                        delay(10_000)
                        "done"
                    }
                }
            runCurrent()

            val exception =
                assertFailsWith<KeyedRateLimiterCapacityExceededException> {
                    limiter.execute(Request("other-tenant")) { "never" }
                }
            assertFalse(exception.message.orEmpty().contains("secret-active-tenant"))

            active.cancel()
            assertFailsWith<CancellationException> { active.await() }
            assertEquals("available", limiter.execute(Request("other-tenant")) { "available" })
        }

    @Test
    fun `waiting buckets are never evicted`() =
        runTest {
            val limiter =
                keyedRateLimiter<Request, String> {
                    keyProvider = { it.tenant }
                    maxCalls = 1
                    period = 1.seconds
                    timeoutWhenLimited = 2.seconds
                    maxBuckets = 1
                }
            limiter.execute(Request("tenant-a")) { "first" }
            val waiting = async { limiter.execute(Request("tenant-a")) { "waiting" } }
            runCurrent()

            assertFailsWith<KeyedRateLimiterCapacityExceededException> {
                limiter.execute(Request("tenant-b")) { "never" }
            }

            waiting.cancel()
            assertFailsWith<CancellationException> { waiting.await() }
            assertEquals("available", limiter.execute(Request("tenant-b")) { "available" })
        }

    @Test
    fun `key extraction failure creates no bucket or permit`() =
        runTest {
            val limiter =
                keyedRateLimiter<Request, String> {
                    keyProvider = { request ->
                        require(request.tenant != "invalid")
                        request.tenant
                    }
                    maxBuckets = 1
                }

            val active =
                async {
                    limiter.execute(Request("a")) {
                        delay(10_000)
                        "never"
                    }
                }
            runCurrent()

            assertFailsWith<IllegalArgumentException> {
                limiter.execute(Request("invalid")) { "never" }
            }
            assertFailsWith<KeyedRateLimiterCapacityExceededException> {
                limiter.execute(Request("b")) { "never" }
            }

            active.cancel()
            assertFailsWith<CancellationException> { active.await() }
        }

    @Test
    fun `cancelling an execution releases its bucket in finally`() =
        runTest {
            val limiter =
                keyedRateLimiter<Request, String> {
                    keyProvider = { it.tenant }
                    maxBuckets = 1
                }
            val cancelled =
                async {
                    limiter.execute(Request("a")) {
                        delay(10_000)
                        "never"
                    }
                }
            runCurrent()

            cancelled.cancel()
            assertFailsWith<CancellationException> { cancelled.await() }
            assertEquals("b", limiter.execute(Request("b")) { "b" })
        }

    @Test
    fun `invalid keyed rate limiter configuration is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            keyedRateLimiter<Request, String> { }
        }
        assertFailsWith<IllegalArgumentException> {
            keyedRateLimiter<Request, String> {
                keyProvider = { it.tenant }
                maxBuckets = 0
            }
        }
        assertFailsWith<IllegalArgumentException> {
            keyedRateLimiter<Request, String> {
                keyProvider = { it.tenant }
                bucketIdleTtl = 0.milliseconds
            }
        }
        assertFailsWith<IllegalArgumentException> {
            keyedRateLimiter<Request, String> {
                keyProvider = { it.tenant }
                maxCalls = 0
            }
        }
    }

    private data class Request(
        val tenant: String,
    )
}
