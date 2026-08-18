plugins {
    // An Xposed/LSPosed module ships as an installable APK (not an AAR library).
    alias(libs.plugins.android.application)
    // Kotlin compilation is provided by AGP 9 built-in Kotlin support —
    // applying org.jetbrains.kotlin.android conflicts with it.
}

android {
    namespace = "com.example.stepshift.xposed"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.stepshift.xposed"
        minSdk = 28
        targetSdk = 36
        versionCode = 7
        versionName = "0.7"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly(libs.xposed.api)
}
