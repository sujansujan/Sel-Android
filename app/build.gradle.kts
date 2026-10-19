plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.selclip"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.selclip"
        minSdk = 26
        targetSdk = 34
        versionCode = 13
        versionName = "2.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Signed with debug key so you can install the release APK directly from GitHub Actions
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Pure Java QR matrix generator (~15KB after R8 minification, zero AndroidX dependencies)
    implementation("com.google.zxing:core:3.5.3")
}
