// Set by the setup script to the project-local .tools directory; see docs/toolchain.md
pluginManagement {
    // Plugin versions are pinned in exactly one place so ':core' and ':app' can never drift.
    plugins {
        id("com.android.application") version "8.7.3"
        id("org.jetbrains.kotlin.jvm") version "2.0.21"
        id("org.jetbrains.kotlin.android") version "2.0.21"
        id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    }
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "esp32phone-android-agent"

include(":core")

// The Android application module needs a complete Android SDK (platform + build-tools
// with an accepted android-sdk-license). This checkpoint does not accept the license on
// the user's behalf, so :app is only configured when an SDK is actually available.
// Set sdk.dir in local.properties or ANDROID_HOME to enable it.
fun hasAndroidSdk(): Boolean {
    val localProperties = file("local.properties")
    val sdkPath = when {
        localProperties.exists() ->
            localProperties.readLines()
                .firstOrNull { it.trim().startsWith("sdk.dir=") }
                ?.substringAfter("=")?.trim()
        else -> System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    } ?: return false
    if (sdkPath.isEmpty()) return false
    val platforms = File(sdkPath, "platforms")
    return platforms.isDirectory && (platforms.listFiles()?.any { it.name.startsWith("android-") } == true)
}

if (hasAndroidSdk()) {
    include(":app")
} else {
    logger.lifecycle(
        "android-phone-agent: ANDROID SDK NOT FOUND -> ':app' module is not configured. " +
            "Only ':core' (pure Kotlin, JVM) can be built here. See docs/implementation-status.md.",
    )
}
