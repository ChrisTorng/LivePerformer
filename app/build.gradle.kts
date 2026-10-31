import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "tw.idv.christorng.liveperformer"
    compileSdk = 37
    defaultConfig {
        applicationId = "tw.idv.christorng.liveperformer"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.1.2"
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-O3", "-fexceptions")
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }
    signingConfigs {
        getByName("debug") {
            val persistentDevKey = rootProject.file("app/dev-signing.keystore")
            if (persistentDevKey.exists()) {
                storeFile = persistentDevKey
                storePassword = "android"
                keyAlias = "liveperformer-dev"
                keyPassword = "android"
            }
        }
    }
    buildFeatures {
        compose = true
        prefab = true
    }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }
    packaging {
        jniLibs { useLegacyPackaging = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.tukaani:xz:1.10")
    implementation("com.google.oboe:oboe:1.11.0")
    // Provides libfluidsynth.so for the NDK-side dynamically loaded API.
    implementation("net.volcanomobile.fluidsynth-android:fluidsynth-android:2.4.6")
}

val gmSf2 = layout.projectDirectory.file("src/main/assets/GeneralUser-GS.sf2").asFile
val prepareGmSoundFont = tasks.register("prepareGmSoundFont") {
    outputs.file(gmSf2)
    doLast {
        val expectedSha = "9575028c7a1f589f5770fccc8cff2734566af40cd26ed836944e9a5152688cfe"
        if (!gmSf2.exists()) {
            gmSf2.parentFile.mkdirs()
            val temp = File(gmSf2.parentFile, "GeneralUser-GS.sf2.part")
            try {
                val url = URI("https://raw.githubusercontent.com/mrbumpy409/GeneralUser-GS/main/GeneralUser-GS.sf2").toURL()
                url.openConnection().apply { connectTimeout = 30_000; readTimeout = 120_000 }
                    .getInputStream().use { input -> temp.outputStream().use { input.copyTo(it) } }
                check(temp.length() > 10_000_000) { "GM SoundFont appears truncated" }
                check(temp.renameTo(gmSf2)) { "Unable to move downloaded SoundFont" }
            } finally { temp.delete() }
        }
        val sha = MessageDigest.getInstance("SHA-256")
            .digest(gmSf2.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(sha.equals(expectedSha, ignoreCase = true)) {
            "GeneralUser GS hash mismatch ($sha); remove asset and retry"
        }
    }
}
tasks.named("preBuild") { dependsOn(prepareGmSoundFont) }
