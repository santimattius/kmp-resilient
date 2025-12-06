package com.santimattius.resilient

import com.santimattius.resilient.timeout.DefaultTimeoutPolicy
import com.santimattius.resilient.timeout.TimeoutConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds

class TimeoutPolicyTest {

    @Test
    fun timesOut() = runTest {
        val cfg = TimeoutConfig().apply { timeout = 50.milliseconds }
        val policy = DefaultTimeoutPolicy(cfg)
        assertFailsWith<Exception> {
            policy.execute {
                delay(200)
                "done"
            }
        }
    }
}
