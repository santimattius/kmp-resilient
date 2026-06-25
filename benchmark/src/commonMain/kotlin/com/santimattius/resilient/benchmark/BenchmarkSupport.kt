package com.santimattius.resilient.benchmark

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Runs [body] inside [runBlocking] with [Dispatchers.Unconfined].
 *
 * Using Unconfined avoids any dispatcher dispatch or thread-handoff overhead, so the
 * measurement captures policy orchestration cost only — not coroutine scheduling cost.
 * Use for all happy-path benchmarks that do not contain real [kotlinx.coroutines.delay] calls.
 */
inline fun <T> blockingUnconfined(crossinline body: suspend () -> T): T =
    runBlocking(Dispatchers.Unconfined) { body() }

/**
 * A trivial suspend payload that returns immediately.
 * Used as the workload inside benchmark bodies so that what is measured is the policy
 * wrapper overhead, not the workload cost itself.
 */
suspend fun unit(): Int = 1
