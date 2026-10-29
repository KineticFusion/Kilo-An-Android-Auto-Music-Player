plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.example.kilo.playback.prepared"
    compileSdk { version = release(37) }
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(project(":playbackoptimization"))
    api(project(":core-streaming"))
    api("androidx.media3:media3-common:1.4.1")
}
