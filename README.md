
# Resilient
This is a Kotlin Multiplatform project targeting Android, iOS.

* [/androidApp](./androidApp/src) contains Android applications.
* [/iosApp](./iosApp/iosApp) contains iOS applications. Even if you’re sharing your UI with Compose Multiplatform,
  you need this entry point for your iOS app. This is also where you should add SwiftUI code for your project.

* [/shared](./shared/src) is for the code that will be shared between all targets in the project.
  The most important subfolder is [commonMain](./shared/src/commonMain/kotlin). If preferred, you can add code to the platform-specific folders here too.

## Verify Your Environment

It is recommended to install and run [kdoctor](https://github.com/Kotlin/kdoctor) to verify that your development environment is correctly set up for Kotlin Multiplatform development. `kdoctor` helps diagnose and fix common configuration issues.

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)
