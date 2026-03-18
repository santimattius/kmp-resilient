package com.santimattius.resilient.coalescing

import com.santimattius.resilient.composition.ResilientScope
import com.santimattius.resilient.composition.resilient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CoalescingPolicyTest {

    @Test
    fun `given coalescing with same key when many concurrent executes then block is executed once`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = ResilientScope(testDispatcher)
        val policy = resilient(scope) {
            coalesce { key = "k" }
        }

        var executions = 0
        val gate = CompletableDeferred<Unit>()

        val jobs = (1..10).map {
            async {
                policy.execute {
                    gate.await()
                    executions++
                    "result"
                }
            }
        }

        gate.complete(Unit)
        val results = jobs.awaitAll()

        assertEquals(1, executions)
        results.forEach { assertEquals("result", it) }

        scope.close()
    }

    @Test
    fun `given coalescing with dynamic key when keys differ concurrently then block executes per key`() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val scope = ResilientScope(testDispatcher)

            val keyChannel = Channel<String>(capacity = 3)

            val policy = resilient(scope) {
                coalesce {
                    keyProvider = { keyChannel.receive() }
                }
            }

            var executions = 0
            val gate = CompletableDeferred<Unit>()
            val startedMutex = Mutex()
            var started = 0
            val startedForTwoKeys = CompletableDeferred<Unit>()

            val jobs = (1..3).map {
                async {
                    policy.execute {
                        startedMutex.withLock {
                            started++
                            if (started == 2) {
                                startedForTwoKeys.complete(Unit)
                            }
                        }
                        gate.await()
                        executions++
                        "result"
                    }
                }
            }

            keyChannel.send("key-a")
            keyChannel.send("key-a")
            keyChannel.send("key-b")

            startedForTwoKeys.await()
            gate.complete(Unit)

            val results = jobs.awaitAll()

            assertEquals(2, executions) // once for key-a, once for key-b
            results.forEach { assertEquals("result", it) }

            scope.close()
        }

    @Test
    fun `given coalescing when concurrent executes fail then inflight is cleared so next call retries`() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val scope = ResilientScope(testDispatcher)
            val policy = resilient(scope) {
                coalesce { key = "k-fail" }
            }

            var executions = 0
            val gate = CompletableDeferred<Unit>()

            val jobs = (1..5).map {
                async {
                    assertFailsWith<IllegalStateException> {
                        policy.execute {
                            gate.await()
                            executions++
                            throw IllegalStateException("boom")
                        }
                    }
                }
            }

            gate.complete(Unit)
            jobs.awaitAll()

            assertEquals(1, executions)

            val recovered = policy.execute {
                executions++
                "recovered"
            }

            assertEquals("recovered", recovered)
            assertEquals(2, executions)

            scope.close()
        }
}

