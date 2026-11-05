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
    fun `when capacity is reached the least recently used inactive bucket with a closed window is evicted`() =
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
            clock.advanceTimeBy(1_000)
            limiter.execute(Request("a")) { "a-2" }
            clock.advanceTimeBy(1_000)
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
                    period = 100.milliseconds
                    maxBuckets = 2
                    bucketIdleTtl = 100.milliseconds
                    timeSource = clock
                }

            limiter.execute(Request("a")) { "a" }
            clock.advanceTimeBy(100)

            assertEquals("a-fresh", limiter.execute(Request("a")) { "a-fresh" })
        }

    @Test
    fun `an exhausted bucket is not evicted by idle ttl before its window ends`() =
        runTest {
            val clock = TestTimeSource()
            val limiter =
                keyedRateLimiter<Request, String> {
                    keyProvider = { it.tenant }
                    maxCalls = 1
                    period = 1.seconds
                    bucketIdleTtl = 100.milliseconds
                    timeSource = clock
                }

            limiter.execute(Request("a")) { "a-1" }
            clock.advanceTimeBy(500)

            assertFailsWith<RateLimitExceededException> {
                limiter.execute(Request("a")) { "a-2" }
            }
            clock.advanceTimeBy(500)
            assertEquals("a-3", limiter.execute(Request("a")) { "a-3" })
        }

    @Test
    fun `a partially used bucket keeps its consumed permits until its window ends`() =
        runTest {
            val clock = TestTimeSource()
            val limiter =
                keyedRateLimiter<Request, String> {
                    keyProvider = { it.tenant }
                    maxCalls = 2
                    period = 1.seconds
                    bucketIdleTtl = 100.milliseconds
                    timeSource = clock
                }

            limiter.execute(Request("a")) { "a-1" }
            clock.advanceTimeBy(500)
            limiter.execute(Request("a")) { "a-2" }

            assertFailsWith<RateLimitExceededException> {
                limiter.execute(Request("a")) { "a-3" }
            }
        }

    @Test
    fun `capacity never evicts a bucket whose window is still open`() =
        runTest {
            val clock = TestTimeSource()
            val limiter =
                keyedRateLimiter<Request, String> {
                    keyProvider = { it.tenant }
                    maxCalls = 1
                    period = 1.seconds
                    maxBuckets = 2
                    timeSource = clock
                }

            limiter.execute(Request("a")) { "a-1" }
            clock.advanceTimeBy(1)
            limiter.execute(Request("b")) { "b-1" }
            clock.advanceTimeBy(1)

            assertFailsWith<KeyedRateLimiterCapacityExceededException> {
                limiter.execute(Request("c")) { "c-1" }
            }
            assertFailsWith<RateLimitExceededException> {
                limiter.execute(Request("a")) { "a-2" }
            }

            clock.advanceTimeBy(1_000)
            assertEquals("c-2", limiter.execute(Request("c")) { "c-2" })
        }

    @Test
    fun `active buckets are never evicted and capacity errors omit raw keys`() =
        runTest {
            val clock = TestTimeSource()
            val limiter =
                keyedRateLimiter<Request, String> {
                    keyProvider = { it.tenant }
                    maxBuckets = 1
                    timeSource = clock
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
            clock.advanceTimeBy(1_000)
            assertEquals("available", limiter.execute(Request("other-tenant")) { "available" })
        }

    @Test
    fun `waiting buckets are never evicted`() =
        runTest {
            val clock = TestTimeSource()
            val limiter =
                keyedRateLimiter<Request, String> {
                    keyProvider = { it.tenant }
                    maxCalls = 1
                    period = 1.seconds
                    timeoutWhenLimited = 2.seconds
                    maxBuckets = 1
                    timeSource = clock
                }
            limiter.execute(Request("tenant-a")) { "first" }
            val waiting = async { limiter.execute(Request("tenant-a")) { "waiting" } }
            runCurrent()

            assertFailsWith<KeyedRateLimiterCapacityExceededException> {
                limiter.execute(Request("tenant-b")) { "never" }
            }

            waiting.cancel()
            assertFailsWith<CancellationException> { waiting.await() }
            clock.advanceTimeBy(1_000)
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
            val clock = TestTimeSource()
            val limiter =
                keyedRateLimiter<Request, String> {
                    keyProvider = { it.tenant }
                    maxBuckets = 1
                    timeSource = clock
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
            clock.advanceTimeBy(1_000)
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
