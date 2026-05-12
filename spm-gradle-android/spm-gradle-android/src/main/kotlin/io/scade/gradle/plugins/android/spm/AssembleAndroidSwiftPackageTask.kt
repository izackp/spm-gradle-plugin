package io.scade.gradle.plugins.android.spm

import io.scade.gradle.plugins.spm.TargetPlatform
import io.scade.gradle.plugins.spm.tasks.AssembleSwiftPackageTask
import io.scade.gradle.plugins.spm.tasks.PerPlatformInvocation

import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.IDevice
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Internal


abstract class AssembleAndroidSwiftPackageTask: AssembleSwiftPackageTask() {
    @Internal
    val sdkPath: DirectoryProperty = project.objects.directoryProperty()

    @Internal
    val ndkPath: DirectoryProperty = project.objects.directoryProperty()

    @Internal
    val adbPath: RegularFileProperty = project.objects.fileProperty()

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

        if (assembleDebug.get()) {
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
