package com.santimattius.resilient.circuitbreaker

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A testable time source that allows manual control of time for testing.
 * This makes tests deterministic and avoids timing issues.
 * 
 * This implementation uses a mutex for writes and direct reads for performance.
 * In test scenarios, reads are typically safe since time advances are controlled.
 */
class TestTimeSource : TimeSource {
    private val mutex = Mutex()
    private var _currentTimeMs: Long = 0L

    /**
     * Sets the current time in milliseconds.
     */
    suspend fun setTime(timeMs: Long) {
        mutex.withLock {
            _currentTimeMs = timeMs
        }
    }

    /**
     * Advances the current time by the specified duration in milliseconds.
     */
    suspend fun advanceTimeBy(durationMs: Long) {
        mutex.withLock {
            _currentTimeMs += durationMs
        }
    }

    override fun currentTimeMillis(): Long {
        // Direct read - in test scenarios this is safe since we control time advances
        // The circuit breaker uses this inside mutex locks, so concurrent access is already protected
        return _currentTimeMs
    }

    /**
     * Resets the time source to 0.
     */
    suspend fun reset() {
        mutex.withLock {
            _currentTimeMs = 0L
        }
    }
}

