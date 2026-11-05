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
        versionCode = 4
        versionName = "0.2.2"
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
    testImplementation(libs.junit)
}
