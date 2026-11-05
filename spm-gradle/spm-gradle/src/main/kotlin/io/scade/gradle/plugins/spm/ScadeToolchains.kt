package io.scade.gradle.plugins.spm

import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import org.gradle.api.logging.Logger
import org.gradle.internal.os.OperatingSystem
import java.io.File
import java.io.FileOutputStream
import java.net.URI

private val isMac: Boolean
    get() = OperatingSystem.current().isMacOsX

/** Where scd installs everything it manages on macOS. */
val scadeToolchainsDir: File
    get() = File(System.getProperty("user.home"), "Library/Developer/Scade/Toolchains")

/** Where `scd toolchain install --version <version>` puts the Swift for Android toolchain. */
fun swiftAndroidToolchainDir(version: String): File =
    File(scadeToolchainsDir, "swift-android-compiler/$version")

/**
 * SCADE's Swift SDK (scade-swift-android-sdk) at [version]. On macOS, where
 * `scd toolchain install --package scade-swift-android-sdk --version <version>` puts it;
 * elsewhere the bundle `swift sdk install` put in ~/.swiftpm/swift-sdks.
 */
fun scadeSwiftSdkDir(version: String): File =
    if (isMac) File(scadeToolchainsDir, "scade-swift-android-sdk/$version")
    else File(System.getProperty("user.home"), ".swiftpm/swift-sdks/scade-swift-android-sdk-$version.artifactbundle")

/** The Swift release that built the SCADE Swift SDK at [sdk], from its info.json; null when not installed. */
fun scadeSwiftSdkSwiftVersion(sdk: File): String? {
    val info = File(sdk, "info.json").takeIf { it.isFile } ?: return null
    @Suppress("UNCHECKED_CAST")
    val artifacts = (JsonSlurper().parse(info) as? Map<String, Any?>)?.get("artifacts") as? Map<String, Map<String, Any?>>
    return artifacts?.values?.firstNotNullOfOrNull { it["version"] as? String }
}

/** Where [installSwiftReleaseToolchain] puts the swift.org toolchain on Linux. */
fun linuxSwiftToolchainsDir(): File {
    val data = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotEmpty() }?.let(::File)
        ?: File(System.getProperty("user.home"), ".local/share")
    return File(data, "swift-toolchains")
}

/**
 * swift.org's name for the Linux build to use: [override] (-Pswiftpm.linuxSwiftPlatform),
 * else Ubuntu 24.04's. Its binaries need nothing beyond what most distributions ship;
 * UBI 9's link libncurses.so.6, which Arch, for one, lacks.
 */
fun linuxSwiftPlatform(override: String? = null): String =
    override ?: if (System.getProperty("os.arch") == "aarch64") "ubuntu24.04-aarch64" else "ubuntu24.04"

/**
 * The swift.org toolchain for [swiftVersion] (e.g. 6.3.1); null when not installed. On
 * macOS the .xctoolchain, per user or system-wide; on Linux the [linuxPlatform] release
 * tarball unpacked into [linuxSwiftToolchainsDir] as `swift-<version>-RELEASE-<platform>`.
 */
fun swiftReleaseToolchainDir(swiftVersion: String, linuxPlatform: String? = null): File? {
    val candidates = if (isMac) {
        val name = "Library/Developer/Toolchains/swift-$swiftVersion-RELEASE.xctoolchain"
        listOf(File(System.getProperty("user.home"), name), File("/", name))
    } else {
        listOf(File(linuxSwiftToolchainsDir(), "swift-$swiftVersion-RELEASE-${linuxSwiftPlatform(linuxPlatform)}"))
    }
    return candidates.firstOrNull { File(it, "usr/bin/swift-build").isFile }
}

/**
 * Installs swift.org's Linux release of Swift [swiftVersion] for [platform] (see
 * [linuxSwiftPlatform]) into [linuxSwiftToolchainsDir] unless it is there, and returns it.
 * Unpacks beside the destination and renames, so an interrupted install leaves nothing
 * that [swiftReleaseToolchainDir] would accept.
 */
fun installSwiftReleaseToolchain(swiftVersion: String, platform: String?, tmpDir: File, logger: Logger): File {
    swiftReleaseToolchainDir(swiftVersion, platform)?.let { return it }
    if (isMac) throw GradleException("installSwiftReleaseToolchain is for Linux; resolveScdTool installs it on macOS")

    val plat = linuxSwiftPlatform(platform)
    val name = "swift-$swiftVersion-RELEASE"
    val fileName = "$name-$plat.tar.gz"
    // The download dir drops the dots: ubuntu24.04 -> ubuntu2404.
    val dirPlat = plat.replace(".", "")
    val url = URI("https://download.swift.org/swift-$swiftVersion-release/$dirPlat/$name/$fileName").toURL()

    val root = linuxSwiftToolchainsDir().apply { mkdirs() }
    val dest = File(root, "$name-$plat")
    val staging = File(root, "$name-$plat.partial")
    val archive = File(tmpDir.apply { mkdirs() }, fileName)
    staging.deleteRecursively()

    logger.lifecycle("Installing Swift $swiftVersion, the release SCADE's Swift SDK was built with, from $url ...")
    try {
        url.openStream().use { input -> FileOutputStream(archive).use { input.copyTo(it) } }
        staging.mkdirs()
        val tar = ProcessBuilder("tar", "-xzf", archive.absolutePath, "-C", staging.absolutePath, "--strip-components=1")
            .inheritIO().start()
        if (tar.waitFor() != 0) throw GradleException("Unpacking $archive failed (tar exit ${tar.exitValue()})")
        if (!File(staging, "usr/bin/swift-build").isFile) {
            throw GradleException("$url has no usr/bin/swift-build")
        }
        aliasWideOnlyLibraries(staging, logger)
        dest.deleteRecursively()
        if (!staging.renameTo(dest)) throw GradleException("Could not move $staging to $dest")
    } finally {
        archive.delete()
        staging.deleteRecursively()
    }
    return swiftReleaseToolchainDir(swiftVersion, platform)
        ?: throw GradleException("Installed $url, but $dest has no usr/bin/swift-build")
}

private val hostLibDirs = listOf("/usr/lib", "/usr/lib64", "/lib", "/lib64",
    "/usr/lib/x86_64-linux-gnu", "/usr/lib/aarch64-linux-gnu").map(::File)

private fun hostLib(name: String): File? = hostLibDirs.map { File(it, name) }.firstOrNull { it.exists() }

/**
 * swift.org's Linux builds link the narrow curses sonames (libncurses.so.6, ...) that
 * Debian and RHEL ship. Arch ships the wide ones only, so the toolchain dies at load with
 * "error while loading shared libraries" (exit 127). The toolchain uses no wide-char
 * symbols, so the wide library serves it: link each missing name to its wide twin in
 * usr/lib/swift/linux, the RUNPATH every binary there searches first. Never in /usr/lib,
 * where it would outlive ncurses updates and change every other program. libxml2.so.2
 * likewise, where the host has moved on to .so.16. Then check that the tools load.
 */
private fun aliasWideOnlyLibraries(toolchain: File, logger: Logger) {
    val runpath = File(toolchain, "usr/lib/swift/linux")
    val aliases = listOf("ncurses", "form", "panel", "menu", "tinfo").map { "lib$it.so.6" to "lib${it}w.so.6" } +
        listOf("libxml2.so.2" to "libxml2.so.16")
    for ((narrow, wide) in aliases) {
        if (hostLib(narrow) != null) continue
        val twin = hostLib(wide) ?: continue
        val link = File(runpath, narrow)
        if (link.exists()) continue
        java.nio.file.Files.createSymbolicLink(link.toPath(), twin.toPath())
        logger.lifecycle("  $narrow -> $twin (in ${runpath.name}; the host has no $narrow)")
    }
    val missing = listOf("swift-package", "swift-frontend", "swift-driver").flatMap { tool ->
        val p = ProcessBuilder("ldd", File(toolchain, "usr/bin/$tool").absolutePath).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out.lines().filter { "not found" in it }.map { "$tool: ${it.trim()}" }
    }
    if (missing.isNotEmpty()) {
        throw GradleException("The Swift toolchain would not load on this host:\n  " + missing.joinToString("\n  "))
    }
}
