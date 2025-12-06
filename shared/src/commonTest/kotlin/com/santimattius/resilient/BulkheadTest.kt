package com.santimattius.resilient

import com.santimattius.resilient.bulkhead.BulkheadConfig
import com.santimattius.resilient.bulkhead.BulkheadFullException
import com.santimattius.resilient.bulkhead.DefaultBulkhead
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BulkheadTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun rejectsWhenNoCapacityAndNoQueue() = runTest {
        val cfg = BulkheadConfig().apply {
            maxConcurrentCalls = 1
            maxWaitingCalls = 0
            timeout = null
        }
        val bh = DefaultBulkhead(cfg)

        val first = async { bh.execute { delay(100); "ok" } }
        runCurrent()
        assertFailsWith<BulkheadFullException> {
            bh.execute { "never" }
        }
        assertEquals("ok", first.await())
    }
}
