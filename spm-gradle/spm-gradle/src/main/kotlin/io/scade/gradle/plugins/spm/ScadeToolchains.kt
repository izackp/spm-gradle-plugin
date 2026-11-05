package io.scade.gradle.plugins.spm

import groovy.json.JsonSlurper
import java.io.File

/** Where scd installs everything it manages on macOS. */
val scadeToolchainsDir: File
    get() = File(System.getProperty("user.home"), "Library/Developer/Scade/Toolchains")

/** Where `scd toolchain install --version <version>` puts the Swift for Android toolchain. */
fun swiftAndroidToolchainDir(version: String): File =
    File(scadeToolchainsDir, "swift-android-compiler/$version")

/** Where `scd toolchain install --package scade-swift-android-sdk --version <version>` puts SCADE's Swift SDK. */
fun scadeSwiftSdkDir(version: String): File =
    File(scadeToolchainsDir, "scade-swift-android-sdk/$version")

/** The Swift release that built the SCADE Swift SDK at [sdk], from its info.json; null when not installed. */
fun scadeSwiftSdkSwiftVersion(sdk: File): String? {
    val info = File(sdk, "info.json").takeIf { it.isFile } ?: return null
    @Suppress("UNCHECKED_CAST")
    val artifacts = (JsonSlurper().parse(info) as? Map<String, Any?>)?.get("artifacts") as? Map<String, Map<String, Any?>>
    return artifacts?.values?.firstNotNullOfOrNull { it["version"] as? String }
}

/** The swift.org toolchain for [swiftVersion] (e.g. 6.3.1), per user or system-wide; null when not installed. */
fun swiftReleaseToolchainDir(swiftVersion: String): File? {
    val name = "Library/Developer/Toolchains/swift-$swiftVersion-RELEASE.xctoolchain"
    return listOf(File(System.getProperty("user.home"), name), File("/", name))
        .firstOrNull { File(it, "usr/bin/swift-build").isFile }
}
