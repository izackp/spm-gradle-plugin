package io.scade.gradle.plugins.android.spm

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import java.io.File


/**
 * `scdOptions` rewritten for one `swift build --swift-sdk` invocation.
 *
 * scd scopes options to a platform with a suffix (`-Xlinker:android-arm64-v8a`), and
 * applies its unscoped `-X` options to the target only. `swift build -Xswiftc` and
 * friends also reach host builds (macros, plugins), where a target-only flag such as
 * `-resource-dir` breaks them. So every `-X` option goes into a toolset, which
 * SwiftPM applies to the target triple alone.
 */
class SwiftSdkOptions(
    val buildArgs: List<String>,
    val swiftc: List<String>,
    val cc: List<String>,
    val cxx: List<String>,
    val linker: List<String>,
    val embedLibraries: List<File>,
    val staticStdlib: Boolean,
) {
    /** Toolset JSON carrying the target-only options. */
    fun toolsetJson(): String {
        val tools = linkedMapOf<String, Any>("schemaVersion" to "1.0")
        if (swiftc.isNotEmpty()) tools["swiftCompiler"] = mapOf("extraCLIOptions" to swiftc)
        if (cc.isNotEmpty()) tools["cCompiler"] = mapOf("extraCLIOptions" to cc)
        if (cxx.isNotEmpty()) tools["cxxCompiler"] = mapOf("extraCLIOptions" to cxx)
        if (linker.isNotEmpty()) tools["linker"] = mapOf("extraCLIOptions" to linker)
        return JsonOutput.prettyPrint(JsonOutput.toJson(tools))
    }

    companion object {
        /** [platform] is scd's name for the one being built, e.g. `android-arm64-v8a`. */
        fun translate(options: List<String>, platform: String): SwiftSdkOptions {
            val buildArgs = mutableListOf<String>()
            val swiftc = mutableListOf<String>()
            val cc = mutableListOf<String>()
            val cxx = mutableListOf<String>()
            val linker = mutableListOf<String>()
            val embed = mutableListOf<File>()
            var staticStdlib = false

            val args = options.iterator()

            // `-Xtool <value>` or `-Xtool:<platform> <value>`. The first half says
            // whether [opt] is [tool]'s option at all (its value is then consumed);
            // the second is that value, or null when it is scoped to another platform.
            fun scopedValue(opt: String, tool: String): Pair<Boolean, String?> {
                val mine = when {
                    opt == tool -> true
                    opt.startsWith("$tool:") -> opt.removePrefix("$tool:") == platform
                    else -> return false to null
                }
                if (!args.hasNext()) throw GradleException("scd option $opt has no value")
                val v = args.next()
                return true to (if (mine) v else null)
            }

            while (args.hasNext()) {
                val opt = args.next()

                val (isSwiftc, swiftcValue) = scopedValue(opt, "-Xswiftc")
                if (isSwiftc) { swiftcValue?.let { swiftc += it }; continue }
                val (isLinker, linkerValue) = scopedValue(opt, "-Xlinker")
                if (isLinker) { linkerValue?.let { linker += it }; continue }
                val (isCxx, cxxValue) = scopedValue(opt, "-Xcxx")
                if (isCxx) { cxxValue?.let { cxx += it }; continue }
                val (isCc, ccValue) = scopedValue(opt, "-Xcc")
                if (isCc) {
                    // Swift's clang importer reads the same headers, so it gets -Xcc too.
                    ccValue?.let { cc += it; swiftc += listOf("-Xcc", it) }
                    continue
                }

                when {
                    opt == "--static-swift-stdlib" -> { staticStdlib = true; buildArgs += opt }
                    opt.startsWith("--embed-library=") -> embed += File(opt.substringAfter("="))
                    opt.startsWith("--embed-library:") -> {
                        val (scope, path) = opt.removePrefix("--embed-library:").split("=", limit = 2)
                        if (scope == platform) embed += File(path)
                    }
                    else -> throw GradleException(
                        "scd option '$opt' has no swift-sdk backend equivalent; build with -Pswiftpm.backend=scd")
                }
            }
            return SwiftSdkOptions(buildArgs, swiftc, cc, cxx, linker, embed, staticStdlib)
        }
    }
}


/** An installed Swift SDK's target-specific paths for one triple. */
class SwiftSdkTarget(val triple: String, val swiftResources: File) {
    /** Where the runtime libs and SwiftFoundation.jar live for [arch]. */
    fun androidLibDir(arch: String): File {
        val android = File(swiftResources, "android")
        // Per-arch SDK bundles (SCADE 6.3) keep libs directly in android/; a
        // multi-arch tree keeps them in android/<arch>/.
        return if (File(android, "libswiftCore.so").isFile) android else File(android, arch)
    }

    companion object {
        private fun sdkRoots(): List<File> {
            val home = File(System.getProperty("user.home"))
            return listOfNotNull(
                System.getenv("SWIFTPM_SWIFT_SDKS_PATH")?.let(::File),
                File(home, ".swiftpm/swift-sdks"),
                File(home, "Library/org.swift.swiftpm/swift-sdks"),
            ).filter { it.isDirectory }
        }

        /**
         * Finds the installed SDK that declares [triple], the way `swift build
         * --swift-sdk <triple>` does. SwiftPM itself prints no machine-readable form of
         * this, so read the bundles' swift-sdk.json files directly.
         */
        fun find(triple: String): SwiftSdkTarget {
            val matches = sdkRoots().flatMap { root ->
                root.walkTopDown().maxDepth(4).filter { it.name == "swift-sdk.json" }.mapNotNull { json ->
                    @Suppress("UNCHECKED_CAST")
                    val doc = JsonSlurper().parse(json) as? Map<String, Any?>
                    val triples = doc?.get("targetTriples") as? Map<String, Map<String, Any?>>
                    val entry = triples?.get(triple) ?: return@mapNotNull null
                    val resources = entry["swiftResourcesPath"] as? String ?: return@mapNotNull null
                    SwiftSdkTarget(triple, File(json.parentFile, resources).canonicalFile)
                }.toList()
            }
            return when (matches.size) {
                1 -> matches.single()
                0 -> throw GradleException("No installed Swift SDK targets $triple (searched ${sdkRoots()}). " +
                    "Install SCADE's scade-swift-android-sdk with `swift sdk install`.")
                else -> throw GradleException("Several installed Swift SDKs target $triple: " +
                    matches.joinToString { it.swiftResources.path } + ". swift build cannot pick between them either.")
            }
        }
    }
}
