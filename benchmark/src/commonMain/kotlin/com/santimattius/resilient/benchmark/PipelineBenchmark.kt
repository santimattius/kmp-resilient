package com.santimattius.resilient.benchmark

import com.santimattius.resilient.ResilientPolicy
import com.santimattius.resilient.composition.ResilientScope
import com.santimattius.resilient.composition.TypedResilientPolicy
import com.santimattius.resilient.composition.resilient
import com.santimattius.resilient.composition.resilientFor
import kotlin.time.Duration.Companion.seconds

internal class PipelineFixture {
    private lateinit var scope: ResilientScope
    private lateinit var legacy: ResilientPolicy
    private lateinit var typed: TypedResilientPolicy<Int>

    fun setup() {
        scope = ResilientScope()
        legacy =
            resilient(scope) {
                timeout { timeout = 30.seconds }
                retry { maxAttempts = 3 }
                rateLimiter {
                    maxCalls = UNBOUNDED_CALLS
                    period = 1.seconds
                }
            }
        typed =
            resilientFor<Int>(scope) {
                timeout { timeout = 30.seconds }
                retry { maxAttempts = 3 }
                rateLimiter {
                    maxCalls = UNBOUNDED_CALLS
                    period = 1.seconds
                }
            }
    }

    fun tearDown() {
        legacy.close()
        typed.close()
        scope.close()
    }

    suspend fun legacyPipeline(): Int = legacy.execute { unit() }

    suspend fun typedPipeline(): Int = typed.execute(1) { unit() }
}
