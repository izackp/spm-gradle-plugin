package io.scade.gradle.plugins.android.spm

import io.scade.gradle.plugins.spm.TargetPlatform
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

        for (abi in effectiveAndroidArchs()) {
            val arch = swiftArchByAbi[abi]
                ?: throw GradleException("No Swift architecture for Android ABI $abi")
            val triple = "$arch-unknown-linux-android$apiLevel"
            val sdk = SwiftSdkTarget.find(triple)
            val opts = SwiftSdkOptions.translate(scdOptions.get(), "android-$abi")
            val toolset = File(toolsetDir, "$abi.json").apply { writeText(opts.toolsetJson()) }

            project.logger.lifecycle("[swift-sdk] building ${product.get()} for $triple ($configuration)")
            swift("build",
                "--package-path", packageDir,
                "--scratch-path", buildDirPath,
                "--swift-sdk", triple,
                "--toolset", toolset,
                "-c", configuration,
                "--product", product.get(),
                *opts.buildArgs.toTypedArray())

            val binDir = File(buildDirPath, "$triple/$configuration")
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
            }
            ndkLibCxxShared(abi)?.let { it.copyTo(File(abiDir, it.name), overwrite = true) }
            foundationJar = foundationJar
                ?: listOf(File(sdkLibs, "SwiftFoundation.jar"), File(sdk.swiftResources, "android/SwiftFoundation.jar"))
                    .firstOrNull { it.isFile }
        }

        foundationJar?.copyTo(File(libRoot, "SwiftFoundation.jar"), overwrite = true)
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
