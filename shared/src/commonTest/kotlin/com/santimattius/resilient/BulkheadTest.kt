package com.santimattius.resilient

import com.santimattius.resilient.bulkhead.BulkheadConfig
import com.santimattius.resilient.bulkhead.BulkheadFullException
import com.santimattius.resilient.bulkhead.DefaultBulkhead
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith


@OptIn(ExperimentalCoroutinesApi::class)
class BulkheadTest {

    @Test
    fun `given no capacity and no queue when executing then throws BulkheadFullException`() =
        runTest {
            val givenConfig = BulkheadConfig().apply {
                maxConcurrentCalls = 1
                maxWaitingCalls = 0
                timeout = null
            }
            val bulkhead = DefaultBulkhead(givenConfig)

            val first = async { bulkhead.execute { delay(100); "ok" } }
            runCurrent()
            assertFailsWith<BulkheadFullException> { bulkhead.execute { "never" } }
            assertEquals("ok", first.await())
        }


    @Test
    fun `given canceled before acquire when executing then propagates CancellationException`() =
        runTest {
            val givenConfig = BulkheadConfig().apply {
                maxConcurrentCalls = 1
                maxWaitingCalls = 1
                timeout = null
            }
            val bulkhead = DefaultBulkhead(givenConfig)

            val holder = async { bulkhead.execute { delay(10_000); "ok" } }
            runCurrent()

            val deferred = async { bulkhead.execute { "never" } }
            deferred.cancel()
            runCurrent()

            assertFailsWith<CancellationException> { deferred.await() }

            holder.cancel()
            assertFailsWith<CancellationException> { holder.await() }
        }


    @Test
    fun `given waiting when canceled then propagates CancellationException`() = runTest {
        val givenConfig = BulkheadConfig().apply {
            maxConcurrentCalls = 1
            maxWaitingCalls = 1
            timeout = null
        }
        val bulkhead = DefaultBulkhead(givenConfig)

        val holder = async { bulkhead.execute { delay(10_000); "ok" } }
        runCurrent()

        val deferred = async { bulkhead.execute { "never" } }
        runCurrent()
        deferred.cancel()
        assertFailsWith<CancellationException> { deferred.await() }

        holder.cancel()
        assertFailsWith<CancellationException> { holder.await() }
    }


    @Test
    fun `given no queue and canceled before enqueue when executing then propagates CancellationException`() =
        runTest {
            val givenConfig = BulkheadConfig().apply {
                maxConcurrentCalls = 1
                maxWaitingCalls = 0
                timeout = null
            }
            val bulkhead = DefaultBulkhead(givenConfig)

            val holder = async { bulkhead.execute { delay(10_000); "ok" } }
            runCurrent()

            val deferred = async { bulkhead.execute { "never" } }
            deferred.cancel()
            runCurrent()

            assertFailsWith<CancellationException> { deferred.await() }

            holder.cancel()
            assertFailsWith<CancellationException> { holder.await() }
        }


    @Test
    fun `given timeout while waiting when executing then throws TimeoutCancellationException`() =
        runTest {
            val givenConfig = BulkheadConfig().apply {
                maxConcurrentCalls = 1
                maxWaitingCalls = 1
                timeout = kotlin.time.Duration.parse("100ms")
            }
            val bulkhead = DefaultBulkhead(givenConfig)

            val holder = async { bulkhead.execute { delay(10_000); "ok" } }
            runCurrent()

            assertFailsWith<TimeoutCancellationException> { bulkhead.execute { "never" } }

            holder.cancel()
            assertFailsWith<CancellationException> { holder.await() }
        }


    @Test
    fun `given block throws when executing then releases permit`() = runTest {
        val givenConfig = BulkheadConfig().apply {
            maxConcurrentCalls = 1
            maxWaitingCalls = 0
            timeout = null
        }
        val bulkhead = DefaultBulkhead(givenConfig)

        val failing = async {
            assertFailsWith<IllegalStateException> {
                bulkhead.execute {
                    delay(50)
                    throw IllegalStateException("boom")
                }
            }
        }
        runCurrent()
        failing.await()

        val result = bulkhead.execute { "ok-after-failure" }
        assertEquals("ok-after-failure", result)
    }


    @Test
    fun `given queue at capacity when executing then throws BulkheadFullException`() = runTest {
        val givenConfig = BulkheadConfig().apply {
            maxConcurrentCalls = 1
            maxWaitingCalls = 1
            timeout = null
        }
        val bulkhead = DefaultBulkhead(givenConfig)

        val holder = async { bulkhead.execute { delay(10_000); "ok" } }
        runCurrent()
        val waiter = async { bulkhead.execute { "will-wait" } }
        runCurrent()

        assertFailsWith<BulkheadFullException> { bulkhead.execute { "rejected" } }

        waiter.cancel()
        holder.cancel()
        assertFailsWith<CancellationException> { waiter.await() }
        assertFailsWith<CancellationException> { holder.await() }
    }
}
