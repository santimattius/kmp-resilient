package com.santimattius.resilient.timeout

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * An interface for policies that enforce a timeout on suspendable operations.
 *
 * This policy wraps a suspendable `block` of code and ensures it completes
 * within a specified maximum execution [Duration]. If the operation exceeds the
 * timeout, it will be cancelled.
 */
interface TimeoutPolicy {
    suspend fun <T> execute(block: suspend () -> T): T
}

/**
 * Configuration for a [TimeoutPolicy].
 *
 * @property timeout The maximum allowed duration for the operation before it's considered timed out.
 *                   Defaults to 30 seconds.
 * @property onTimeout A suspendable callback that is invoked when a timeout occurs.
 *                     This is executed before the [kotlinx.coroutines.TimeoutCancellationException] is thrown.
 */
class TimeoutConfig {
    var timeout: Duration = 30.seconds
    var onTimeout: suspend () -> Unit = { }
}
