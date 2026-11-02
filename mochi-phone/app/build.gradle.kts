plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes from CI secrets (see mochi-phone/README.md); without them only debug builds work.
val keystore = System.getenv("MOCHI_KEYSTORE")

android {
    namespace = "app.mochi.phone"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.mochi.phone"
        minSdk = 31
        targetSdk = 35
        versionCode = (System.getenv("MOCHI_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("MOCHI_VERSION_NAME") ?: "dev"
    }

    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storeType = "pkcs12"
                storePassword = System.getenv("MOCHI_KEYSTORE_PASSWORD")
                keyAlias = "mochi"
                keyPassword = System.getenv("MOCHI_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
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
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // the "Mochi" wake word, on the phone only (Picovoice Porcupine; needs December's free access key)
    implementation("ai.picovoice:porcupine-android:4.0.2")
}
