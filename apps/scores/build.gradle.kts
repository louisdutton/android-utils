plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val appId = "digital.dutton.essentials.scores"
val omrAssetsDir = providers.environmentVariable("SCORES_OMR_ASSETS_DIR")
val verovioSrcDir = providers.environmentVariable("VEROVIO_SRC_DIR")
val verovioAssetsDir = layout.buildDirectory.dir("generated/verovio-assets")
val prepareVerovioAssets by tasks.registering {
    val source = verovioSrcDir.orNull
    if (!source.isNullOrBlank()) {
        inputs.dir(file("$source/data"))
    }
    outputs.dir(verovioAssetsDir)
    doLast {
        val output = verovioAssetsDir.get().asFile
        delete(output)
        output.mkdirs()
        if (!source.isNullOrBlank()) {
            file("$source/data").copyRecursively(File(output, "verovio"), overwrite = true)
            output.walkTopDown().forEach { file ->
                file.setReadable(true, true)
                file.setWritable(true, true)
                if (file.isDirectory) {
                    file.setExecutable(true, true)
                }
            }
        }
    }
}

android {
    namespace = appId
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = appId
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures { compose = true }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets {
        getByName("main") {
            omrAssetsDir.orNull?.takeIf { it.isNotBlank() }?.let { assets.directories.add(it) }
            assets.directories.add(verovioAssetsDir.get().asFile.absolutePath)
        }
    }

    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation(platform("androidx.compose:compose-bom:2026.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("com.caverock:androidsvg:1.4")
    implementation("com.google.ai.edge.litert:litert:2.1.4")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.opencv:opencv:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}

tasks.named("preBuild") {
    dependsOn(prepareVerovioAssets)
}
