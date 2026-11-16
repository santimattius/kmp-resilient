import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsExec
import java.io.File
import java.util.Base64

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
    macosArm64() // only Apple-Silicon-host Native target that runs without cross-compilation
    js(IR) {
        nodejs() // browser runner does not exist in kotlinx-benchmark; nodejs only
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared"))
            implementation(libs.kotlinx.benchmark.runtime)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        val blockingMain by creating { dependsOn(commonMain.get()) }
        jvmMain.get().dependsOn(blockingMain)
        macosArm64Main.get().dependsOn(blockingMain)
    }
}

// Capture only the successful smoke runner's public invocation fields, after NodeJsExec
// has assembled its final arguments. The replay helper requires a pre-run boundary.
tasks.withType<NodeJsExec>().configureEach {
    if (name == "jsBenchmarkProductionExecutableSmokeBenchmark") {
        val destination =
            layout.buildDirectory
                .dir("js-smoke-invocation")
                .get()
                .asFile
        doLast {
            val actual = args.orEmpty().map { it.toString() }
            val runner = inputFileProperty.get().asFile.absoluteFile
            check(
                actual.size == 6 && actual[0] == "--require" && actual[2] == "-r" &&
                    actual[3] == "source-map-support/register" && File(actual[4]).absoluteFile == runner,
            ) {
                "Unexpected JS smoke Node invocation; refusing to export it"
            }
            val node = File(checkNotNull(executable)).absoluteFile
            val cwd = workingDir.absoluteFile
            val preload = File(actual[1]).absoluteFile
            val sourceConfig = File(actual[5]).absoluteFile
            check(node.isFile && cwd.isDirectory && runner.isFile && preload.isFile && sourceConfig.isFile) {
                "JS smoke invocation contains missing files"
            }
            destination.mkdirs()
            val config = destination.resolve("config.txt")
            config.writeText(sourceConfig.readText(Charsets.UTF_8), Charsets.UTF_8)
            val values = linkedMapOf("node" to node, "cwd" to cwd, "runner" to runner, "preload" to preload, "config" to config)
            val encoder = Base64.getEncoder()
            destination.resolve("invocation.txt").writeText(
                values.entries.joinToString("\n", postfix = "\n") { (key, value) ->
                    "$key=${encoder.encodeToString(value.absolutePath.toByteArray(Charsets.UTF_8))}"
                },
                Charsets.UTF_8,
            )
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
            mode = "avgt" // AverageTime
            outputTimeUnit = "us" // MICROSECONDS
        }
        register("smoke") {
            // JS runtime maps warmups to benchmark.js initCount, which must be positive.
            // On JVM this also adds one warmup iteration to the smoke run.
            warmups = 1
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
