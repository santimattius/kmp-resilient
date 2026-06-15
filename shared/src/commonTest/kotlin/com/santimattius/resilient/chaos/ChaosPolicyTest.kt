package com.santimattius.resilient.chaos

import com.santimattius.resilient.annotations.ResilientExperimentalApi
import com.santimattius.resilient.composition.ResilientScope
import com.santimattius.resilient.composition.resilient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ResilientExperimentalApi::class, ExperimentalCoroutinesApi::class)
class ChaosPolicyTest {

    // ---------------------------------------------------------------------------
    // S1: enabled NOT set (defaults to false) — no fault injected, block runs normally
    // GIVEN chaos { failureRate = 1.0 } (enabled NOT set)
    // WHEN execute { "ok" }
    // THEN "ok" returned, no fault
    // ---------------------------------------------------------------------------

    @Test
    fun `given chaos with enabled false when failureRate is 1_0 then block executes normally`() = runTest {
        // given
        val scope = ResilientScope()
        val policy = resilient(scope) {
            chaos {
                failureRate = 1.0
                // enabled NOT set — defaults to false
            }
        }

        // when
        val result = policy.execute { "ok" }

        // then
        assertEquals("ok", result)
    }

    @Test
    fun `given chaos policy when enabled is false then no wrapper is installed and block executes`() = runTest {
        // given — triangulation: different block return value
        val scope = ResilientScope()
        val policy = resilient(scope) {
            chaos {
                enabled = false
                failureRate = 1.0
                exception = { RuntimeException("should not be thrown") }
            }
        }

        // when
        val result = policy.execute { 42 }

        // then — block runs, no fault
        assertEquals(42, result)
    }

    // ---------------------------------------------------------------------------
    // S2: 100% failure rate throws before block runs
    // GIVEN chaos { enabled = true; failureRate = 1.0 }
    // WHEN execute { "ok" }
    // THEN exception thrown before block runs
    // ---------------------------------------------------------------------------

    @Test
    fun `given chaos enabled with failureRate 1_0 when execute then exception thrown`() = runTest {
        // given
        val scope = ResilientScope()
        var blockExecuted = false
        val policy = resilient(scope) {
            chaos {
                enabled = true
                failureRate = 1.0
            }
        }

        // when / then
        assertFailsWith<RuntimeException> {
            policy.execute {
                blockExecuted = true
                "ok"
            }
        }
        // block should NOT have run (fault fires before block)
        assertEquals(false, blockExecuted)
    }

    @Test
    fun `given chaos enabled with custom exception factory when execute then custom exception thrown`() = runTest {
        // given — triangulation: custom exception factory
        val scope = ResilientScope()
        val policy = resilient(scope) {
            chaos {
                enabled = true
                failureRate = 1.0
                exception = { IllegalStateException("custom fault") }
            }
        }

        // when / then
        val thrown = assertFailsWith<IllegalStateException> {
            policy.execute { "ok" }
        }
        assertEquals("custom fault", thrown.message)
    }

    // ---------------------------------------------------------------------------
    // S3: 0% failure rate passes through
    // GIVEN chaos { enabled = true; failureRate = 0.0 }
    // WHEN execute { 42 }
    // THEN 42 returned
    // ---------------------------------------------------------------------------

    @Test
    fun `given chaos enabled with failureRate 0_0 when execute then block result returned`() = runTest {
        // given
        val scope = ResilientScope()
        val policy = resilient(scope) {
            chaos {
                enabled = true
                failureRate = 0.0
            }
        }

        // when
        val result = policy.execute { 42 }

        // then
        assertEquals(42, result)
    }

    @Test
    fun `given chaos enabled with failureRate 0_0 when execute string then block string returned`() = runTest {
        // given — triangulation: different type
        val scope = ResilientScope()
        val policy = resilient(scope) {
            chaos {
                enabled = true
                failureRate = 0.0
            }
        }

        // when
        val result = policy.execute { "hello" }

        // then
        assertEquals("hello", result)
    }

    // ---------------------------------------------------------------------------
    // S4: latency injection adds delay
    // GIVEN chaos { enabled = true; latency = 500.ms; failureRate = 0.0 }
    // WHEN execute and measure time
    // THEN elapsed >= 500ms
    // ---------------------------------------------------------------------------

    @Test
    fun `given chaos enabled with latency 500ms when execute then virtual time advances by at least 500ms`() = runTest {
        // given
        val scope = ResilientScope()
        val policy = resilient(scope) {
            chaos {
                enabled = true
                failureRate = 0.0
                latency = 500.milliseconds
            }
        }

        // when — runTest uses a virtual scheduler; testScheduler.currentTime tracks virtual ms
        val before = testScheduler.currentTime
        policy.execute { "result" }
        val virtualElapsed = testScheduler.currentTime - before

        // then — virtual time must advance by at least 500ms (the injected delay)
        assertTrue(virtualElapsed >= 500L, "Expected virtual elapsed >= 500ms but was ${virtualElapsed}ms")
    }

    @Test
    fun `given chaos enabled with latency 200ms when execute then virtual time advances by at least 200ms`() = runTest {
        // given — triangulation: different latency value
        val scope = ResilientScope()
        val policy = resilient(scope) {
            chaos {
                enabled = true
                failureRate = 0.0
                latency = 200.milliseconds
            }
        }

        // when
        val before = testScheduler.currentTime
        policy.execute { "result" }
        val virtualElapsed = testScheduler.currentTime - before

        // then
        assertTrue(virtualElapsed >= 200L, "Expected virtual elapsed >= 200ms but was ${virtualElapsed}ms")
    }

    // ---------------------------------------------------------------------------
    // S5: injectResult overrides block return
    // GIVEN chaos { enabled = true; failureRate = 0.0; injectResult = { "injected" } }
    // WHEN execute { "real" }
    // THEN "injected" returned
    // ---------------------------------------------------------------------------

    @Test
    fun `given chaos enabled with injectResult when execute then injectResult value returned`() = runTest {
        // given
        val scope = ResilientScope()
        val policy = resilient(scope) {
            chaos {
                enabled = true
                failureRate = 0.0
                @Suppress("UNCHECKED_CAST")
                injectResult = { "injected" }
            }
        }

        // when
        val result = policy.execute { "real" }

        // then — injectResult overrides block return
        assertEquals("injected", result)
    }

    @Test
    fun `given chaos enabled with injectResult as null value when execute then null returned`() = runTest {
        // given — triangulation: injectResult returns null
        val scope = ResilientScope()
        val policy = resilient(scope) {
            chaos {
                enabled = true
                failureRate = 0.0
                injectResult = { null }
            }
        }

        // when
        val result = policy.execute<Any?> { "real" }

        // then
        assertEquals(null, result)
    }
}
