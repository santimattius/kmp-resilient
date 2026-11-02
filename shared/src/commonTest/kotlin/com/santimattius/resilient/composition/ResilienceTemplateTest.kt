package com.santimattius.resilient.composition

import com.santimattius.resilient.annotations.ResilientExperimentalApi
import com.santimattius.resilient.ratelimiter.RateLimitExceededException
import com.santimattius.resilient.ratelimiter.RateLimiterRegistry
import com.santimattius.resilient.retry.FixedBackoff
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ResilientExperimentalApi::class)
class ResilienceTemplateTest {
    @Test
    fun `given one template when included by policies with unrelated result types then both policies execute`() =
        runTest {
            val template =
                resilienceTemplate {
                    timeout { timeout = 1.seconds }
                }
            val scope = ResilientScope()

            val textPolicy = resilient(scope) { include(template) }
            val numberPolicy = resilient(scope) { include(template) }

            assertEquals("ready", textPolicy.execute { "ready" })
            assertEquals(42, numberPolicy.execute { 42 })
        }

    @Test
    fun `given an ordinary cache template when built twice then each policy has independent state`() =
        runTest {
            val template =
                resilienceTemplate {
                    cache {
                        key = "shared-definition"
                        ttl = 1.seconds
                    }
                }
            val scope = ResilientScope()
            val first = resilient(scope) { include(template) }
            val second = resilient(scope) { include(template) }
            var firstCalls = 0
            var secondCalls = 0

            assertEquals(
                "first",
                first.execute {
                    firstCalls++
                    "first"
                },
            )
            assertEquals(
                "first",
                first.execute {
                    firstCalls++
                    "unexpected"
                },
            )
            assertEquals(
                "second",
                second.execute {
                    secondCalls++
                    "second"
                },
            )
            assertEquals(
                "second",
                second.execute {
                    secondCalls++
                    "unexpected"
                },
            )

            assertEquals(1, firstCalls)
            assertEquals(1, secondCalls)
        }

    @Test
    fun `given a template with a named limiter when included twice then its registry state is explicitly shared`() =
        runTest {
            val registry = RateLimiterRegistry()
            val template =
                resilienceTemplate {
                    rateLimiterNamed(registry, "template-shared") {
                        maxCalls = 1
                        period = 1.seconds
                    }
                }
            val scope = ResilientScope()
            val first = resilient(scope) { include(template) }
            val second = resilient(scope) { include(template) }

            first.execute { "allowed" }
            first.close()

            assertFailsWith<RateLimitExceededException> {
                second.execute { "must not run" }
            }
        }

    @Test
    fun `given an included ordinary config when locally overlaid then changed properties win and preserved properties remain`() =
        runTest {
            val template =
                resilienceTemplate {
                    retry {
                        maxAttempts = 3
                        shouldRetry = { it is IllegalStateException }
                        backoffStrategy = FixedBackoff(0.milliseconds)
                    }
                }
            val policy =
                resilient(ResilientScope()) {
                    include(template)
                    retry { maxAttempts = 2 }
                }
            var attempts = 0

            assertFailsWith<IllegalArgumentException> {
                policy.execute {
                    attempts++
                    throw IllegalArgumentException("not retryable")
                }
            }
            assertEquals(1, attempts)

            assertFailsWith<IllegalStateException> {
                policy.execute {
                    attempts++
                    throw IllegalStateException("retryable")
                }
            }
            assertEquals(3, attempts)
        }

    @Test
    fun `given a template with a named limiter when included with a duplicate or ordinary limiter then conflicts fail`() {
        val registry = RateLimiterRegistry()
        val template =
            resilienceTemplate {
                rateLimiterNamed(registry, "conflict") { maxCalls = 1 }
            }
        val scope = ResilientScope()

        assertFailsWith<IllegalArgumentException> {
            resilient(scope) {
                include(template)
                include(template)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            resilient(scope) {
                include(template)
                rateLimiter { maxCalls = 2 }
            }
        }
    }

    @Test
    fun `given an included composition order when a later order is declared then the later order wins`() =
        runTest {
            val template =
                resilienceTemplate {
                    timeout { timeout = 100.milliseconds }
                    retry {
                        maxAttempts = 2
                        backoffStrategy = FixedBackoff(0.milliseconds)
                    }
                    compositionOrder(listOf(OrderablePolicyType.TIMEOUT, OrderablePolicyType.RETRY))
                }
            val policy =
                resilient(ResilientScope()) {
                    include(template)
                    compositionOrder(listOf(OrderablePolicyType.RETRY, OrderablePolicyType.TIMEOUT))
                }
            var attempts = 0

            val result =
                policy.execute {
                    attempts++
                    delay(60.milliseconds)
                    if (attempts == 1) throw IllegalStateException("retry once")
                    "completed"
                }

            assertEquals("completed", result)
            assertEquals(2, attempts)
        }
}
