plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.kleigiftwatcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.kleigiftwatcher"
        minSdk = 26
        // Personal sideload build; Android 15+ foreground-service rules still vary by OS/vendor.
        targetSdk = 34
        versionCode = 5
        versionName = "0.3.2"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// Chrome Custom Tabs use the user's installed browser rather than bundling Chromium.
dependencies {
    implementation("androidx.browser:browser:1.9.0")
}
