plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.xennon.venue"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xennon.venue"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.2.1"
    }

    // A fixed, non-secret key checked into the repo so every build (local or CI)
    // can be installed over the previous one without uninstalling first.
    signingConfigs {
        create("sideload") {
            storeFile = file("sideload.keystore")
            storePassword = "venueeq"
            keyAlias = "venue"
            keyPassword = "venueeq"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
        }
        debug {
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
