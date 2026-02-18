package com.santimattius.resilient.fallback

/**
 * Configuration class for the Fallback policy.
 *
 * This class holds the configuration for what action to take when a fallback is triggered.
 *
 * @param T The type of the result that the fallback action will return.
 * @property onFallback A suspend lambda function that is executed when an operation fails.
 *                     It receives the `Throwable` that caused the failure and must return a value of type [T].
 */
class FallbackConfig<T>(
    var onFallback: suspend (Throwable) -> T
)

/**
 * A resilience policy that provides a fallback mechanism.
 *
 * This policy executes a given code block and, if an exception (a [Throwable]) is caught,
 * it executes a predefined fallback function. The result of the fallback function is then
 * returned as the result of the execution.
 *
 * This is useful for providing default values or alternative logic when an operation fails,
 * ensuring that the application can continue to function gracefully.
 *
 * @param config The configuration for the fallback policy, specifying the action to take on failure.
 */
class FallbackPolicy(
    private val config: FallbackConfig<Any?>
) {
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> execute(block: suspend () -> T): T {
        return try {
            block()
        } catch (t: Throwable) {
            config.onFallback(t) as T
        }
    }
}
