plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val persistentDebugKeystorePath = providers.environmentVariable("ANDROID_DEBUG_KEYSTORE_PATH").orNull
val persistentDebugKeystorePassword = providers.environmentVariable("ANDROID_DEBUG_KEYSTORE_PASSWORD").orNull
val persistentDebugKeyAlias = providers.environmentVariable("ANDROID_DEBUG_KEY_ALIAS").orNull

android {
    namespace = "com.teamshryne.kino"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.teamshryne.kino"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures { compose = true }

    if (persistentDebugKeystorePath != null && persistentDebugKeystorePassword != null && persistentDebugKeyAlias != null) {
        signingConfigs {
            create("persistentDebug") {
                storeFile = file(persistentDebugKeystorePath)
                storePassword = persistentDebugKeystorePassword
                keyAlias = persistentDebugKeyAlias
                keyPassword = persistentDebugKeystorePassword
            }
        }
        buildTypes {
            getByName("debug") {
                signingConfig = signingConfigs.getByName("persistentDebug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // CI downloads a pinned gomobile artifact; keeping it out of source control
    // keeps the Android repository small and makes core upgrades explicit.
    providers.gradleProperty("kinoCoreAar").orNull?.let { aarPath ->
        implementation(files(aarPath))
    }

    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    implementation("io.noties.markwon:ext-tasklist:4.6.2")
    implementation("io.noties.markwon:html:4.6.2")
    implementation("io.noties.markwon:image:4.6.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
