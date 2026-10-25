plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.kilo"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.kilo"
        minSdk = 24
        targetSdk = 37
        versionCode = 23
        versionName = "2.7.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // Keep the signing identity used by the published 1.5.5 APK so
            // local release builds can update existing KILO installations.
            signingConfig = signingConfigs.getByName("debug")
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("Kilo-${output.versionName.get()}.apk")
        }
    }
}


    dependencies {
        implementation(platform(libs.androidx.compose.bom))
        implementation(libs.androidx.activity.compose)
        implementation(libs.androidx.compose.material3)
        implementation(libs.androidx.compose.ui)
        implementation(libs.androidx.compose.ui.graphics)
        implementation(libs.androidx.compose.ui.tooling.preview)
        implementation(libs.androidx.core.ktx)
        implementation("androidx.appcompat:appcompat:1.7.0")
        implementation(project(":core-streaming"))
        implementation(project(":core-profile"))
        implementation(project(":core-search"))
        implementation(project(":core-ui"))
        implementation(project(":core-androidauto"))
        implementation(project(":core-companion"))
        implementation(project(":core-journeys"))
        implementation(project(":core-sounddna"))
        implementation(project(":core-discoveryradar"))
        implementation(project(":core-timemachine"))
        implementation(project(":core-updates"))
        implementation(libs.androidx.lifecycle.runtime.ktx)
        implementation(libs.androidx.room.runtime)
        ksp(libs.androidx.room.compiler)
        testImplementation(libs.junit)
        androidTestImplementation(platform(libs.androidx.compose.bom))
        androidTestImplementation(libs.androidx.compose.ui.test.junit4)
        androidTestImplementation(libs.androidx.espresso.core)
        androidTestImplementation(libs.androidx.junit)
        debugImplementation(libs.androidx.compose.ui.test.manifest)
        debugImplementation(libs.androidx.compose.ui.tooling)

        // Additional libraries needed for VYBE / ExoPlayer / Coil / Icons
        implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
        implementation("androidx.compose.material:material-icons-extended")
        implementation("androidx.media3:media3-exoplayer:1.4.1")
        implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
        implementation("androidx.media3:media3-exoplayer-dash:1.4.1")
        implementation("androidx.media3:media3-common:1.4.1")
        implementation("androidx.media3:media3-datasource:1.4.1")
        implementation("androidx.media3:media3-session:1.4.1")
        implementation("androidx.media3:media3-ui:1.4.1")
        implementation("io.coil-kt:coil-compose:2.7.0")
        implementation("androidx.browser:browser:1.8.0")
        implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    }
