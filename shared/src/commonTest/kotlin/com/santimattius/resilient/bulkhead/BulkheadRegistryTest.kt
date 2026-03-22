package com.santimattius.resilient.bulkhead

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class BulkheadRegistryTest {

    @Test
    fun `given same name when getOrCreate twice then returns same instance`() {
        val registry = BulkheadRegistry()
        val first = registry.getOrCreate("db", configure = { maxConcurrentCalls = 2 })
        val second = registry.getOrCreate("db", configure = { maxConcurrentCalls = 99 })
        assertSame(first, second)
        assertEquals(2, first.snapshot().maxConcurrentCalls)
    }

    @Test
    fun `given different names when getOrCreate then distinct instances`() {
        val registry = BulkheadRegistry()
        val a = registry.getOrCreate("a", configure = { maxConcurrentCalls = 1 })
        val b = registry.getOrCreate("b", configure = { maxConcurrentCalls = 1 })
        assertSame(a, registry.getOrCreate("a", configure = { }))
        assertSame(b, registry.getOrCreate("b", configure = { }))
    }
}
