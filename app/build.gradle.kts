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
        versionCode = 28
        versionName = "4.4.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Signed with debug key so release APK installs directly from GitHub Actions
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
    // Pure Java QR matrix encoder (~15KB after R8, zero native/Play Services bloat)
    implementation("com.google.zxing:core:3.5.3")

    // Fast JVM unit tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
