import java.io.FileOutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// ---------------------------------------------------------------------------
// libezvpn.so delivery.
//
// Default: download the pinned ezvpn release zip (libezvpn-android.zip, the
// jniLibs/<abi>/libezvpn.so tree built by ../ezvpn/build-android.sh) by
// URL + sha256 into build/, mirroring how ezvpn-apple pins its xcframework.
// Local FFI dev: EZVPN_LOCAL_JNILIBS=1 (exactly) points the jniLibs source set
// at ../ezvpn/dist/android/jniLibs instead. Any other value selects the release.
val ezvpnLocalJniLibs = System.getenv("EZVPN_LOCAL_JNILIBS") == "1"
val ezvpnReleaseTag = providers.gradleProperty("ezvpn.releaseTag").get()
val ezvpnReleaseSha256 = providers.gradleProperty("ezvpn.releaseSha256").getOrElse("")
val ezvpnReleaseUrl =
    "https://github.com/flexaccessdev/ezvpn/releases/download/$ezvpnReleaseTag/libezvpn-android.zip"
val ezvpnLocalDir = rootProject.file("../ezvpn/dist/android/jniLibs")
val ezvpnFetchedDir = layout.buildDirectory.dir("ezvpn-jnilibs")
val ezvpnJniLibsDir: File =
    if (ezvpnLocalJniLibs) ezvpnLocalDir else ezvpnFetchedDir.get().asFile.resolve("jniLibs")

val fetchEzvpnJniLibs by tasks.registering {
    description = "Downloads the pinned libezvpn-android.zip release and verifies its sha256."
    val url = ezvpnReleaseUrl
    val sha256 = ezvpnReleaseSha256
    val outDir = ezvpnFetchedDir
    val skip = ezvpnLocalJniLibs
    inputs.property("url", url)
    inputs.property("sha256", sha256)
    outputs.dir(outDir)
    onlyIf { !skip }
    doLast {
        require(sha256.matches(Regex("[0-9a-f]{64}"))) {
            "ezvpn.releaseSha256 is not set in gradle.properties: run " +
                "scripts/bump-jnilibs.sh <tag> to pin a published ezvpn release, " +
                "or build ../ezvpn with ./build-android.sh and set EZVPN_LOCAL_JNILIBS=1."
        }
        val dir = outDir.get().asFile
        val stamp = dir.resolve("sha256.txt")
        if (stamp.isFile && stamp.readText().trim() == sha256 &&
            dir.resolve("jniLibs").isDirectory
        ) {
            return@doLast
        }
        dir.deleteRecursively()
        dir.mkdirs()
        logger.lifecycle("Downloading $url")
        val bytes = URI(url).toURL().openConnection().run {
            connectTimeout = 30_000
            readTimeout = 120_000
            getInputStream().use { it.readBytes() }
        }
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        require(actual == sha256) {
            "sha256 mismatch for $url: expected $sha256, got $actual"
        }
        ZipInputStream(bytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                val target = dir.resolve(entry.name).canonicalFile
                require(target.path.startsWith(dir.canonicalPath)) { "zip entry escapes dir: ${entry.name}" }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile.mkdirs()
                    FileOutputStream(target).use { zip.copyTo(it) }
                }
            }
        }
        require(dir.resolve("jniLibs").isDirectory) { "zip did not contain a jniLibs/ tree" }
        stamp.writeText(sha256)
    }
}

android {
    namespace = "dev.flexaccess.ezvpn"
    compileSdk = 37

    defaultConfig {
        // The JNI symbols in libezvpn.so (ezvpn/src/ffi_android.rs) are bound to
        // the class dev.flexaccess.ezvpn.EzvpnNative; the applicationId can
        // change, the package of that class cannot.
        applicationId = "dev.flexaccess.ezvpn"
        minSdk = 29
        targetSdk = 37
        versionCode = providers.gradleProperty("ezvpn.versionCode").get().toInt()
        versionName = providers.gradleProperty("ezvpn.versionName").get()

        // 64-bit only (Google Play's 64-bit requirement; 32-bit devices are
        // not supported): arm64-v8a for phones plus x86_64 for VMs/emulators.
        // Only these ABIs' libezvpn.so from the core zip are packaged, so the
        // APK refuses to install anywhere else.
        ndk { abiFilters.addAll(listOf("arm64-v8a", "x86_64")) }
    }

    // Release signing comes from the environment (scripts/build-release-apk.sh
    // sets it up): EZVPN_KEYSTORE (path), EZVPN_KEYSTORE_PASSWORD, optional
    // EZVPN_KEY_ALIAS (default "ezvpn") and EZVPN_KEY_PASSWORD (defaults to the
    // keystore password). Without EZVPN_KEYSTORE, assembleRelease produces an
    // unsigned APK (app-release-unsigned.apk) that no device will install.
    val releaseKeystore = System.getenv("EZVPN_KEYSTORE")?.takeIf { it.isNotBlank() }
    if (releaseKeystore != null) {
        signingConfigs.create("release") {
            storeFile = file(releaseKeystore)
            storePassword = System.getenv("EZVPN_KEYSTORE_PASSWORD")?.takeIf { it.isNotEmpty() }
                ?: error("EZVPN_KEYSTORE is set but EZVPN_KEYSTORE_PASSWORD is not")
            keyAlias = System.getenv("EZVPN_KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "ezvpn"
            keyPassword = System.getenv("EZVPN_KEY_PASSWORD")?.takeIf { it.isNotEmpty() } ?: storePassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    sourceSets["main"].jniLibs.srcDir(ezvpnJniLibsDir)

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // The .so files are built against the NDK with 16 KiB page alignment;
        // keep them uncompressed and aligned as the system expects.
        jniLibs.useLegacyPackaging = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

tasks.named("preBuild") {
    dependsOn(fetchEzvpnJniLibs)
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

dependencies {
    implementation(project(":tunnelcore"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.kotlinx.coroutines.test)
}
