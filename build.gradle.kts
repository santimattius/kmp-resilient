import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

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
    version = project.findProperty("PROJECT_VERSION")?.toString() ?: "2.1.0"

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

// Binary API validation (Kotlin Gradle Plugin built-in ABI validation).
// Reference dumps live in each module's `api/` directory and MUST be committed.
// - `./gradlew apiCheck` fails when the public ABI differs from the committed dump
//   (also wired into each module's `check` by the plugin).
// - `./gradlew apiDump` accepts an intentional ABI change by rewriting the dumps.
val apiValidatedModules = listOf(
    ":shared",
    ":resilient-test",
    ":resilient-ktor",
    ":resilient-micrometer",
    ":resilient-otel",
)

configure(apiValidatedModules.map { project(it) }) {
    pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
        extensions.configure<KotlinMultiplatformExtension> {
            @OptIn(ExperimentalAbiValidation::class)
            abiValidation()
        }
    }
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> {
            @OptIn(ExperimentalAbiValidation::class)
            abiValidation()
        }
    }
}

tasks.register("apiCheck") {
    group = "verification"
    description = "Checks the public binary API of all published modules against the committed dumps."
    dependsOn(apiValidatedModules.map { "$it:checkKotlinAbi" })
}

tasks.register("apiDump") {
    group = "verification"
    description = "Rewrites the committed binary API dumps of all published modules."
    dependsOn(apiValidatedModules.map { "$it:updateKotlinAbi" })
}
