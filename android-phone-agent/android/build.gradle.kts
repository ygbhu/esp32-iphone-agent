// Root build file. Plugin versions are pinned inside each module so that the pure-Kotlin
// ':core' module can be built and tested on a machine without an Android SDK.
plugins {
    base
}

tasks.register("printToolchain") {
    doLast {
        println("java     = " + System.getProperty("java.version"))
        println("javaHome = " + System.getProperty("java.home"))
        println("gradle   = " + gradle.gradleVersion)
    }
}
