package com.santimattius.resilient.circuitbreaker

import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * An abstraction for a time source, providing the current time in milliseconds.
 *
 * This interface is used to abstract away the system clock, making it possible
 * to control time in tests and inject different time-keeping mechanisms.
 */
@OptIn(ExperimentalTime::class)
interface TimeSource {
    /**
     * Retrieves the current time expressed in milliseconds since the Unix epoch (January 1, 1970, 00:00:00 UTC).
     *
     * @return A [Long] representing the number of milliseconds since the epoch.
     */
    fun currentTimeMillis(): Long
}

/**
 * A [TimeSource] implementation that uses the system clock.
 *
 * This object provides the current time based on the underlying system's clock,
 * making it suitable for production environments.
 * It uses [kotlin.time.Clock.System] to get the current time.
 */
@OptIn(ExperimentalTime::class)
object SystemTimeSource : TimeSource {
    override fun currentTimeMillis(): Long {
        return Clock.System.now().toEpochMilliseconds()
    }
}

