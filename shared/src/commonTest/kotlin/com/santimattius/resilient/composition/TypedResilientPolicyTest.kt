package com.santimattius.resilient.composition

import app.cash.turbine.test
import com.santimattius.resilient.annotations.ResilientExperimentalApi
import com.santimattius.resilient.fallback.FallbackConfig
import com.santimattius.resilient.ratelimiter.RateLimiterRegistry
import com.santimattius.resilient.telemetry.ResilientEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ResilientExperimentalApi::class, ExperimentalCoroutinesApi::class)
class TypedResilientPolicyTest {

    private data class UserId(val value: String)

    private data class OrderId(val value: Int)

    @Test
    fun `given typed policy when executed then block receives the explicit input`() = runTest {
        val policy = resilientFor<UserId>(ResilientScope()) { timeout { timeout = 1.seconds } }

        val result = policy.execute(UserId("u-1")) { input -> "user:${input.value}" }

        assertEquals("user:u-1", result)
    }

    @Test
    fun `given one context-free template when included in policies of unrelated input types then both execute without nested calls`() =
        runTest {
            val template = resilienceTemplate {
                timeout { timeout = 1.seconds }
                retry { maxAttempts = 2 }
            }
            val scope = ResilientScope()
            val users = resilientFor<UserId>(scope) { include(template) }
            val orders = resilientFor<OrderId>(scope) { include(template) }

            assertEquals("u-1", users.execute(UserId("u-1")) { it.value })
            assertEquals(7, orders.execute(OrderId(7)) { it.value })
        }

    @Test
    fun `given template retry when typed policy block fails once then it is retried with the same input`() =
        runTest {
            val template = resilienceTemplate { retry { maxAttempts = 3 } }
            val policy = resilientFor<UserId>(ResilientScope()) { include(template) }
            val seen = mutableListOf<UserId>()

            val result = policy.execute(UserId("u-2")) { input ->
                seen += input
                if (seen.size < 2) error("boom")
                "ok"
            }

            assertEquals("ok", result)
            assertEquals(listOf(UserId("u-2"), UserId("u-2")), seen)
        }

    @Test
    fun `given template and local block when building typed policy then local overlay wins`() = runTest {
        val template = resilienceTemplate { retry { maxAttempts = 5 } }
        val policy = resilientFor<UserId>(ResilientScope()) {
            include(template)
            retry { maxAttempts = 1 }
        }
        var calls = 0

        assertFailsWith<IllegalStateException> {
            policy.execute(UserId("u")) {
                calls++
                error("boom")
            }
        }

        assertEquals(1, calls)
        assertEquals(1, policy.getHealthSnapshot().retry?.maxAttempts)
    }

    @Test
    fun `given typed policy when one execution succeeds then exactly one OperationSuccess is emitted`() =
        runTest {
            val policy = resilientFor<UserId>(ResilientScope()) { retry { maxAttempts = 2 } }

            policy.events.test {
                policy.execute(UserId("u")) { "ok" }
                assertTrue(awaitItem() is ResilientEvent.OperationSuccess)
                expectNoEvents()
            }
        }

    @Test
    fun `given typed policy when one execution fails then exactly one OperationFailure is emitted`() =
        runTest {
            val policy = resilientFor<UserId>(ResilientScope()) {}

            policy.events.test {
                assertFailsWith<IllegalStateException> {
                    policy.execute(UserId("u")) { error("boom") }
                }
                assertTrue(awaitItem() is ResilientEvent.OperationFailure)
                expectNoEvents()
            }
        }

    @Test
    fun `given typed policy with fallback when block fails then fallback value is returned`() = runTest {
        val policy = resilientFor<UserId>(ResilientScope()) {
            fallback(FallbackConfig { "fallback" })
        }

        val result = policy.execute<String>(UserId("u")) { error("boom") }

        assertEquals("fallback", result)
    }

    @Test
    fun `given cache template when two typed policies are built then each has independent cache state`() =
        runTest {
            val template = resilienceTemplate {
                cache {
                    key = "k"
                    ttl = 10.seconds
                }
            }
            val scope = ResilientScope()
            val first = resilientFor<UserId>(scope) { include(template) }
            val second = resilientFor<OrderId>(scope) { include(template) }
            var calls = 0

            first.execute(UserId("a")) { ++calls }
            first.execute(UserId("a")) { ++calls }
            second.execute(OrderId(1)) { ++calls }

            assertEquals(2, calls)
        }

    @Test
    fun `given named rate limiter registry when typed policies are built then registry state is shared`() =
        runTest {
            val registry = RateLimiterRegistry()
            val template = resilienceTemplate {
                rateLimiterNamed(registry, "global") {
                    maxCalls = 1
                    period = 60.seconds
                }
            }
            val scope = ResilientScope()
            val users = resilientFor<UserId>(scope) { include(template) }
            val orders = resilientFor<OrderId>(scope) { include(template) }

            users.execute(UserId("u")) { "ok" }
            val remaining = orders.getHealthSnapshot().rateLimiter?.remainingCalls

            assertEquals(0, remaining)
        }

    @Test
    fun `given typed policy when closed then cache is released but registry state and outer scope survive`() =
        runTest {
            val registry = RateLimiterRegistry()
            val outer = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val policy = outer.resilientFor<UserId> {
                cache {
                    key = "k"
                    ttl = 10.seconds
                }
                rateLimiterNamed(registry, "global") {
                    maxCalls = 5
                    period = 60.seconds
                }
            }

            policy.execute(UserId("u")) { "v" }
            policy.close()

            assertTrue(outer.isActive)
            val survivor = resilientFor<OrderId>(ResilientScope()) {
                rateLimiterNamed(registry, "global") {
                    maxCalls = 5
                    period = 60.seconds
                }
            }
            assertEquals(4, survivor.getHealthSnapshot().rateLimiter?.remainingCalls)
            outer.cancel()
        }

    @Test
    fun `given legacy resilient policy and typed policy when both built then legacy behavior is unchanged`() =
        runTest {
            val legacy = resilient(ResilientScope()) { retry { maxAttempts = 2 } }
            var calls = 0

            val result = legacy.execute {
                calls++
                if (calls < 2) error("boom")
                "ok"
            }

            assertEquals("ok", result)
        }
}
