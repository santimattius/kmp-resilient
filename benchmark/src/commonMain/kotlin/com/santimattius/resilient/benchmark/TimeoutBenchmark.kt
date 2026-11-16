package com.santimattius.resilient.benchmark

import com.santimattius.resilient.timeout.DefaultTimeoutPolicy
import com.santimattius.resilient.timeout.TimeoutConfig
import com.santimattius.resilient.timeout.TimeoutPolicy
import kotlin.time.Duration.Companion.seconds

internal class TimeoutFixture {
    private lateinit var policy: TimeoutPolicy

    fun setup() {
        policy = DefaultTimeoutPolicy(TimeoutConfig().apply { timeout = 30.seconds })
    }

    suspend fun withinDeadline(): Int = policy.execute { unit() }
}
