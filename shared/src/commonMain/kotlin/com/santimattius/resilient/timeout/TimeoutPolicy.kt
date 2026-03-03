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
    /**
     * Executes [block] with a time limit. If execution exceeds the configured timeout, it is cancelled.
     * @param T The return type of the block.
     * @param block The suspendable operation to execute.
     * @return The result of [block] when it completes within the timeout.
     * @throws kotlinx.coroutines.TimeoutCancellationException When the timeout is exceeded.
     */
    suspend fun <T> execute(block: suspend () -> T): T
}

/**
 * Configuration for a [TimeoutPolicy].
 *
 * **Behaviour and composition:** This policy applies a single time limit to the execution of the block.
 * When combined with [com.santimattius.resilient.retry.RetryPolicy], the order of composition matters:
 * - **Timeout outer, Retry inner (default):** The timeout applies to the **entire** execution (all retries combined).
 *   One slow attempt can use the full duration; when it expires, the whole operation fails.
 * - **Retry outer, Timeout inner:** Each retry attempt has its own timeout. Use [ResilientBuilder.compositionOrder]
 *   to place [OrderablePolicyType.RETRY] before [OrderablePolicyType.TIMEOUT].
 *
 * For a timeout **per retry attempt** without changing composition order, use [RetryPolicyConfig.perAttemptTimeout].
 *
 * @property timeout The maximum allowed duration for the operation before it's considered timed out.
 *                   Defaults to 30 seconds. When converted to milliseconds for the underlying API, very large
 *                   values may overflow; keep duration within a reasonable range (e.g. under [Long.MAX_VALUE] ms).
 * @property onTimeout A suspendable callback that is invoked when a timeout occurs.
 *                     This is executed before the [kotlinx.coroutines.TimeoutCancellationException] is thrown.
 */
class TimeoutConfig {
    var timeout: Duration = 30.seconds
    var onTimeout: suspend () -> Unit = { }
}
