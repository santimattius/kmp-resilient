package com.santimattius.resilient.composition

import com.santimattius.resilient.annotations.ResilientExperimentalApi
import com.santimattius.resilient.bulkhead.BulkheadConfig
import com.santimattius.resilient.cache.CacheConfig
import com.santimattius.resilient.chaos.ChaosConfig
import com.santimattius.resilient.circuitbreaker.CircuitBreakerConfig
import com.santimattius.resilient.coalescing.CoalesceConfig
import com.santimattius.resilient.fallback.FallbackConfig
import com.santimattius.resilient.hedging.HedgingConfig
import com.santimattius.resilient.ratelimiter.RateLimiterConfig
import com.santimattius.resilient.retry.RetryPolicyConfig
import com.santimattius.resilient.timeout.TimeoutConfig

/*
 * Definition-only snapshots of ordinary policy configurations. Copies carry configuration values
 * and callbacks, never runtime state (no Default* instances, mutexes, buckets, caches or jobs).
 */

internal fun RetryPolicyConfig.snapshot(): RetryPolicyConfig {
    val source = this
    return RetryPolicyConfig().apply {
        maxAttempts = source.maxAttempts
        backoffStrategy = source.backoffStrategy
        shouldRetry = source.shouldRetry
        shouldRetryResult = source.shouldRetryResult
        perAttemptTimeout = source.perAttemptTimeout
        onRetry = source.onRetry
    }
}

internal fun CircuitBreakerConfig.snapshot(): CircuitBreakerConfig {
    val source = this
    return CircuitBreakerConfig().apply {
        failureThreshold = source.failureThreshold
        successThreshold = source.successThreshold
        timeout = source.timeout
        halfOpenMaxCalls = source.halfOpenMaxCalls
        shouldRecordFailure = source.shouldRecordFailure
        shouldRecordResult = source.shouldRecordResult
        slidingWindow = source.slidingWindow
        failureRateThreshold = source.failureRateThreshold
        minimumNumberOfCalls = source.minimumNumberOfCalls
        onStateChange = source.onStateChange
    }
}

internal fun RateLimiterConfig.snapshot(): RateLimiterConfig {
    val source = this
    return RateLimiterConfig().apply {
        maxCalls = source.maxCalls
        period = source.period
        timeoutWhenLimited = source.timeoutWhenLimited
        onRateLimited = source.onRateLimited
    }
}

internal fun BulkheadConfig.snapshot(): BulkheadConfig {
    val source = this
    return BulkheadConfig().apply {
        maxConcurrentCalls = source.maxConcurrentCalls
        maxWaitingCalls = source.maxWaitingCalls
        timeout = source.timeout
    }
}

internal fun TimeoutConfig.snapshot(): TimeoutConfig {
    val source = this
    return TimeoutConfig().apply {
        timeout = source.timeout
        onTimeout = source.onTimeout
    }
}

internal fun CacheConfig.snapshot(): CacheConfig {
    val source = this
    return CacheConfig().apply {
        key = source.key
        keyProvider = source.keyProvider
        ttl = source.ttl
        cleanupInterval = source.cleanupInterval
        cleanupBatch = source.cleanupBatch
    }
}

internal fun CoalesceConfig.snapshot(): CoalesceConfig {
    val source = this
    return CoalesceConfig().apply {
        key = source.key
        keyProvider = source.keyProvider
    }
}

internal fun FallbackConfig<Any?>.snapshot(): FallbackConfig<Any?> = FallbackConfig(onFallback)

internal fun HedgingConfig.snapshot(): HedgingConfig {
    val source = this
    return HedgingConfig().apply {
        attempts = source.attempts
        stagger = source.stagger
    }
}

@ResilientExperimentalApi
internal fun ChaosConfig.snapshot(): ChaosConfig {
    val source = this
    return ChaosConfig().apply {
        enabled = source.enabled
        failureRate = source.failureRate
        latency = source.latency
        exception = source.exception
        injectResult = source.injectResult
    }
}
