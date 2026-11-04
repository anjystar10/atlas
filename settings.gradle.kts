pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Atlas"
include(":app")
// On-device model runtime: llama.cpp's Android library, checked out at a pinned commit by CI (see .github/workflows/build.yml).
include(":lib")
project(":lib").projectDir = file("llama.cpp/examples/llama.android/lib")
