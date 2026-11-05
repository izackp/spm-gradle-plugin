package io.scade.gradle.plugins.android.spm

import groovy.json.JsonOutput
import org.gradle.api.GradleException
import java.io.File
import java.nio.file.Files


/**
 * A Swift SDK description over SCADE's swift-android-compiler toolchain (6.2.x), so
 * `swift build --swift-sdk` builds against the runtime `scd archive` uses.
 *
 * That toolchain is not a Swift SDK: one resource tree serves every arch
 * (`usr/lib/swift/android/<arch>`), and clang's runtime libs come from the NDK. Each
 * arch gets a resource dir of links into the toolchain, with `clang/lib` pointing at
 * the NDK's compiler-rt and SwiftFoundation.jar where the swift-sdk build looks for it.
 * Only links; the toolchain is never written to.
 */
object ScadeToolchainSdk {
    fun isToolchain(toolchain: File): Boolean =
        File(toolchain, "usr/lib/swift/android").isDirectory &&
            File(toolchain, "usr/lib/swift_static").isDirectory

    /** Writes the description into the empty or missing [dir]. */
    fun write(dir: File, toolchain: File, ndk: File, triples: Map<String, String>) {
        val prebuilt = File(ndk, "toolchains/llvm/prebuilt").listFiles()
            ?.firstOrNull { File(it, "sysroot").isDirectory }
            ?: throw GradleException("No NDK sysroot under $ndk/toolchains/llvm/prebuilt")
        val clangLib = File(prebuilt, "lib/clang").listFiles()
            ?.map { File(it, "lib") }?.firstOrNull { it.isDirectory }
            ?: throw GradleException("No clang runtime libs under $prebuilt/lib/clang")
        val swift = File(toolchain, "usr/lib/swift")
        val bundle = File(dir, "scade-${toolchain.name}.artifactbundle")
        val variants = mutableListOf<Map<String, Any>>()

        for ((arch, triple) in triples) {
            val archDir = File(swift, "android/$arch")
            if (!archDir.isDirectory) {
                throw GradleException("The Swift for Android toolchain at $toolchain has no $arch runtime")
            }
            val res = File(bundle, "$arch/swift")
            File(res, "android").mkdirs()
            File(res, "clang").mkdirs()
            swift.listFiles()!!.filter { it.name != "android" && it.name != "clang" }
                .forEach { link(File(res, it.name), it) }
            File(swift, "android").listFiles()!!.forEach { link(File(res, "android/${it.name}"), it) }
            link(File(res, "android/SwiftFoundation.jar"), File(archDir, "SwiftFoundation.jar"))
            link(File(res, "clang/include"), File(swift, "clang/include"))
            link(File(res, "clang/lib"), clangLib)

            File(bundle, "$arch/swift-sdk.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(mapOf(
                "schemaVersion" to "4.0",
                "targetTriples" to mapOf(triple to mapOf(
                    "sdkRootPath" to File(prebuilt, "sysroot").absolutePath,
                    "swiftResourcesPath" to "swift",
                    "swiftStaticResourcesPath" to File(toolchain, "usr/lib/swift_static").absolutePath,
                )),
            ))))
            variants += mapOf("path" to arch, "supportedTriples" to listOf("arm64-apple-macosx"))
        }

        File(bundle, "info.json").writeText(JsonOutput.toJson(mapOf(
            "schemaVersion" to "1.0",
            "artifacts" to mapOf("scade-${toolchain.name}" to mapOf(
                "type" to "swiftSDK", "version" to toolchain.name, "variants" to variants)),
        )))
    }

    private fun link(at: File, target: File) {
        Files.createSymbolicLink(at.toPath(), target.toPath())
    }
}
