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
        versionCode = 41
        versionName = "5.0.2"
    }

    signingConfigs {
        val ksPath = System.getenv("RELEASE_KEYSTORE_PATH")
        val ksPass = System.getenv("RELEASE_KEYSTORE_PASS")
        val keyAliasEnv = System.getenv("RELEASE_KEY_ALIAS")
        val keyPassEnv = System.getenv("RELEASE_KEY_PASS")
        if (!ksPath.isNullOrBlank() && !ksPass.isNullOrBlank() && !keyAliasEnv.isNullOrBlank() && !keyPassEnv.isNullOrBlank()) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = ksPass
                keyAlias = keyAliasEnv
                keyPassword = keyPassEnv
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Uses persistent GitHub Secrets release keystore in CI (enabling seamless in-place updates);
            // falls back to debug key for local/fork builds without signing secrets.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
