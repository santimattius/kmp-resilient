package com.santimattius.resilient.test

import com.santimattius.resilient.composition.ResilientScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * A [ResilientScope] factory for testing.
 *
 * Use this in unit tests to create resilient policies without needing a real application scope.
 * The returned [ResilientScope] can be closed to cancel all policies and operations.
 *
 * **Example:**
 * ```kotlin
 * @Test
 * fun `test retry policy`() = runTest {
 *     val testScope = TestResilientScope()
 *     val policy = resilient(testScope) {
 *         retry { maxAttempts = 3 }
 *     }
 *     // ... test logic
 *     testScope.close() // cleanup
 * }
 * ```
 *
 * @param dispatcher The [CoroutineDispatcher] for the scope. Defaults to [Dispatchers.Default].
 * @return A [ResilientScope] instance for testing.
 */
fun TestResilientScope(dispatcher: CoroutineDispatcher = Dispatchers.Default): ResilientScope {
    return ResilientScope(dispatcher)
}
