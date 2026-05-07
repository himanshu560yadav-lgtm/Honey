import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.10"
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile))
}

val versionProps = Properties()
val versionPropsFile = rootProject.file("version.properties")
if (versionPropsFile.exists()) {
    versionProps.load(FileInputStream(versionPropsFile))
}

android {
    namespace = "com.blurr.voice"
    compileSdk = 35

    val apiKeys = localProperties.getProperty("GEMINI_API_KEYS") ?: ""
    val googlecloudProxyURL = localProperties.getProperty("GCLOUD_PROXY_URL") ?: ""
    val googlecloudProxyURLKey = localProperties.getProperty("GCLOUD_PROXY_URL_KEY") ?: ""

    val debugSha1 = "D0:A1:49:03:FD:B5:37:DF:B5:36:51:B1:66:AE:70:11:E2:59:08:33"

    defaultConfig {
        applicationId = "com.blurr.voice"
        minSdk = 24
        targetSdk = 35
        versionCode = versionProps.getProperty("VERSION_CODE", "13").toInt()
        versionName = versionProps.getProperty("VERSION_NAME", "1.0.13")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "GEMINI_API_KEYS", "\"$apiKeys\"")
        buildConfigField("boolean", "ENABLE_DIRECT_APP_OPENING", "true")
        buildConfigField("boolean", "SPEAK_INSTRUCTIONS", "true")
        buildConfigField("String", "GCLOUD_PROXY_URL", "\"$googlecloudProxyURL\"")
        buildConfigField("String", "GCLOUD_PROXY_URL_KEY", "\"$googlecloudProxyURLKey\"")
        buildConfigField("boolean", "ENABLE_LOGGING", "true")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            buildConfigField("String", "SHA1_FINGERPRINT", "\"$debugSha1\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.appcompat)
    implementation(libs.generativeai)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    implementation("com.google.android.material:material:1.11.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:5.0.0-alpha.16")
    implementation("com.squareup.moshi:moshi:1.15.0")
    implementation("com.google.code.gson:gson:2.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")

    implementation("androidx.recyclerview:recyclerview:1.3.2")
}

tasks.register("incrementVersion") {
    doLast {
        val versionFile = rootProject.file("version.properties")
        val props = Properties()
        props.load(FileInputStream(versionFile))

        val currentVersionCode = props.getProperty("VERSION_CODE").toInt()
        val currentVersionName = props.getProperty("VERSION_NAME")

        val newVersionCode = currentVersionCode + 1

        val versionParts = currentVersionName.split(".")
        val newPatchVersion = if (versionParts.size >= 3) {
            versionParts[2].toInt() + 1
        } else {
            1
        }
        val newVersionName = if (versionParts.size >= 2) {
            "${versionParts[0]}.${versionParts[1]}.$newPatchVersion"
        } else {
            "1.0.$newPatchVersion"
        }

        props.setProperty("VERSION_CODE", newVersionCode.toString())
        props.setProperty("VERSION_NAME", newVersionName)

        val output = FileOutputStream(versionFile)
        output.use { fileOutput ->
            fileOutput.write("# Version configuration for Honey Android App\n".toByteArray())
            fileOutput.write("# This file is automatically updated during release builds\n".toByteArray())
            fileOutput.write("# Do not modify manually - use Gradle tasks to update versions\n\n".toByteArray())
            fileOutput.write("# Current version code (integer - increments by 1 each release)\n".toByteArray())
            fileOutput.write("VERSION_CODE=$newVersionCode\n\n".toByteArray())
            fileOutput.write("# Current version name (semantic version - increments patch number each release)\n".toByteArray())
            fileOutput.write("VERSION_NAME=$newVersionName".toByteArray())
        }

        println("Version incremented to: versionCode=$newVersionCode, versionName=$newVersionName")
    }
}

tasks.whenTaskAdded {
    if (name == "assembleRelease" || name == "bundleRelease") {
        dependsOn("incrementVersion")
    }
}