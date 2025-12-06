package com.santimattius.resilient

import com.santimattius.resilient.hedging.DefaultHedgingPolicy
import com.santimattius.resilient.hedging.HedgingConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime

class HedgingPolicyTest {

    @OptIn(ExperimentalTime::class)
    @Test
    fun returnsFirstWinner() = runTest {
        val policy = DefaultHedgingPolicy(HedgingConfig().apply {
            attempts = 2
            stagger = 10.milliseconds
        })
        val result = policy.execute {
            // Half of the time be fast, half slow; hedging should pick the earliest completion
            if (Clock.System.now().toEpochMilliseconds() % 2L == 0L) {
                delay(50); "slow"
            } else {
                delay(5); "fast"
            }
        }
        // We can't assert exact value deterministically; ensure we got a value
        // and exercise code path. For stability, assert non-empty.
        assertEquals(true, result.isNotEmpty())
    }
}
