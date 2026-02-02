plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val bundleId = "digital.dutton.agent"

// Copy whisper model from nix store to assets before build
tasks.register<Copy>("copyModels") {
    val whisperModel = System.getenv("WHISPER_MODEL")

    if (whisperModel != null) {
        from(whisperModel) {
            rename { "ggml-tiny.en-q5_1.bin" }
        }
    }
    into("src/main/assets")
}

tasks.named("preBuild") {
    dependsOn("copyModels")
}

android {
    namespace = bundleId
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = bundleId
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // Use prebuilt JNI library from nix
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs(System.getenv("AGENT_JNI_DIR") ?: "src/main/jniLibs")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions { jvmTarget = "21" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
