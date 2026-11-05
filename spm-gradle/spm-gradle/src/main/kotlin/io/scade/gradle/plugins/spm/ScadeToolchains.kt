package io.scade.gradle.plugins.spm

import java.io.File

/** Where scd installs everything it manages on macOS. */
val scadeToolchainsDir: File
    get() = File(System.getProperty("user.home"), "Library/Developer/Scade/Toolchains")

/** Where `scd toolchain install --version <version>` puts the Swift for Android toolchain. */
fun swiftAndroidToolchainDir(version: String): File =
    File(scadeToolchainsDir, "swift-android-compiler/$version")
