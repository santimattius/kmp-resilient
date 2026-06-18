package com.santimattius.resilient.deadline

import com.santimattius.resilient.annotations.ResilientExperimentalApi
import com.santimattius.resilient.composition.ResilientScope
import com.santimattius.resilient.composition.resilient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class, ResilientExperimentalApi::class)
class ResilientDeadlineTest {

    private val testDispatcher = StandardTestDispatcher()

    // ----------------------------------------------------------------
    // S1: No explicit timeout — deadline governs the execution
    // ----------------------------------------------------------------

    @Test
    fun `given no explicit timeout when deadline expires before block finishes then throws TimeoutCancellationException`() =
        runTest {
            // given
            val scope = ResilientScope(testDispatcher)
            val policy = resilient(scope) {
                // no timeout configured — deadline should provide the limit
            }

            // when / then
            assertFailsWith<TimeoutCancellationException> {
                withContext(ResilientDeadline.after(200.milliseconds)) {
                    policy.execute {
                        delay(5.seconds)
                        "should not reach here"
                    }
                }
            }
        }

    // ----------------------------------------------------------------
    // S2: No deadline, no timeout — normal execution
    // ----------------------------------------------------------------

    @Test
    fun `given no deadline and no timeout when block succeeds then result is returned normally`() =
        runTest {
            // given
            val scope = ResilientScope(testDispatcher)
            val policy = resilient(scope) {}

            // when
            val result = policy.execute {
                delay(100.milliseconds)
                "ok"
            }

            // then
            assertEquals("ok", result)
        }

    // ----------------------------------------------------------------
    // S3: Explicit timeout < deadline — timeout wins
    // ----------------------------------------------------------------

    @Test
    fun `given explicit timeout shorter than deadline when block exceeds timeout then cancelled at timeout`() =
        runTest {
            // given
            val scope = ResilientScope(testDispatcher)
            val policy = resilient(scope) {
                timeout { timeout = 200.milliseconds }
            }

            // when / then — cancelled at ~200ms, not at deadline of 10s
            assertFailsWith<TimeoutCancellationException> {
                withContext(ResilientDeadline.after(10.seconds)) {
                    policy.execute {
                        delay(5.seconds)
                        "nope"
                    }
                }
            }
        }

    // ----------------------------------------------------------------
    // S4: Deadline < explicit timeout — deadline wins
    // ----------------------------------------------------------------

    @Test
    fun `given deadline shorter than explicit timeout when block exceeds deadline then cancelled at deadline`() =
        runTest {
            // given
            val scope = ResilientScope(testDispatcher)
            val policy = resilient(scope) {
                timeout { timeout = 10.seconds }
            }

            // when / then — cancelled at ~200ms deadline, not at 10s timeout
            assertFailsWith<TimeoutCancellationException> {
                withContext(ResilientDeadline.after(200.milliseconds)) {
                    policy.execute {
                        delay(5.seconds)
                        "nope"
                    }
                }
            }
        }

    // ----------------------------------------------------------------
    // S5: Expired deadline — immediate cancellation, block never called
    // ----------------------------------------------------------------

    @Test
    fun `given already expired deadline when execute is called then throws TimeoutCancellationException without invoking block`() =
        runTest {
            // given
            val scope = ResilientScope(testDispatcher)
            val policy = resilient(scope) {}

            // Create a deadline that is already in the past (negative duration → expired)
            val expiredDeadline = ResilientDeadline.after((-1).seconds)

            var blockInvoked = false

            // when / then
            assertFailsWith<TimeoutCancellationException> {
                withContext(expiredDeadline) {
                    policy.execute {
                        blockInvoked = true
                        "should not reach here"
                    }
                }
            }

            // verify block was NOT invoked
            assertEquals(false, blockInvoked, "Block must not be invoked when deadline is already expired")
        }
}
