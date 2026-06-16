package com.santimattius.resilient.ktor

import com.santimattius.resilient.ResilientPolicy
import com.santimattius.resilient.composition.resilient
import com.santimattius.resilient.retry.RetryPolicyConfig
import io.ktor.client.call.HttpClientCall
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode

/**
 * Bridges the HTTP-level [shouldRetryOnStatus] predicate into [RetryPolicyConfig.shouldRetryResult].
 *
 * This function mutates [cfg] by composing a new `shouldRetryResult` predicate that:
 * 1. Checks whether the result is an [HttpClientCall] with a status that [shouldRetryOnStatus] marks as retryable.
 * 2. Falls back to any pre-existing user-defined `shouldRetryResult` predicate.
 *
 * The CRITICAL smart-cast (`result is HttpClientCall`) is intentional: the [RetryPolicyConfig.shouldRetryResult]
 * callback receives `Any?`, and omitting this check would cause status-based retry to silently never fire.
 *
 * Called once per plugin install, inside the inline-DSL `retry { }` block.
 */
internal fun bridgeStatusRetry(
    cfg: RetryPolicyConfig,
    shouldRetryOnStatus: (HttpStatusCode) -> Boolean
) {
    val userResultPredicate = cfg.shouldRetryResult
    cfg.shouldRetryResult = { result ->
        val statusSaysRetry = result is HttpClientCall && shouldRetryOnStatus(result.response.status)
        statusSaysRetry || (userResultPredicate?.invoke(result) ?: false)
    }
}

/**
 * A Ktor HTTP client plugin that applies a [com.santimattius.resilient.ResilientPolicy] transparently
 * to every outgoing HTTP request via the [Send] hook.
 *
 * ## Usage — Bring-Your-Own policy
 * ```kotlin
 * val client = HttpClient {
 *     install(ResilientPlugin) {
 *         policy = myExistingPolicy
 *     }
 * }
 * ```
 *
 * ## Usage — Inline DSL
 * ```kotlin
 * val client = HttpClient {
 *     install(ResilientPlugin) {
 *         scope = appScope.asResilientScope()
 *         retry { maxAttempts = 3 }
 *         shouldRetryOnStatus = { it.value >= 500 }
 *     }
 * }
 * ```
 *
 * ## Idempotency gate
 * By default (`retryOnlyIdempotent = true`), POST and PATCH requests bypass the policy entirely,
 * preserving at-most-once semantics. See [ResilientPluginConfig.retryOnlyIdempotent] and ADR-3.
 *
 * ## Policy lifecycle
 * - **BYO policy**: the plugin does NOT close the user-provided policy on `client.close()`.
 * - **Inline DSL**: the plugin owns the policy and closes it via `onClose { }`.
 */
val ResilientPlugin = createClientPlugin("ResilientPlugin", ::ResilientPluginConfig) {
    val cfg = pluginConfig

    // ── Resolve policy + determine ownership (runs ONCE at install time) ──────

    val byo = cfg.policy
    val policyOwned: Boolean
    val resolvedPolicy: ResilientPolicy

    if (byo != null) {
        require(!cfg.hasInlineConfig) {
            "ResilientPlugin: provide either `policy` OR inline DSL blocks (retry { } / circuitBreaker { } / ...), " +
                "not both. Remove `policy` or remove the inline DSL blocks."
        }
        policyOwned = false
        resolvedPolicy = byo
        // shouldRetryOnStatus is inert in the BYO path — configure shouldRetryResult
        // directly inside the provided ResilientPolicy's RetryPolicyConfig instead.
    } else {
        require(cfg.hasInlineConfig) {
            "ResilientPlugin: no policy source configured. " +
                "Either set `policy = yourPolicy` (BYO) or provide `scope` and at least one " +
                "policy block (retry { } / circuitBreaker { } / ...)."
        }
        val scope = requireNotNull(cfg.scope) {
            "ResilientPlugin: inline DSL requires `scope`. " +
                "Set `scope = yourCoroutineScope.asResilientScope()` in the plugin configuration."
        }
        policyOwned = true
        resolvedPolicy = resilient(scope) {
            // ── Retry block: also bridges the HTTP status predicate ────────────
            cfg.retryBlock?.let { userRetry ->
                retry {
                    userRetry()                              // apply user's settings first
                    bridgeStatusRetry(this, cfg.shouldRetryOnStatus) // then wire status predicate
                }
            } ?: run {
                // No explicit retry block, but status retry is still desired (default or custom).
                // Install a bare retry block solely to host the bridged status predicate so that
                // 5xx responses are retried up to maxAttempts (3 by default).
                retry {
                    bridgeStatusRetry(this, cfg.shouldRetryOnStatus)
                }
            }

            cfg.circuitBreakerBlock?.let { circuitBreaker(it) }
            cfg.rateLimiterBlock?.let { rateLimiter(it) }
            cfg.bulkheadBlock?.let { bulkhead(it) }
            cfg.timeoutBlock?.let { timeout(it) }
        }
    }

    // ── on(Send) hook: wraps each outgoing request in the policy ─────────────
    //
    // The Send hook in Ktor 3.x exposes a `Sender` receiver with a `proceed(request)` function
    // that returns `HttpClientCall`. This is verified against the Ktor 3.2.0 `CommonHooks.kt` API.
    on(Send) { request ->
        val nonIdempotent = request.method == HttpMethod.Post || request.method == HttpMethod.Patch
        if (cfg.retryOnlyIdempotent && nonIdempotent) {
            // Bypass the policy entirely for non-idempotent methods (ADR-3).
            // This preserves at-most-once semantics: no retry, no circuit breaker check,
            // no timeout wrapping for POST/PATCH when retryOnlyIdempotent = true.
            proceed(request)
        } else {
            resolvedPolicy.execute { proceed(request) }
        }
    }

    // ── onClose: release the policy when the client is closed ─────────────────
    onClose {
        if (policyOwned) {
            resolvedPolicy.close()
        }
    }
}
