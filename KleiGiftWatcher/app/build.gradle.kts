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
        versionCode = 10
        versionName = "0.4.4"
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}


dependencies { testImplementation("junit:junit:4.13.2") }
