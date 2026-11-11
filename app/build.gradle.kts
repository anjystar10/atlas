plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
}

android {
    namespace = "com.anjy.atlas"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.anjy.atlas"
        minSdk = 33
        targetSdk = 35
        versionCode = 16
        versionName = "0.9.2"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // One fixed key for every build, so each new APK installs over the last one instead of being refused.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("signing/atlas.jks")
            storePassword = "atlas-android"
            keyAlias = "atlas"
            keyPassword = "atlas-android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        jniLibs { useLegacyPackaging = true }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":lib"))
    implementation(libs.kotlinx.coroutines.android)
    // "Hey Atlas": Vosk, offline speech recognition (Apache 2.0). Its model is added to the assets by CI.
    implementation("com.alphacephei:vosk-android:0.3.75")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    testImplementation(libs.junit)
    // the real org.json for JVM unit tests (Android's copy is only a stub off the phone)
    testImplementation("org.json:json:20240303")
}
