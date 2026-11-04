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
        versionCode = 2
        versionName = "0.2.0"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        debug {
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
