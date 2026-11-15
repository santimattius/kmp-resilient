package com.santimattius.resilient.benchmark

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.promise
import kotlin.js.Promise

/** Owns every promise until benchmark teardown. Failures stay observable to callers. */
internal class BenchmarkOwner {
    val scope: CoroutineScope = MainScope()

    fun <T> run(block: suspend () -> T): Promise<T> = scope.promise { block() }

    fun close() = scope.cancel()
}
