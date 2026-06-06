package com.santimattius.resilient.circuitbreaker

import com.santimattius.resilient.composition.ResilientScope
import com.santimattius.resilient.composition.resilient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame

@OptIn(ExperimentalCoroutinesApi::class)
class CircuitBreakerRegistryTest {

    // ─────────────────────────────────────────────────────────────────────────
    // S1: Same name returns same instance
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `given same name when getOrCreate twice then returns same instance`() {
        val registry = CircuitBreakerRegistry()
        val first = registry.getOrCreate("orders", configure = { failureThreshold = 5 })
        val second = registry.getOrCreate("orders", configure = { failureThreshold = 99 })
        assertSame(first, second)
    }

    @Test
    fun `given different names when getOrCreate then distinct instances`() {
        val registry = CircuitBreakerRegistry()
        val a = registry.getOrCreate("orders", configure = { failureThreshold = 3 })
        val b = registry.getOrCreate("payments", configure = { failureThreshold = 3 })
        assertSame(a, registry.getOrCreate("orders", configure = {}))
        assertSame(b, registry.getOrCreate("payments", configure = {}))
        assertNotSame(a, b)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // S2: Shared trip state — two policies sharing the same registry CB
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `given two policies sharing registry entry when failures trip circuit then policy B also rejects`() = runTest {
        val registry = CircuitBreakerRegistry()
        val scope = ResilientScope()

        val policyA = resilient(scope) {
            circuitBreakerNamed(registry, "orders") { failureThreshold = 3 }
        }
        val policyB = resilient(scope) {
            circuitBreakerNamed(registry, "orders") { failureThreshold = 99 }
        }

        // Cause 3 failures through policy A
        repeat(3) {
            assertFailsWith<IllegalStateException> {
                policyA.execute { throw IllegalStateException("boom") }
            }
        }

        // Policy B should also be tripped — same underlying CB instance
        assertFailsWith<CircuitBreakerOpenException> {
            policyB.execute { "should not execute" }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // S3: Mixing circuitBreakerNamed + circuitBreaker {} throws
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `given circuitBreaker block when circuitBreakerNamed added then throws IllegalArgumentException`() {
        val registry = CircuitBreakerRegistry()
        val scope = ResilientScope()

        assertFailsWith<IllegalArgumentException> {
            resilient(scope) {
                circuitBreaker { failureThreshold = 5 }
                circuitBreakerNamed(registry, "orders") { failureThreshold = 3 }
            }
        }
    }

    @Test
    fun `given circuitBreakerNamed when circuitBreaker block added then throws IllegalArgumentException`() {
        val registry = CircuitBreakerRegistry()
        val scope = ResilientScope()

        assertFailsWith<IllegalArgumentException> {
            resilient(scope) {
                circuitBreakerNamed(registry, "orders") { failureThreshold = 3 }
                circuitBreaker { failureThreshold = 5 }
            }
        }
    }
}
