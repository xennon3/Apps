import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing (the Play upload key). The key and its passwords never go in git: point
// DASHPLAY_SIGNING at a properties file, or put keystore.properties in the project root.
// Expected keys: storeFile, storePassword, keyAlias, keyPassword.
val signing = (System.getenv("DASHPLAY_SIGNING") ?: rootProject.file("keystore.properties").path)
    .let(::File).takeIf { it.exists() }
    ?.let { file -> Properties().apply { file.inputStream().use { load(it) } } }

android {
    namespace = "com.xennon3.dashplay"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.xennon3.dashplay"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "0.4.0"
    }

    signingConfigs {
        if (signing != null) {
            create("upload") {
                storeFile = file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("upload")
        }
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
    // Serves bundled pages and downloaded store items from one https origin.
    implementation("androidx.webkit:webkit:1.17.1")
    // CarHardwareManager on Android Auto (used for the speed lock).
    implementation("androidx.car.app:app-projected:1.7.0")
}
