plugins {
    kotlin("jvm")
    alias(libs.plugins.mavenPublish)
}

kotlin {
    jvmToolchain(11)
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.micrometer.core)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.micrometer.core)
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
        artifactId = "resilient-micrometer",
        version = project.version.toString()
    )
}
