package com.santimattius.resilient.annotations

/**
 * Marks an API as experimental. Use [OptIn] to acknowledge that the API may change in future releases.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This API is experimental and may change in the future."
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR
)
annotation class ResilientExperimentalApi