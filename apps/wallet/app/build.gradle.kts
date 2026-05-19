import com.android.build.gradle.internal.tasks.factory.dependsOn
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.com.android.application)
    alias(libs.plugins.org.jetbrains.kotlin.plugin.compose)
}

android {
    namespace = "protect.card_locker"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "digital.dutton.essentials.wallet"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        vectorDrawables.useSupportLibrary = true
        multiDexEnabled = true

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("boolean", "showDonate", "false")
        buildConfigField("boolean", "useAcraCrashReporter", "false")
    }

    signingConfigs {
        getByName("debug")
        create("release") {
            initWith(getByName("debug"))
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
        viewBinding = true
    }

    bundle {
        language {
            enableSplit = false
        }
    }

    sourceSets {
        getByName("test") {
            resources.setSrcDirs(listOf("src/test/res"))
        }
    }

    androidResources {
        localeFilters += listOf("ar", "be", "bg", "bn", "bn-rIN", "bs", "cs", "da", "de", "el-rGR", "en", "eo", "es", "es-rAR", "et", "fa", "fi", "fr", "gl", "he-rIL", "hi", "hr", "hu", "in-rID", "is", "it", "ja", "ko", "lt", "lv", "nb-rNO", "nl", "oc", "peo", "pl", "pt", "pt-rBR", "pt-rPT", "ro-rRO", "ru", "sk", "sl", "sr", "sv", "ta", "tr", "uk", "vi", "zh-rCN", "zh-rTW")
    }

    // Starting with Android Studio 3 Robolectric is unable to find resources.
    // The following allows it to find the resources.
    testOptions.unitTests.isIncludeAndroidResources = true
    tasks.withType<Test>().configureEach {
        testLogging {
            events("started", "passed", "skipped", "failed")
        }
    }

    lint {
        lintConfig = file("lint.xml")
    }

    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_21
        }
    }
    compileOptions {
        encoding = "UTF-8"

        // Flag to enable support for the new language APIs
        isCoreLibraryDesugaringEnabled = true

        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

composeCompiler {
    includeComposeMappingFile = false
}

dependencies {
    // AndroidX
    implementation(libs.androidx.appcompat.appcompat)
    implementation(libs.androidx.constraintlayout.constraintlayout)
    implementation(libs.androidx.core.core.ktx)
    implementation(libs.androidx.core.core.remoteviews)
    implementation(libs.androidx.exifinterface.exifinterface)
    implementation(libs.androidx.palette.palette)
    implementation(libs.androidx.preference.preference)
    implementation(libs.androidx.viewpager2.viewpager2)
    implementation(libs.com.google.android.material.material)
    coreLibraryDesugaring(libs.com.android.tools.desugar.jdk.libs)

    // Compose
    implementation(libs.androidx.activity.activity.compose)
    val composeBom = platform(libs.androidx.compose.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.foundation.foundation)
    implementation(libs.androidx.compose.material3.material3)
    implementation(libs.androidx.compose.material.material.icons.extended)
    implementation(libs.androidx.compose.ui.ui.tooling.preview.android)
    debugImplementation(libs.androidx.compose.ui.ui.test.manifest)

    androidTestImplementation(composeBom)
    androidTestImplementation(libs.androidx.compose.ui.ui.test.junit4)

    // Third-party
    implementation(libs.com.journeyapps.zxing.android.embedded)
    implementation(libs.com.github.yalantis.ucrop)
    implementation(libs.com.google.zxing.core)
    implementation(libs.org.apache.commons.commons.csv)
    implementation(libs.com.jaredrummler.colorpicker)
    implementation(libs.net.lingala.zip4j.zip4j)

    // Testing
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.junit.junit)
    testImplementation(libs.org.robolectric.robolectric)

    androidTestImplementation(libs.bundles.androidx.test)
    androidTestImplementation(libs.junit.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.uiautomator.uiautomator)
    androidTestImplementation(libs.androidx.test.espresso.espresso.core)
}

tasks.register("copyRawResFiles", Copy::class) {
    from(
        layout.projectDirectory.file("../CHANGELOG.md"),
        layout.projectDirectory.file("../PRIVACY.md")
    )
    into(layout.projectDirectory.dir("src/main/res/raw"))
    rename { it.lowercase() }
}.also {
    tasks.preBuild.dependsOn(it)
    tasks.getByName<Delete>("clean") {
        val filesNamesToDelete = listOf("CHANGELOG", "PRIVACY")
        filesNamesToDelete.forEach { fileName ->
            delete(layout.projectDirectory.file("src/main/res/raw/${fileName.lowercase()}.md"))
        }
    }
}
