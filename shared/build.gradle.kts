import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKMPLibrary)
    alias(libs.plugins.mavenPublish)
}

group = "io.github.santimattius.resilient"
version = "1.0.0-ALPHA03"

kotlin {
    androidLibrary {
        namespace = "io.github.santimattius.resilient"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Resilient"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            // put your Multiplatform dependencies here
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }
    }
}

mavenPublishing {
    publishToMavenCentral()

    signAllPublications()

    coordinates(group.toString(), "resilient", version.toString())

    pom {
        name = "Resilient Kotlin Multiplatform Library"
        description =
            "A Kotlin Multiplatform library providing resilience patterns (Timeout, Retry, Circuit Breaker, Rate Limiter, Bulkhead, Hedging, Cache, Fallback) for suspend functions."
        inceptionYear = "2025"
        url = "https://github.com/santimattius/kmp-resilient/"
        licenses {
            license {
                name = "The Apache License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "https://www.apache.org/licenses/LICENSE-2.0.txt"
            }
        }
        developers {
            developer {
                id = "santiago-mattiauda"
                name = "Santiago Mattiauda"
                url = "https://github.com/santimattius"
            }
        }
        scm {
            url = "https://github.com/santimattius/kmp-resilient/"
            connection = "scm:git:git://github.com/santimattius/kmp-resilient.git"
            developerConnection = "scm:git:ssh://git@github.com/santimattius/kmp-resilient.git"
        }
    }
}
