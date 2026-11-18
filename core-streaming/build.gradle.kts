plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.example.kilo.streaming"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation("androidx.media3:media3-common:1.4.1")
    implementation("androidx.media3:media3-datasource:1.4.1")
    implementation("androidx.media3:media3-database:1.4.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Google Tink for AES-256-GCM encryption backed by Android Keystore
    implementation("com.google.crypto.tink:tink-android:1.12.0")
}