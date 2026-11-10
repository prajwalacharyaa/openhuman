plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
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
            // R8: drops unused code and resources (smaller APK, faster start). The app uses no reflection;
            // anything that ever needs keeping goes in proguard-rules.pro
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    buildFeatures {
        compose = true
    }
}

dependencies {
    // screens: Jetpack Compose + Material 3 (the calling, ringing, wake word and phone services stay plain Android)
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    // WhileVisible: refresh loops stop while the app is in the background
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
