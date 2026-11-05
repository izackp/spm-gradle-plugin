package io.scade.gradle.plugins.android.spm

import groovy.json.JsonOutput
import org.gradle.api.GradleException
import java.io.File
import java.nio.file.Files


/** A macOS-host Swift SDK description over SCADE's Swift SDK (scade-swift-android-sdk, 6.3+). */
object ScadeSwiftSdk {
    /** Writes the description into the empty or missing [dir]. */
    fun write(dir: File, sdk: File, ndk: File, triples: Map<String, String>) {
        val sysroot = File(ndk, "toolchains/llvm/prebuilt").listFiles()
            ?.map { File(it, "sysroot") }?.firstOrNull { it.isDirectory }
            ?: throw GradleException("No NDK sysroot under $ndk/toolchains/llvm/prebuilt")
        val bundle = File(dir, "scade-${sdk.name}.artifactbundle")
        val variants = mutableListOf<Map<String, Any>>()

        for ((arch, triple) in triples) {
            val archDir = File(sdk, arch)
            val lib = File(archDir, "usr/lib")
            if (!File(lib, "swift_static").isDirectory) {
                throw GradleException("The SCADE Swift SDK at $sdk has no $arch runtime")
            }
            val root = File(bundle, "$arch/ndk-sysroot")
            File(root, "usr/lib").mkdirs()
            File(sysroot, "usr").listFiles()!!.filter { it.name != "lib" }
                .forEach { link(File(root, "usr/${it.name}"), it) }
            File(sysroot, "usr/lib").listFiles()!!.forEach { link(File(root, "usr/lib/${it.name}"), it) }
            link(File(root, "usr/lib/swift"), File(lib, "swift"))
            link(File(root, "usr/lib/swift_static"), File(lib, "swift_static"))

            File(bundle, "$arch/swift-sdk.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(mapOf(
                "schemaVersion" to "4.0",
                "targetTriples" to mapOf(triple to mapOf(
                    "sdkRootPath" to "ndk-sysroot",
                    "swiftResourcesPath" to File(lib, "swift").absolutePath,
                    "swiftStaticResourcesPath" to File(lib, "swift_static").absolutePath,
                    "includeSearchPaths" to listOf(File(archDir, "usr/include").absolutePath),
                    "librarySearchPaths" to listOf(lib.absolutePath, File(lib, "swift/android").absolutePath),
                )),
            ))))
            variants += mapOf("path" to arch, "supportedTriples" to listOf(macHostTriple()))
        }

        File(bundle, "info.json").writeText(JsonOutput.toJson(mapOf(
            "schemaVersion" to "1.0",
            "artifacts" to mapOf("scade-${sdk.name}" to mapOf(
                "type" to "swiftSDK", "version" to sdk.name, "variants" to variants)),
        )))
    }

    private fun link(at: File, target: File) {
        Files.createSymbolicLink(at.toPath(), target.toPath())
    }
}

/** The triple SwiftPM matches a Swift SDK's supportedTriples against on this Mac. */
fun macHostTriple(): String =
    if (System.getProperty("os.arch") == "aarch64") "arm64-apple-macosx" else "x86_64-apple-macosx"
