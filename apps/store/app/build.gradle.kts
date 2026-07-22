import java.io.FileInputStream
import java.util.Properties

val keystorePropertiesFile = rootProject.file("keystore.properties")
val useKeystoreProperties = keystorePropertiesFile.canRead()
val keystoreProperties = Properties()
if (useKeystoreProperties) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}
val environmentKeystoreFile = providers.environmentVariable("ESSENTIALS_STORE_KEYSTORE_FILE")
    .orNull
    ?.let(::file)
val useEnvironmentKeystore = environmentKeystoreFile?.canRead() == true
val useReleaseSigning = useKeystoreProperties || useEnvironmentKeystore

fun signingValue(property: String, environmentVariable: String): String =
    if (useEnvironmentKeystore) {
        providers.environmentVariable(environmentVariable).get()
    } else {
        keystoreProperties[property] as String
    }

fun configuredValue(gradleProperty: String, environmentVariable: String, fallback: String): String =
    providers.gradleProperty(gradleProperty)
        .orElse(providers.environmentVariable(environmentVariable))
        .getOrElse(fallback)

val repoBaseUrl = configuredValue(
    "essentialsRepoBaseUrl",
    "ESSENTIALS_REPO_BASE_URL",
    "https://essentials-store.invalid",
).removeSuffix("/")
val repoPublicKey = configuredValue(
    "essentialsRepoPublicKey",
    "ESSENTIALS_REPO_PUBLIC_KEY",
    "RWQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
)
val repoKeyVersion = configuredValue(
    "essentialsRepoKeyVersion",
    "ESSENTIALS_REPO_KEY_VERSION",
    "0",
)
val storeVersionCode = configuredValue(
    "essentialsStoreVersionCode",
    "ESSENTIALS_STORE_VERSION_CODE",
    "1",
).toInt()
val storeVersionName = configuredValue(
    "essentialsStoreVersionName",
    "ESSENTIALS_STORE_VERSION_NAME",
    "0.1.0",
)
val releaseRequested = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }

if (releaseRequested) {
    require(useReleaseSigning) {
        "Release signing requires apps/store/keystore.properties or ESSENTIALS_STORE_KEYSTORE_FILE"
    }
    require(repoBaseUrl != "https://essentials-store.invalid") {
        "Release builds require essentialsRepoBaseUrl or ESSENTIALS_REPO_BASE_URL"
    }
    require(repoPublicKey != "RWQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA") {
        "Release builds require essentialsRepoPublicKey or ESSENTIALS_REPO_PUBLIC_KEY"
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.androidx.navigation.safeargs)
    id("kotlin-parcelize")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

android {
    if (useReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = environmentKeystoreFile
                    ?: rootProject.file(keystoreProperties["storeFile"]!!)
                storePassword = signingValue(
                    "storePassword",
                    "ESSENTIALS_STORE_KEYSTORE_PASSWORD",
                )
                keyAlias = signingValue("keyAlias", "ESSENTIALS_STORE_KEY_ALIAS")
                keyPassword = signingValue(
                    "keyPassword",
                    "ESSENTIALS_STORE_KEY_PASSWORD",
                )
                enableV4Signing = true
            }
        }
    }

    compileSdk = 36
    buildToolsVersion = "36.0.0"

    namespace = "app.grapheneos.apps"

    defaultConfig {
        applicationId = "digital.dutton.essentials.store"
        minSdk = 31
        targetSdk = 36
        versionCode = storeVersionCode
        versionName = storeVersionName

        buildConfigField(String::class.java.name, "REPO_BASE_URL",
            "\"$repoBaseUrl\"")

        buildConfigField(String::class.java.name, "REPO_PUBLIC_KEY", "\"$repoPublicKey\"")

        buildConfigField(String::class.java.name, "REPO_KEY_VERSION",
            "\"$repoKeyVersion\"")
    }

    buildTypes {
        getByName("release") {
            isShrinkResources = true
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (useReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }

        getByName("debug") {
            applicationIdSuffix = ".debug"
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    androidResources {
        localeFilters += listOf("en")
    }

    packaging {
        resources.excludes.addAll(listOf(
            "META-INF/versions/*/OSGI-INF/MANIFEST.MF",
            "org/bouncycastle/pqc/**.properties",
            "org/bouncycastle/x509/**.properties",
        ))
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.swiperefreshlayout)

    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)

    implementation(libs.material)

    implementation(libs.bcprov.jdk18on)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlin.retry)
    implementation(libs.kotlin.retry.result)

    implementation(libs.glide.core)
    ksp(libs.glide.ksp)
}
