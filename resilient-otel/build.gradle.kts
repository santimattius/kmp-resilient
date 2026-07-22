plugins {
    kotlin("jvm")
    alias(libs.plugins.mavenPublish)
}

kotlin {
    jvmToolchain(11)
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.opentelemetry.api)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.opentelemetry.sdk)
    testImplementation(libs.opentelemetry.sdk.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
}

mavenPublishing {
    coordinates(
        groupId = project.group.toString(),
        artifactId = "resilient-otel",
        version = project.version.toString()
    )
}
