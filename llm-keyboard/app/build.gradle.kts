plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.llmkeyboard"
    compileSdk = 34
    defaultConfig {
        applicationId = "dev.llmkeyboard"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // On-device LLM runtime (no network needed once a model file is loaded).
    implementation("com.google.mediapipe:tasks-genai:0.10.35")
}
