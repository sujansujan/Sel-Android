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
        versionCode = 21
        versionName = "2.8.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Pure Java QR matrix generator (~15KB after R8 minification)
    implementation("com.google.zxing:core:3.5.3")

    // Thin Google Play Services ML Kit on-device OCR client (model lives in Play Services, minimal APK impact)
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")

    // Fast JVM unit testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
