plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.kotlinAndroid) apply false
    alias(libs.plugins.androidKMPLibrary) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.mavenPublish) apply false
    alias(libs.plugins.allOpen) apply false
    alias(libs.plugins.kotlinxBenchmark) apply false
    alias(libs.plugins.dokka)
}

allprojects {
    group = project.findProperty("PROJECT_GROUP")?.toString() ?: "io.github.santimattius.resilient"
    version = project.findProperty("PROJECT_VERSION")?.toString() ?: "2.0.0-ALPHA01"

    repositories {
        mavenCentral()
        google()
    }
}

dependencies {
    dokka(project(":shared"))
    dokka(project(":resilient-test"))
    dokka(project(":resilient-ktor"))
    dokka(project(":resilient-micrometer"))
    dokka(project(":resilient-otel"))
}
