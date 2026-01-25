package com.santimattius.resilient.annotations

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