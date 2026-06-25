import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.allOpen)
    alias(libs.plugins.kotlinxBenchmark)
    // NO androidKMPLibrary: Android KMP benchmark target is blocked by upstream
    // kotlinx-benchmark PR #368 (unmerged in 0.4.17). JVM acts as the Android proxy.
    // NO mavenPublish: this module is never published.
}

// allopen: JMH requires @State-annotated benchmark classes to be `open` for subclass instrumentation.
allOpen {
    annotation("org.openjdk.jmh.annotations.State")
}

kotlin {
    jvm {
        compilations.all {
            compilerOptions.configure { jvmTarget.set(JvmTarget.JVM_11) }
        }
    }
    macosArm64()          // only Apple-Silicon-host Native target that runs without cross-compilation
    js(IR) {
        nodejs()          // browser runner does not exist in kotlinx-benchmark; nodejs only
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared"))
            implementation(libs.kotlinx.benchmark.runtime)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}

// Configuration Cache (CC) compatibility: verified with kotlinx-benchmark 0.4.17 on Kotlin 2.3.20.
// Running `./gradlew :benchmark:jvmBenchmark --configuration-cache` twice produced
// "Configuration cache entry reused." on the second run — no workaround needed.
benchmark {
    configurations {
        named("main") {
            warmups = 5
            iterations = 10
            iterationTime = 1
            iterationTimeUnit = "s"
            mode = "avgt"           // AverageTime
            outputTimeUnit = "us"   // MICROSECONDS
        }
        register("smoke") {
            // Fast CI sanity check: verifies benchmarks compile and execute, not precision.
            warmups = 0
            iterations = 1
            iterationTime = 1
            iterationTimeUnit = "s"
            mode = "avgt"
            outputTimeUnit = "us"
        }
    }
    targets {
        register("jvm")
        register("macosArm64")
        register("js")
    }
}
