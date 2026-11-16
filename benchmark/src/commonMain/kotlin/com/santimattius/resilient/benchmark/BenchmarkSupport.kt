package com.santimattius.resilient.benchmark

/** Trivial payload shared by the measured workloads. */
suspend fun unit(): Int = 1

internal const val UNBOUNDED_CALLS = 1_000_000_000
