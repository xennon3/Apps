plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.xennon3.dashplay"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.xennon3.dashplay"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.activity:activity:1.12.4")
    // CarHardwareManager on Android Auto (used for the speed lock).
    implementation("androidx.car.app:app-projected:1.7.0")
}
