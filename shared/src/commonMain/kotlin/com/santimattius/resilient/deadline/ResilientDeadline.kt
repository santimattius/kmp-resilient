package com.santimattius.resilient.deadline

import com.santimattius.resilient.annotations.ResilientExperimentalApi
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * A [CoroutineContext] element that carries a deadline for resilient policy execution.
 *
 * When present in the coroutine context, the [com.santimattius.resilient.composition.resilient]
 * builder will enforce this deadline on every `execute` call. If the deadline has already expired
 * when `execute` is invoked, a [kotlinx.coroutines.TimeoutCancellationException] is thrown
 * immediately without invoking the block. If both an explicit `timeout { }` and a deadline are
 * configured, the **shorter** of the two wins.
 *
 * Usage:
 * ```kotlin
 * withContext(ResilientDeadline.after(5.seconds)) {
 *     policy.execute { callRemoteService() }
 * }
 * ```
 *
 * @property deadline A [TimeMark] that represents the point in time at which the deadline expires.
 *                    Created via [after] which sets it to `now + duration`.
 */
@ResilientExperimentalApi
class ResilientDeadline(val deadline: TimeMark) : AbstractCoroutineContextElement(Key) {

    companion object Key : CoroutineContext.Key<ResilientDeadline> {

        /**
         * Creates a [ResilientDeadline] that expires [duration] from now.
         *
         * A negative [duration] produces an already-expired deadline, which will cause
         * [com.santimattius.resilient.composition.resilient] to throw immediately on `execute`.
         */
        @ResilientExperimentalApi
        fun after(duration: Duration): ResilientDeadline =
            ResilientDeadline(TimeSource.Monotonic.markNow() + duration)
    }

    /**
     * Returns the time remaining until the deadline expires.
     *
     * - Positive value → deadline is in the future (time still available).
     * - Zero or negative value → deadline has already passed.
     *
     * Implementation note: [TimeMark.elapsedNow] returns a positive duration for marks in the
     * past, and a negative duration for marks in the future. Negating it gives us the intuitive
     * "remaining" semantics (positive = time left, negative = expired).
     */
    fun remaining(): Duration = -deadline.elapsedNow()
}
