import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    id("com.google.gms.google-services") version "4.5.0"
}

val localProperties = Properties().apply {
    val propertiesFile = rootProject.file("local.properties")
    if (propertiesFile.isFile) propertiesFile.inputStream().use { load(it) }
}
val openAiApiKey = localProperties.getProperty("OPENAI_API_KEY", "").trim()
require(openAiApiKey.all { it in '!'..'~' }) {
    "OPENAI_API_KEY in local.properties must contain only printable ASCII characters without spaces."
}
val openAiApiKeyLiteral = "\"${openAiApiKey.replace("\\", "\\\\").replace("\"", "\\\"")}\""
val releaseSigningFile = rootProject.file("keystore.properties")
val releaseSigningProperties = Properties().apply {
    if (releaseSigningFile.isFile) releaseSigningFile.inputStream().use { load(it) }
}
val hasReleaseSigning = releaseSigningFile.isFile
require(!hasReleaseSigning || listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all {
    !releaseSigningProperties.getProperty(it).isNullOrBlank()
}) {
    "keystore.properties must define storeFile, storePassword, keyAlias and keyPassword."
}
val pleosDrivingPreview = providers.gradleProperty("pleosDrivingPreview").orNull == "true"

android {
    namespace = "com.kotlinsun.hitchtalk"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.kotlinsun.hitchtalk"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["hitchTalkDrivingMode"] = "false"
        buildConfigField("boolean", "PLEOS_DRIVING_PREVIEW", "false")
        buildConfigField("String", "OPENAI_API_KEY", openAiApiKeyLiteral)
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseSigningProperties.getProperty("storeFile"))
                storePassword = releaseSigningProperties.getProperty("storePassword")
                keyAlias = releaseSigningProperties.getProperty("keyAlias")
                keyPassword = releaseSigningProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            manifestPlaceholders["hitchTalkDrivingMode"] = pleosDrivingPreview.toString()
            buildConfigField("boolean", "PLEOS_DRIVING_PREVIEW", pleosDrivingPreview.toString())
        }
        release {
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation(platform("com.google.firebase:firebase-bom:34.6.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
