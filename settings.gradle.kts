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

rootProject.name = "ezvpn-android"

// The Android app, and the pure-Kotlin tunnel-core library (IP/CIDR math,
// profile model and validation, snapshot decoding) it shares with its JVM
// unit tests — the counterpart of ezvpn-apple's Packages/TunnelCore.
include(":app", ":tunnelcore")
