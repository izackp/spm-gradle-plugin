package io.scade.gradle.plugins.android.spm

import io.scade.gradle.plugins.spm.TargetPlatform
import io.scade.gradle.plugins.spm.installSwiftReleaseToolchain
import io.scade.gradle.plugins.spm.scadeSwiftSdkDir
import io.scade.gradle.plugins.spm.scadeSwiftSdkSwiftVersion
import io.scade.gradle.plugins.spm.swiftAndroidToolchainDir
import io.scade.gradle.plugins.spm.swiftReleaseToolchainDir
import io.scade.gradle.plugins.spm.tasks.AssembleSwiftPackageTask
import io.scade.gradle.plugins.spm.tasks.PerPlatformInvocation

import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.IDevice
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import java.io.File
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.internal.os.OperatingSystem


private val swiftArchByAbi = mapOf(
    "arm64-v8a" to "aarch64", "x86_64" to "x86_64", "armeabi-v7a" to "armv7", "x86" to "i686")
private val ndkLibTripleByAbi = mapOf(
    "arm64-v8a" to "aarch64-linux-android", "x86_64" to "x86_64-linux-android",
    "armeabi-v7a" to "arm-linux-androideabi", "x86" to "i686-linux-android")
private val testLibs = listOf("XCTest", "Testing")


abstract class AssembleAndroidSwiftPackageTask: AssembleSwiftPackageTask() {
    @Internal
    val sdkPath: DirectoryProperty = project.objects.directoryProperty()

    @Internal
    val ndkPath: DirectoryProperty = project.objects.directoryProperty()

    @Internal
    val adbPath: RegularFileProperty = project.objects.fileProperty()

    @get:Input
    val useConnectedDeviceArch: Property<Boolean> =
        project.objects.property(Boolean::class.java).convention(true)

    /** Args shared across every per-arch scd invocation (toolchain, sdk, ndk). */
    private fun sharedAndroidArgs(): List<String> {
        val args = mutableListOf<String>()
        val androidPlatform = platforms.get().find { it is TargetPlatform.Android } as? TargetPlatform.Android
        androidPlatform?.toolchain?.let { tc ->
            args += listOf("--android-swift-toolchain", tc.absolutePath)
        }
        try {
            sdkPath.orNull?.let {
                val path = it.asFile.absolutePath
                project.logger.lifecycle("Building for Android SDK at: $path")
                args += listOf("--android-sdk", path)
            }
        } catch (_: Exception) {
            project.logger.lifecycle("An SDK path not set in AGP, trying to autodetect")
        }
        try {
            ndkPath.orNull?.let {
                val path = it.asFile.absolutePath
                project.logger.lifecycle("Building for Android NDK at: $path")
                args += listOf("--android-ndk", path)
            }
        } catch (_: Exception) {
            project.logger.lifecycle("An NDK path not set in AGP, trying to autodetect")
        }
        return args
    }

    /** Effective list of archs to build (respects connected device, falls back to defaults). */
    private fun effectiveAndroidArchs(): List<String> {
        val androidPlatform = platforms.get().find { it is TargetPlatform.Android } as? TargetPlatform.Android ?: return emptyList()
        var buildArchs = androidPlatform.archs

        if (assembleDebug.get() && useConnectedDeviceArch.get()) {
            val abi = getConnectedDeviceAbi()
            if (abi != null) {
                project.logger.lifecycle("Detected connected device ABI: $abi")
                if (abi in SpmGradleAndroidPlugin.supportedBuildArchs) {
                    buildArchs = listOf(abi)
                } else {
                    project.logger.lifecycle("Connected device ABI unsupported. Building for default Android platforms.")
                }
            } else {
                project.logger.lifecycle("⚠️ No connected devices found. Assembling for default Android platforms.")
            }
        }
        return buildArchs
    }

    override fun platformArgs(): List<String> {
        val args = mutableListOf<String>()
        args += effectiveAndroidArchs().flatMap { listOf("--platform", "android-$it") }
        args += sharedAndroidArgs()
        return args
    }

    /**
     * One invocation per Android arch. scd archive (and its underlying
     * swift-build) is single-triple per invocation, so this is the unit at
     * which parallelism is meaningful.
     */
    override fun expandedPlatformInvocations(): List<PerPlatformInvocation> {
        val shared = sharedAndroidArgs()
        return effectiveAndroidArchs().map { arch ->
            val label = "android-$arch"
            PerPlatformInvocation(label, listOf("--platform", label) + shared)
        }
    }

    /**
     * One `swift build --swift-sdk <arch>-unknown-linux-android<api>` per ABI, laid out
     * the way `scd archive` lays out its output: `lib/<abi>/lib<product>.so` plus the
     * libs it needs, and SwiftFoundation.jar beside the ABI dirs.
     *
     * scd also compiles the Java sources Swift targets carry as resources (swift4j's
     * runtime) into per-target jars. Here generateBridging copies those sources next
     * to the generated bridging code instead (see configureBridgingTask), and the app
     * compiles them, so no jars are made.
     */
    override fun runWithSwiftSdk() {
        if (linkDependencies.get().isNotEmpty()) {
            throw GradleException("swiftpm { dependencies } is not supported by swiftpm.backend=swift-sdk")
        }
        val configuration = if (assembleDebug.get()) "debug" else "release"
        // SCADE's SDK declares its triples at API 24 only, so a variant's minSdk does
        // not name one of them.
        val apiLevel = project.findProperty("swiftpm.androidApiLevel")?.toString() ?: "24"
        val libRoot = outputDirectory.get().asFile
        val toolsetDir = File(temporaryDir, "toolsets").apply { mkdirs() }
        var foundationJar: File? = null
        val abis = effectiveAndroidArchs()
        val tripleOf = { arch: String -> "$arch-unknown-linux-android$apiLevel" }
        val triples = { abis.associate { abi ->
            val arch = swiftArchByAbi[abi] ?: throw GradleException("No Swift architecture for Android ABI $abi")
            arch to tripleOf(arch)
        } }
        val ndkOrThrow = { what: File -> ndkPath.orNull?.asFile
            ?: throw GradleException("Building with the Swift for Android runtime at $what needs the Android NDK") }
        val sdks = File(temporaryDir, "swift-sdks")
        val scadeToolchain = scadeToolchain()
        val scadeSdk = scadeSdk()
        if (scadeToolchain != null && scadeSdk != null) {
            throw GradleException("swiftpm { toolchainVersion } and swiftpm { sdkVersion } are both set; set one")
        }
        val sdkRoots: List<File>?
        val swiftBuild: List<String>
        if (scadeToolchain != null) {
            project.delete(sdks)
            ScadeToolchainSdk.write(sdks, scadeToolchain, ndkOrThrow(scadeToolchain), triples())
            swiftBuild = listOf(File(scdFile.asFile.get().parentFile.parentFile, "lib/swift-build").absolutePath,
                "--toolchain", scadeToolchain.absolutePath, "--swift-sdks-path", sdks.absolutePath)
            sdkRoots = listOf(sdks)
        } else if (scadeSdk != null && OperatingSystem.current().isMacOsX) {
            val (sdk, hostToolchain) = scadeSdk
            project.delete(sdks)
            ScadeSwiftSdk.write(sdks, sdk, ndkOrThrow(sdk), triples())
            swiftBuild = listOf(File(hostToolchain, "usr/bin/swift-build").absolutePath,
                "--swift-sdks-path", sdks.absolutePath)
            sdkRoots = listOf(sdks)
        } else if (scadeSdk != null) {
            // Linux: the bundle's own Linux-host description, as installed and set up,
            // built with the pinned toolchain instead of PATH's swift.
            val (_, hostToolchain) = scadeSdk
            swiftBuild = listOf(File(hostToolchain, "usr/bin/swift-build").absolutePath)
            sdkRoots = null
        } else {
            swiftBuild = swiftCommand + "build"
            sdkRoots = null
        }
        val swiftpmVersion = swiftpmVersion(swiftBuild)
        val ndkBin = ndkBinDir()
        val release = configuration == "release"

        libRoot.listFiles { f -> f.isFile && f.name.endsWith(".jar") }?.forEach { it.delete() }

        for (abi in abis) {
            val arch = swiftArchByAbi[abi]
                ?: throw GradleException("No Swift architecture for Android ABI $abi")
            val triple = tripleOf(arch)
            val sdk = sdkRoots?.let { SwiftSdkTarget.find(triple, it) } ?: SwiftSdkTarget.find(triple)
            val opts = SwiftSdkOptions.translate(scdOptions.get(), "android-$abi").plus(
                swiftc = listOf("-Xlinker", "--build-id=sha1", "-Xlinker", "-z", "-Xlinker", "max-page-size=16384") +
                    (ndkBin?.let { listOf("-tools-directory", it.absolutePath) } ?: emptyList()),
                cc = listOf("-fPIC"),
                cxx = listOf("-fPIC"),
                buildArgs = listOf("--disable-experimental-prebuilts", "--disable-build-manifest-caching") +
                    (if (release) listOf("-debug-info-format", "none") else emptyList()),
            )
            val targetOnly = opts.targetOnlyArgs(swiftpmVersion, File(toolsetDir, "$abi.json"))
            // A release build gives every host-tool compile (swift-syntax, the macros)
            // the destination's swift-version file as an input, so ABIs sharing one
            // scratch rebuild all the host tools on every pass, each undoing the last.
            // Debug's host tools don't depend on it, and keep the shared scratch.
            val scratch = if (configuration == "release") File("$buildDirPath-$abi") else buildDirPath

            project.logger.lifecycle("[swift-sdk] building ${product.get()} for $triple ($configuration)")
            project.exec { e -> execTool(e, swiftBuild + listOf(
                "--package-path", packageDir,
                "--scratch-path", scratch,
                "--swift-sdk", triple,
                *targetOnly.toTypedArray(),
                "-c", configuration,
                "--product", product.get(),
                *opts.buildArgs.toTypedArray())) }

            val binDir = File(scratch, "$triple/$configuration")
            val built = File(binDir, "lib${product.get()}.so")
            if (!built.isFile) {
                throw GradleException("swift build reported success but $built does not exist")
            }

            val abiDir = File(libRoot, abi)
            project.delete(abiDir)
            abiDir.mkdirs()
            built.copyTo(File(abiDir, built.name), overwrite = true)
            opts.embedLibraries.forEach { it.copyTo(File(abiDir, it.name), overwrite = true) }

            val sdkLibs = sdk.androidLibDir(arch)
            if (!opts.staticStdlib) {
                // Dynamic runtime: ship every runtime lib except the test frameworks.
                sdkLibs.listFiles { f -> f.name.endsWith(".so") && testLibs.none { f.name.contains(it) } }
                    ?.forEach { it.copyTo(File(abiDir, it.name), overwrite = true) }
            } else {
                sdk.staticRuntimeLibs(arch).forEach { it.copyTo(File(abiDir, it.name), overwrite = true) }
            }
            ndkLibCxxShared(abi)?.let { it.copyTo(File(abiDir, it.name), overwrite = true) }
            if (release) {
                val strip = ndkBin?.let { File(it, "llvm-strip") }?.takeIf { it.isFile }
                    ?: throw GradleException("No llvm-strip in the NDK at ${ndkPath.orNull?.asFile}")
                abiDir.listFiles { f -> f.name.endsWith(".so") }?.forEach {
                    project.exec { e -> e.commandLine(strip.absolutePath, "--strip-all", it.absolutePath) }
                }
            }
            foundationJar = foundationJar
                ?: listOf(File(sdkLibs, "SwiftFoundation.jar"), File(sdk.swiftResources, "android/SwiftFoundation.jar"))
                    .firstOrNull { it.isFile }
                ?: throw GradleException("No SwiftFoundation.jar in $sdkLibs or ${sdk.swiftResources}/android")
        }

        foundationJar?.copyTo(File(libRoot, "SwiftFoundation.jar"), overwrite = true)
    }

    private fun swiftpmVersion(swiftBuild: List<String>): KotlinVersion {
        val stdout = java.io.ByteArrayOutputStream()
        project.exec { execTool(it, swiftBuild + "--version"); it.standardOutput = stdout }
        val out = stdout.toString()
        val m = Regex("""Swift Package Manager - Swift (\d+)\.(\d+)""").find(out)
            ?: throw GradleException("Cannot read the SwiftPM version from: $out")
        return KotlinVersion(m.groupValues[1].toInt(), m.groupValues[2].toInt())
    }

    /**
     * The pinned Swift for Android toolchain scd installed (`swiftpm { toolchainVersion }`),
     * which the build then runs on instead of an installed Swift SDK. Unset, or overridden
     * by -Pswiftpm.swiftCommand, it builds with `swift build` as before.
     */
    private fun scadeToolchain(): File? {
        if (project.findProperty("swiftpm.swiftCommand") != null) return null
        val version = toolchainVersion.orNull ?: return null
        if (!scdFile.isPresent) return null
        val dir = swiftAndroidToolchainDir(version)
        if (!ScadeToolchainSdk.isToolchain(dir)) {
            throw GradleException("swiftpm.toolchainVersion $version: no Swift for Android toolchain at $dir")
        }
        return dir
    }

    /**
     * The pinned SCADE Swift SDK (`swiftpm { sdkVersion }`) and the swift.org toolchain of
     * the Swift release that built it, which the build runs on.
     *
     * On macOS resolveScdTool installs both. On Linux the SDK is the bundle `swift sdk
     * install` put in ~/.swiftpm/swift-sdks (the app's own script installs and sets it
     * up), and the toolchain is installed here when missing, so the build never runs
     * whatever `swift` happens to be on PATH.
     */
    private fun scadeSdk(): Pair<File, File>? {
        if (project.findProperty("swiftpm.swiftCommand") != null) return null
        val version = sdkVersion.orNull ?: return null
        val mac = OperatingSystem.current().isMacOsX
        if (mac && !scdFile.isPresent) return null
        val sdk = scadeSwiftSdkDir(version)
        val swift = scadeSwiftSdkSwiftVersion(sdk)
            ?: throw GradleException("swiftpm.sdkVersion $version: no SCADE Swift SDK at $sdk" +
                (if (mac) "" else ". Install it with `swift sdk install` and set it up for the NDK"))
        val hostToolchain = if (mac) {
            swiftReleaseToolchainDir(swift)
                ?: throw GradleException("swiftpm.sdkVersion $version: no Swift $swift toolchain " +
                    "(swift-$swift-RELEASE.xctoolchain) in ~/Library/Developer/Toolchains")
        } else {
            installSwiftReleaseToolchain(swift, project.findProperty("swiftpm.linuxSwiftPlatform")?.toString(),
                File(temporaryDir, "swift-download"), project.logger)
        }
        return sdk to hostToolchain
    }

    private fun ndkBinDir(): File? {
        val ndk = ndkPath.orNull?.asFile ?: return null
        return File(ndk, "toolchains/llvm/prebuilt").listFiles()
            ?.map { File(it, "bin") }
            ?.firstOrNull { File(it, "llvm-strip").isFile }
    }

    /** The NDK's libc++_shared.so for [abi]; Swift's runtime links it dynamically. */
    private fun ndkLibCxxShared(abi: String): File? {
        val ndk = ndkPath.orNull?.asFile ?: return null
        val triple = ndkLibTripleByAbi[abi] ?: return null
        return File(ndk, "toolchains/llvm/prebuilt").listFiles()
            ?.map { File(it, "sysroot/usr/lib/$triple/libc++_shared.so") }
            ?.firstOrNull { it.isFile }
    }

    private fun getConnectedDeviceAbi(): String? {
        AndroidDebugBridge.initIfNeeded(false)
        val adb = AndroidDebugBridge.createBridge(adbPath.orNull?.asFile?.absolutePath, false)

        var attempts = 0
        while (!adb.hasInitialDeviceList() && attempts < 50) {
            Thread.sleep(100)
            attempts++
        }

        val devices = adb.devices
        if (devices.isEmpty()) {
            return null
        }

        val device: IDevice = devices[0]
        val abis = device.abis

        return abis.firstOrNull()
    }

}
