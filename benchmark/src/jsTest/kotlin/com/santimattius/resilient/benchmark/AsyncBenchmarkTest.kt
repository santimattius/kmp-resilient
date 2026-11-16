package com.santimattius.resilient.benchmark

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AsyncBenchmarkTest {
    @Test
    fun delayedResultPreservesValueAndDoesNotCompleteEarly(): Promise<Unit> {
        val owner = BenchmarkOwner()
        val gate = CompletableDeferred<Unit>()
        var finished = false
        val result =
            owner.run {
                gate.await()
                42
            }
        result.then { finished = true }
        return MainScope().promise {
            assertFalse(finished)
            gate.complete(Unit)
            assertEquals(42, result.await())
            owner.close()
        }
    }

    @Test
    fun failureAndCancellationRemainRejections(): Promise<Unit> {
        val owner = BenchmarkOwner()
        val failure = owner.run<Int> { throw IllegalStateException("expected") }
        val cancelled = owner.run<Int> { throw CancellationException("cancelled") }
        return MainScope().promise {
            assertEquals("expected", assertFailsWith<IllegalStateException> { failure.await() }.message)
            assertFailsWith<CancellationException> { cancelled.await() }
            owner.close()
        }
    }

    @Test
    fun preparationMustFinishBeforeRejectAndPayloadIsNotRun(): Promise<Unit> {
        val owner = BenchmarkOwner()
        val gate = CompletableDeferred<Unit>()
        val fixture = BreakerFixture()
        fixture.setup()
        val preparation =
            owner.run {
                gate.await()
                fixture.prepareOpen()
            }
        var payloadCalled = false
        val result =
            owner.run {
                preparation.await()
                fixture.openReject {
                    payloadCalled = true
                    1
                }
            }
        return MainScope().promise {
            assertFalse(payloadCalled)
            gate.complete(Unit)
            result.await()
            assertFalse(payloadCalled)
            owner.close()
        }
    }

    @Test
    fun closeCancelsInFlightWork(): Promise<Unit> {
        val owner = BenchmarkOwner()
        val started = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val result =
            owner.run {
                started.complete(Unit)
                never.await()
            }
        return MainScope().promise {
            started.await()
            owner.close()
            assertFailsWith<CancellationException> { result.await() }
            assertTrue(never.isActive)
        }
    }
}
