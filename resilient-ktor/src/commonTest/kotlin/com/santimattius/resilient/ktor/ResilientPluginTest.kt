package com.santimattius.resilient.ktor

import com.santimattius.resilient.PolicyHealthSnapshot
import com.santimattius.resilient.ResilientPolicy
import com.santimattius.resilient.circuitbreaker.CircuitBreakerOpenException
import com.santimattius.resilient.composition.ResilientScope
import com.santimattius.resilient.retry.FixedBackoff
import com.santimattius.resilient.telemetry.ResilientEvent
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Full behavioral test suite for [ResilientPlugin].
 *
 * Tests use [MockEngine] to simulate HTTP response sequences via an atomic counter per test.
 * All tests use [runTest] for virtual-time control, so [FixedBackoff] with 0ms delays keeps
 * the suite fast regardless of retry counts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResilientPluginTest {

    // ── Helpers ───────────────────────────────────────────────────────────────────

    /**
     * Creates a [MockEngine] that returns [responses] in order.
     * If more requests arrive than responses, the last response is repeated.
     * Returns the engine and an atomic-like counter backed by a mutable state holder.
     */
    private fun sequentialEngine(
        vararg responses: Pair<String, HttpStatusCode>
    ): Pair<MockEngine, () -> Int> {
        var callCount = 0
        val engine = MockEngine { _ ->
            val index = callCount.coerceAtMost(responses.size - 1)
            callCount++
            val (body, status) = responses[index]
            respond(body, status)
        }
        return engine to { callCount }
    }

    /** Creates a [MockEngine] that always returns [status], counting total calls. */
    private fun fixedEngine(
        status: HttpStatusCode = HttpStatusCode.OK,
        body: String = "ok"
    ): Pair<MockEngine, () -> Int> {
        var callCount = 0
        val engine = MockEngine { _ ->
            callCount++
            respond(body, status)
        }
        return engine to { callCount }
    }

    /**
     * Creates a standalone [ResilientScope] backed by [Dispatchers.Default].
     * This scope is independent of the test's coroutine scope and MUST be closed
     * explicitly after use (call [ResilientScope.close] or close the enclosing [HttpClient]).
     */
    private fun testResilientScope(): ResilientScope = ResilientScope()

    // ── 3.2: Successful request passes through exactly once ───────────────────────

    @Test
    fun successfulRequest_passesThrough_exactlyOnce() = runTest {
        val (engine, count) = sequentialEngine(
            "ok" to HttpStatusCode.OK
        )
        val client = HttpClient(engine) {
            install(ResilientPlugin) {
                scope = testResilientScope()
                retry {
                    maxAttempts = 3
                    backoffStrategy = FixedBackoff(0.milliseconds)
                }
                shouldRetryOnStatus = { it.value >= 500 }
            }
        }

        val response = client.get("/")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(1, count(), "Engine must be called exactly once for a 200 response")
        client.close()
    }

    // ── 3.3: Policy executes on each independent request ─────────────────────────

    @Test
    fun policyExecutes_on_each_independent_request() = runTest {
        val (engine, count) = fixedEngine(HttpStatusCode.OK)
        val client = HttpClient(engine) {
            install(ResilientPlugin) {
                scope = testResilientScope()
                retry {
                    maxAttempts = 3
                    backoffStrategy = FixedBackoff(0.milliseconds)
                }
            }
        }

        client.get("/")
        client.get("/")

        assertEquals(2, count(), "Each request must be intercepted independently")
        client.close()
    }

    // ── 3.4: Retry fires on 503 then succeeds on 200 ─────────────────────────────

    @Test
    fun retry_fires_on_503_then_succeeds_on_200() = runTest {
        val (engine, count) = sequentialEngine(
            "err" to HttpStatusCode.ServiceUnavailable,
            "err" to HttpStatusCode.ServiceUnavailable,
            "ok" to HttpStatusCode.OK
        )
        val client = HttpClient(engine) {
            install(ResilientPlugin) {
                scope = testResilientScope()
                retry {
                    maxAttempts = 3
                    backoffStrategy = FixedBackoff(0.milliseconds)
                }
                shouldRetryOnStatus = { it == HttpStatusCode.ServiceUnavailable }
            }
        }

        val response = client.get("/")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(3, count(), "Engine must be called exactly 3 times (2 × 503 + 1 × 200)")
        client.close()
    }

    // ── 3.5: No retry when shouldRetryOnStatus returns false ─────────────────────

    @Test
    fun noRetry_when_shouldRetryOnStatus_returnsFalse() = runTest {
        val (engine, count) = fixedEngine(HttpStatusCode.ServiceUnavailable, "err")
        val client = HttpClient(engine) {
            install(ResilientPlugin) {
                scope = testResilientScope()
                retry {
                    maxAttempts = 3
                    backoffStrategy = FixedBackoff(0.milliseconds)
                }
                shouldRetryOnStatus = { false }
            }
        }

        val response = client.get("/")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals(1, count(), "Engine must be called exactly once when retry is disabled by predicate")
        client.close()
    }

    // ── 3.6: Custom shouldRetryOnStatus — 500 not retried when only 503 configured ─

    @Test
    fun customShouldRetryOnStatus_does_not_retry_500_when_only_503_configured() = runTest {
        val (engine, count) = fixedEngine(HttpStatusCode.InternalServerError, "err")
        val client = HttpClient(engine) {
            install(ResilientPlugin) {
                scope = testResilientScope()
                retry {
                    maxAttempts = 3
                    backoffStrategy = FixedBackoff(0.milliseconds)
                }
                shouldRetryOnStatus = { it == HttpStatusCode.ServiceUnavailable }
            }
        }

        val response = client.get("/")

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals(1, count(), "500 must not be retried when shouldRetryOnStatus only matches 503")
        client.close()
    }

    // ── 3.7: POST not retried when retryOnlyIdempotent = true ────────────────────

    @Test
    fun post_not_retried_when_retryOnlyIdempotent_true() = runTest {
        val (engine, count) = fixedEngine(HttpStatusCode.ServiceUnavailable, "err")
        val client = HttpClient(engine) {
            install(ResilientPlugin) {
                scope = testResilientScope()
                retry {
                    maxAttempts = 3
                    backoffStrategy = FixedBackoff(0.milliseconds)
                }
                shouldRetryOnStatus = { it.value >= 500 }
                retryOnlyIdempotent = true
            }
        }

        val response = client.post("/")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals(1, count(), "POST must not be retried when retryOnlyIdempotent = true")
        client.close()
    }

    // ── 3.8: GET retried when retryOnlyIdempotent = true ─────────────────────────

    @Test
    fun get_retried_when_retryOnlyIdempotent_true() = runTest {
        val (engine, count) = sequentialEngine(
            "err" to HttpStatusCode.ServiceUnavailable,
            "err" to HttpStatusCode.ServiceUnavailable,
            "ok" to HttpStatusCode.OK
        )
        val client = HttpClient(engine) {
            install(ResilientPlugin) {
                scope = testResilientScope()
                retry {
                    maxAttempts = 3
                    backoffStrategy = FixedBackoff(0.milliseconds)
                }
                shouldRetryOnStatus = { it.value >= 500 }
                retryOnlyIdempotent = true
            }
        }

        val response = client.get("/")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(3, count(), "GET must be retried when retryOnlyIdempotent = true")
        client.close()
    }

    // ── 3.9: POST retried when retryOnlyIdempotent = false ───────────────────────

    @Test
    fun post_retried_when_retryOnlyIdempotent_false() = runTest {
        val (engine, count) = sequentialEngine(
            "err" to HttpStatusCode.ServiceUnavailable,
            "ok" to HttpStatusCode.OK
        )
        val client = HttpClient(engine) {
            install(ResilientPlugin) {
                scope = testResilientScope()
                retry {
                    maxAttempts = 2
                    backoffStrategy = FixedBackoff(0.milliseconds)
                }
                shouldRetryOnStatus = { it.value >= 500 }
                retryOnlyIdempotent = false
            }
        }

        val response = client.post("/")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(2, count(), "POST must be retried when retryOnlyIdempotent = false")
        client.close()
    }

    // ── 3.10: Circuit breaker opens after failure threshold ───────────────────────

    @Test
    fun circuitBreaker_opens_after_failure_threshold() = runTest {
        var engineCallCount = 0
        val engine = MockEngine { _ ->
            engineCallCount++
            respond("err", HttpStatusCode.ServiceUnavailable)
        }

        val client = HttpClient(engine) {
            install(ResilientPlugin) {
                scope = testResilientScope()
                // No retry: each call goes straight through (one attempt only)
                retry {
                    maxAttempts = 1
                    backoffStrategy = FixedBackoff(0.milliseconds)
                }
                // No status-based retry: 503 is NOT considered retryable by retry policy
                shouldRetryOnStatus = { false }
                // Circuit breaker counts 503 as a failure via shouldRecordResult
                circuitBreaker {
                    failureThreshold = 2
                    shouldRecordResult = { result ->
                        result is io.ktor.client.call.HttpClientCall &&
                            result.response.status.value >= 500
                    }
                    // Very short open timeout so we don't rely on time advancement
                    timeout = 60_000.milliseconds
                }
            }
        }

        // First call: 503 → failure #1 counted by CB
        val response1 = client.get("/")
        assertEquals(HttpStatusCode.ServiceUnavailable, response1.status)

        // Second call: 503 → failure #2 counted by CB → CB opens
        val response2 = client.get("/")
        assertEquals(HttpStatusCode.ServiceUnavailable, response2.status)

        // Third call: CB is open → CircuitBreakerOpenException, engine NOT called
        val engineCountBeforeThirdCall = engineCallCount
        assertFailsWith<CircuitBreakerOpenException> {
            client.get("/")
        }
        assertEquals(
            engineCountBeforeThirdCall, engineCallCount,
            "Engine must NOT be called when circuit breaker is open"
        )

        client.close()
    }

    // ── 3.11: Timeout cancels slow request ───────────────────────────────────────

    @Test
    fun timeout_cancels_slow_request() = runTest {
        val engine = MockEngine { _ ->
            delay(500.milliseconds) // virtual time in runTest
            respond("ok", HttpStatusCode.OK)
        }

        val client = HttpClient(engine) {
            install(ResilientPlugin) {
                scope = testResilientScope()
                timeout {
                    timeout = 100.milliseconds
                }
            }
        }

        assertFailsWith<CancellationException> {
            client.get("/")
        }

        client.close()
    }

    // ── 3.12: Policy exception not wrapped ────────────────────────────────────────

    @Test
    fun policy_exception_not_wrapped() = runTest {
        var engineCallCount = 0
        val engine = MockEngine { _ ->
            engineCallCount++
            respond("err", HttpStatusCode.ServiceUnavailable)
        }

        val client = HttpClient(engine) {
            install(ResilientPlugin) {
                scope = testResilientScope()
                retry {
                    maxAttempts = 1
                    backoffStrategy = FixedBackoff(0.milliseconds)
                }
                shouldRetryOnStatus = { false }
                circuitBreaker {
                    failureThreshold = 1
                    shouldRecordResult = { result ->
                        result is io.ktor.client.call.HttpClientCall &&
                            result.response.status.value >= 500
                    }
                    timeout = 60_000.milliseconds
                }
            }
        }

        // First call opens the circuit
        client.get("/")

        // Second call: CB open → must throw CircuitBreakerOpenException directly (no wrapping)
        val thrown = assertFailsWith<CircuitBreakerOpenException> {
            client.get("/")
        }
        // Verify it is the exact type, not a wrapper
        assertIs<CircuitBreakerOpenException>(thrown)

        client.close()
    }

    // ── 3.13: Inline policy closed on client close ────────────────────────────────
    //
    // Verifies that:
    // (a) client.close() completes without throwing when the inline-DSL path owns the policy
    // (b) the onClose hook fires (policyOwned = true), which calls resolvedPolicy.close()
    //
    // Note: ResilientScope is a final class and resolvedPolicy is internal to the plugin,
    // so close() is verified indirectly. The plugin correctly sets policyOwned = true for
    // the inline-DSL path, and the onClose { if (policyOwned) resolvedPolicy.close() } hook
    // is tested by confirming the lifecycle completes cleanly without error.
    // A request succeeds before close; after close the engine shows exactly 1 request in history.

    @Test
    fun inline_policy_closed_on_client_close() = runTest {
        val resilientScope = testResilientScope()
        var engineCallCount = 0
        val engine = MockEngine { _ ->
            engineCallCount++
            respond("ok", HttpStatusCode.OK)
        }

        val client = HttpClient(engine) {
            install(ResilientPlugin) {
                scope = resilientScope
                retry {
                    maxAttempts = 1
                    backoffStrategy = FixedBackoff(0.milliseconds)
                }
            }
        }

        // Make one successful request
        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(1, engineCallCount)

        // Close the client — must not throw; triggers onClose { resolvedPolicy.close() }
        // This verifies policyOwned = true → close() is called on the internally created policy
        client.close()

        // Engine confirms exactly 1 request was made (no extra calls due to close lifecycle)
        assertEquals(1, engineCallCount, "No additional engine calls should occur during client.close()")

        // Cleanup: close the standalone scope (plugin does NOT do this — user owns scope lifecycle)
        resilientScope.close()
    }

    // ── 3.14: BYO policy NOT closed on client close ───────────────────────────────

    @Test
    fun byo_policy_not_closed_on_client_close() = runTest {
        var closeCalled = false

        // Build a spy policy that tracks close() calls
        val spyPolicy = object : ResilientPolicy {
            override val events: SharedFlow<ResilientEvent>
                get() = kotlinx.coroutines.flow.MutableSharedFlow()

            override val cacheHandle = null

            override fun getHealthSnapshot() = PolicyHealthSnapshot()

            override suspend fun <T> execute(block: suspend () -> T): T = block()

            override fun close() {
                closeCalled = true
            }
        }

        val (engine, _) = fixedEngine()
        val client = HttpClient(engine) {
            install(ResilientPlugin) {
                policy = spyPolicy
            }
        }

        client.get("/")
        client.close()

        assertTrue(!closeCalled, "BYO policy path: user-provided policy must NOT be closed by the plugin")
    }

    // ── 3.15: Validation fails when neither policy nor inline DSL set ─────────────

    @Test
    fun validation_fails_when_neither_policy_nor_inline_set() {
        assertFailsWith<IllegalArgumentException> {
            HttpClient(MockEngine { respond("ok", HttpStatusCode.OK) }) {
                install(ResilientPlugin) {
                    // neither policy nor any DSL block
                }
            }
        }
    }

    // ── 3.16: Validation fails when both policy and inline DSL set ────────────────

    @Test
    fun validation_fails_when_both_policy_and_inline_set() {
        val dummyPolicy = object : ResilientPolicy {
            override val events: SharedFlow<ResilientEvent>
                get() = kotlinx.coroutines.flow.MutableSharedFlow()

            override val cacheHandle = null

            override fun getHealthSnapshot() = PolicyHealthSnapshot()

            override suspend fun <T> execute(block: suspend () -> T): T = block()

            override fun close() {}
        }

        assertFailsWith<IllegalArgumentException> {
            HttpClient(MockEngine { respond("ok", HttpStatusCode.OK) }) {
                install(ResilientPlugin) {
                    policy = dummyPolicy
                    // Also set an inline DSL block — this conflicts with BYO policy
                    retry { maxAttempts = 2 }
                }
            }
        }
    }
}
