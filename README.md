
# KMP Basic Template
This is a Kotlin Multiplatform project targeting Android, iOS.

* [/androidApp](./androidApp/src) contains Android applications.
* [/iosApp](./iosApp/iosApp) contains iOS applications. Even if you’re sharing your UI with Compose Multiplatform,
  you need this entry point for your iOS app. This is also where you should add SwiftUI code for your project.

* [/shared](./shared/src) is for the code that will be shared between all targets in the project.
  The most important subfolder is [commonMain](./shared/src/commonMain/kotlin). If preferred, you can add code to the platform-specific folders here too.

## Important Configuration Steps When Using This Template

When creating a new project based on this template, you need to modify the following:

1.  **Project Name in settings.gradle.kts:**
    *   Open the `settings.gradle.kts` file in the root of your project.
    *   Change the `rootProject.name` attribute to your desired project name.
        ```kotlin
        rootProject.name = "YourNewProjectName"
        ```

2.  **Android Application ID:**
    *   Open the `build.gradle.kts` file located in the `androidApp` module (`./androidApp/build.gradle.kts`).
    *   Modify the `applicationId` within the `defaultConfig` block to your unique application ID.
        ```kotlin
        android {
            // ...
            defaultConfig {
                applicationId = "com.yourcompany.yournewprojectname"
                // ...
            }
            // ...
        }
        ```

3.  **iOS Configuration (Config.xcconfig):**
    *   Open the `Config.xcconfig` file located in the `iosApp/Configuration` directory (`./iosApp/Configuration/Config.xcconfig`).
    *   Update the necessary values such as `APP_ID` (Bundle ID) and `APP_NAME` (Display Name) with your project-specific information.
        ```
        APP_ID = com.yourcompany.YourNewProjectName
        APP_NAME = YourNewProjectName
        ```

## Verify Your Environment

It is recommended to install and run [kdoctor](https://github.com/Kotlin/kdoctor) to verify that your development environment is correctly set up for Kotlin Multiplatform development. `kdoctor` helps diagnose and fix common configuration issues.

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)
