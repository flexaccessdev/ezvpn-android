// Pure-Kotlin/JVM library: everything about a tunnel that needs no Android API
// (CIDR math, route subtraction, split-tunnel conflict check, profile model and
// editor validation, FFI JSON shapes, snapshot decoding). Unit-tested on the
// host JVM with `./gradlew :tunnelcore:test`, no device or emulator needed.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // org.json ships in the Android framework; provide it only for compiling
    // this module and for its host tests, never as a transitive runtime dep.
    compileOnly(libs.org.json)
    testImplementation(libs.org.json)
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
