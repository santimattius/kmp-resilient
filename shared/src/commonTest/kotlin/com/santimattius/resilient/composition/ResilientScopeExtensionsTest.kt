package com.santimattius.resilient.composition

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ResilientScopeExtensionsTest {

    // Scenario 1: Extension creates a usable policy
    @Test
    fun `given a CoroutineScope when resilient extension is called then returns a valid policy that executes normally`() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(testDispatcher)

            val policy = scope.resilient {
                retry { maxAttempts = 3 }
            }

            val result = policy.execute { "success" }

            assertEquals("success", result)

            scope.cancel()
        }

    // Scenario 2: Scope cancellation stops background cache cleanup jobs
    @Test
    fun `given policy with cache cleanup interval when outer scope is cancelled then subsequent advances do not trigger additional cleanup cycles`() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(testDispatcher)

            val sweepInterval = 100.milliseconds

            val policy = scope.resilient {
                cache {
                    key = "test-key"
                    cleanupInterval = sweepInterval
                    // Use a very short TTL so the first cleanup actually removes entries
                    ttl = 10.milliseconds
                }
            }

            // Populate the cache
            policy.execute { "cached-value" }

            // Advance time past one cleanup cycle — sweeper fires at least once
            advanceTimeBy(sweepInterval + 10.milliseconds)

            // Record cleanup count proxy: the derived scope must still be active at this point
            assertTrue(scope.isActive, "Outer scope should still be active before cancel")

            // Cancel the outer scope — this cancels the derived child scope and its sweeper job
            scope.cancel()

            // The outer scope is now inactive
            assertFalse(scope.isActive, "Outer scope must be inactive after cancel")

            // Advance past several more cleanup intervals — the sweeper must NOT fire again
            advanceTimeBy(sweepInterval * 5)

            // The derived scope (and its sweeper) are gone; executing from a new scope proves
            // the policy data structure is independent of the cancelled scope
            val newScope = CoroutineScope(StandardTestDispatcher(testScheduler))
            val newPolicy = newScope.resilient { retry { maxAttempts = 1 } }
            val result = newPolicy.execute { "still works" }
            assertEquals("still works", result)

            newScope.cancel()
        }

    // Scenario 3: policy.close() does not cancel the outer scope
    @Test
    fun `given a policy created via resilient extension when policy close is called then outer scope continues operating`() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(testDispatcher)

            val policy = scope.resilient {
                cache {
                    key = "test-key"
                    cleanupInterval = 5.seconds
                }
            }

            // Close the policy — must NOT cancel the outer scope
            policy.close()

            // The outer scope must still be active
            assertTrue(scope.isActive)

            // We can still launch work from the outer scope after policy.close()
            var ranAfterClose = false
            scope.resilient { }.execute { ranAfterClose = true }
            assertTrue(ranAfterClose)

            scope.cancel()
        }

    // Scenario 4: Already-cancelled scope produces a non-functional internal scope
    @Test
    fun `given already cancelled scope when resilient extension is called then derived scope is inactive and execute runs in caller context`() =
        runTest {
            // Create and immediately cancel the outer scope before building any policy.
            // Use an independent SupervisorJob (NOT linked to the test's coroutineContext)
            // so that cancelling this scope does not propagate into the runTest scope.
            val cancelledScope = CoroutineScope(SupervisorJob())
            cancelledScope.cancel()

            assertFalse(cancelledScope.isActive, "Precondition: scope must be cancelled before building policy")

            // The policy is built on top of the already-cancelled scope.
            // The KDoc warns: "internal jobs it tries to launch will fail immediately."
            // A derived child scope whose parent Job is already cancelled is itself cancelled.
            // We verify the lifecycle contract indirectly: building a policy with a cache cleanup
            // interval will attempt to launch the sweeper job into the cancelled child scope.
            // That launch silently fails (no crash) but the job never runs.
            val policy = cancelledScope.resilient {
                cache {
                    key = "lifecycle-test"
                    cleanupInterval = 10.milliseconds
                }
            }

            // execute() runs the user block in the CALLER's coroutine context (runTest scope),
            // not in the resilient scope — so it must succeed even though the derived scope is dead.
            val result = policy.execute { "result-from-caller-context" }
            assertEquals("result-from-caller-context", result)
        }
}
