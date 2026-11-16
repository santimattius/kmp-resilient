package com.santimattius.resilient.benchmark

import com.santimattius.resilient.retry.DefaultRetryPolicy
import com.santimattius.resilient.retry.FixedBackoff
import com.santimattius.resilient.retry.RetryPolicy
import com.santimattius.resilient.retry.RetryPolicyConfig
import kotlin.time.Duration

internal class RetryFixture {
    private lateinit var policy: RetryPolicy

    fun setup(attempts: Int) {
        policy =
            DefaultRetryPolicy(
                RetryPolicyConfig().apply {
                    maxAttempts = attempts
                    backoffStrategy = FixedBackoff(Duration.ZERO)
                },
            )
    }

    suspend fun happyPath(): Int = policy.execute { unit() }
}
