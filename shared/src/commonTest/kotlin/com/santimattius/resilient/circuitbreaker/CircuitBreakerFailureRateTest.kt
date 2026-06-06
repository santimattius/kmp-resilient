package com.santimattius.resilient.circuitbreaker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for the failure-rate (count-based sliding window) mode of [DefaultCircuitBreaker].
 *
 * Scenarios from spec:
 * S1 – CLOSED when < minimumNumberOfCalls recorded (even if all fail)
 * S2 – CLOSED when minimumNumberOfCalls reached but rate < threshold
 * S3 – OPEN when minimumNumberOfCalls reached and rate >= threshold
 * S4 – Half-open recovery after rate-triggered open
 * S5 – Consecutive mode still works when failureRateThreshold == null
 * S6 – Ring buffer slides (old entries evicted)
 * S7 – Both failureRateThreshold and slidingWindow set → IllegalArgumentException
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CircuitBreakerFailureRateTest {

    // ──────────────────────────────────────────────────────────────────────────
    // S1 – below minimumNumberOfCalls: circuit stays CLOSED even with all failures
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `given failureRateThreshold when fewer than minimumNumberOfCalls recorded then circuit stays CLOSED`() =
        runTest {
            val cfg = CircuitBreakerConfig().apply {
                failureRateThreshold = 50.0
                minimumNumberOfCalls = 10
                successThreshold = 1
                timeout = 60.seconds
                halfOpenMaxCalls = 1
            }
            val cb = DefaultCircuitBreaker(cfg)

            // Record 9 failures — all fail but minimum not reached
            repeat(9) {
                assertFailsWith<IllegalStateException> { cb.execute { error("boom") } }
            }

            assertEquals(CircuitState.CLOSED, cb.state.value)
        }

    // ──────────────────────────────────────────────────────────────────────────
    // S2 – minimumNumberOfCalls reached but rate below threshold: stays CLOSED
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `given 10 calls with 4 failures 40 percent when threshold is 50 percent then circuit stays CLOSED`() =
        runTest {
            val cfg = CircuitBreakerConfig().apply {
                failureRateThreshold = 50.0
                minimumNumberOfCalls = 10
                successThreshold = 1
                timeout = 60.seconds
                halfOpenMaxCalls = 1
            }
            val cb = DefaultCircuitBreaker(cfg)

            // 6 successes
            repeat(6) { cb.execute { "ok" } }
            // 4 failures
            repeat(4) {
                assertFailsWith<IllegalStateException> { cb.execute { error("boom") } }
            }

            assertEquals(CircuitState.CLOSED, cb.state.value)
        }

    // ──────────────────────────────────────────────────────────────────────────
    // S3 – exactly at threshold (50%): circuit opens
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `given 10 calls with 5 failures 50 percent when threshold is 50 percent then circuit opens`() =
        runTest {
            val cfg = CircuitBreakerConfig().apply {
                failureRateThreshold = 50.0
                minimumNumberOfCalls = 10
                successThreshold = 1
                timeout = 60.seconds
                halfOpenMaxCalls = 1
            }
            val cb = DefaultCircuitBreaker(cfg)

            // 5 successes + 5 failures = 50% rate
            repeat(5) { cb.execute { "ok" } }
            repeat(4) {
                assertFailsWith<IllegalStateException> { cb.execute { error("boom") } }
            }
            // 10th call (5th failure) — should trip the breaker
            assertFailsWith<IllegalStateException> { cb.execute { error("boom") } }

            assertEquals(CircuitState.OPEN, cb.state.value)
            assertFailsWith<CircuitBreakerOpenException> { cb.execute { "never" } }
        }

    // ──────────────────────────────────────────────────────────────────────────
    // S4 – half-open recovery after rate-triggered open
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `given OPEN due to failure rate when timeout elapses then half-open probe succeeds and transitions to CLOSED`() =
        runTest {
            val ts = TestTimeSource()
            ts.setTime(1000L)
            val cfg = CircuitBreakerConfig().apply {
                failureRateThreshold = 50.0
                minimumNumberOfCalls = 4
                successThreshold = 2
                timeout = 10.milliseconds
                halfOpenMaxCalls = 3
            }
            val cb = DefaultCircuitBreaker(cfg, ts)

            // Trip the breaker: 4 calls, 2 failures = 50%
            repeat(2) { cb.execute { "ok" } }
            repeat(2) { assertFailsWith<IllegalStateException> { cb.execute { error("trip") } } }
            assertEquals(CircuitState.OPEN, cb.state.value)

            // Advance past timeout
            ts.advanceTimeBy(15L)

            // Two successive probe successes → CLOSED
            cb.execute { "probe-1" }
            cb.execute { "probe-2" }

            assertEquals(CircuitState.CLOSED, cb.state.value)
        }

    // ──────────────────────────────────────────────────────────────────────────
    // S5 – consecutive mode unchanged when failureRateThreshold == null
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `given failureRateThreshold null when 5 consecutive failures then circuit opens no regression`() =
        runTest {
            val cfg = CircuitBreakerConfig().apply {
                failureRateThreshold = null
                failureThreshold = 5
                successThreshold = 1
                timeout = 60.seconds
                halfOpenMaxCalls = 1
            }
            val cb = DefaultCircuitBreaker(cfg)

            repeat(5) {
                assertFailsWith<IllegalStateException> { cb.execute { error("boom") } }
            }

            assertEquals(CircuitState.OPEN, cb.state.value)
        }

    // ──────────────────────────────────────────────────────────────────────────
    // S6 – ring buffer slides: old entries are evicted
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `given circuit recovered to CLOSED when next window has 2 of 5 failures then circuit stays CLOSED`() =
        runTest {
            val ts = TestTimeSource()
            ts.setTime(1000L)
            val cfg = CircuitBreakerConfig().apply {
                failureRateThreshold = 60.0
                minimumNumberOfCalls = 5
                successThreshold = 1
                timeout = 10.milliseconds
                halfOpenMaxCalls = 1
            }
            val cb = DefaultCircuitBreaker(cfg, ts)

            // First window: 4 failures, 1 success = 80% → opens
            repeat(4) { assertFailsWith<IllegalStateException> { cb.execute { error("fail") } } }
            cb.execute { "ok" }
            assertEquals(CircuitState.OPEN, cb.state.value)

            // Recover: advance past timeout, one probe success → CLOSED
            ts.advanceTimeBy(15L)
            cb.execute { "probe" }
            assertEquals(CircuitState.CLOSED, cb.state.value)

            // Second window: 2 failures, 3 successes = 40% → stays CLOSED
            repeat(3) { cb.execute { "ok" } }
            repeat(2) { assertFailsWith<IllegalStateException> { cb.execute { error("fail") } } }

            assertEquals(CircuitState.CLOSED, cb.state.value)
        }

    // ──────────────────────────────────────────────────────────────────────────
    // S7 – combining failureRateThreshold + slidingWindow → IllegalArgumentException
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `given both failureRateThreshold and slidingWindow set then IllegalArgumentException is thrown`() {
        assertFailsWith<IllegalArgumentException> {
            CircuitBreakerConfig().apply {
                failureRateThreshold = 50.0
                slidingWindow = 30.seconds
            }
            // Validation happens in DefaultCircuitBreaker init
            DefaultCircuitBreaker(
                CircuitBreakerConfig().apply {
                    failureRateThreshold = 50.0
                    slidingWindow = 30.seconds
                }
            )
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Extra triangulation: out-of-range failureRateThreshold
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `given failureRateThreshold above 100 when creating circuit breaker then IllegalArgumentException is thrown`() {
        assertFailsWith<IllegalArgumentException> {
            DefaultCircuitBreaker(
                CircuitBreakerConfig().apply {
                    failureRateThreshold = 101.0
                }
            )
        }
    }

    @Test
    fun `given failureRateThreshold below 0 when creating circuit breaker then IllegalArgumentException is thrown`() {
        assertFailsWith<IllegalArgumentException> {
            DefaultCircuitBreaker(
                CircuitBreakerConfig().apply {
                    failureRateThreshold = -1.0
                }
            )
        }
    }
}
