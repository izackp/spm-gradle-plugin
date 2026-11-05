package io.scade.gradle.plugins.spm.tasks

import org.gradle.api.provider.Property
import org.gradle.api.provider.ListProperty
import org.gradle.api.file.DirectoryProperty

import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction


abstract class GenerateBridgingTask() : SpmGradlePluginTask() {
    @Internal
    val product: Property<String> = project.objects.property(String::class.java)

    @Internal
    val javaVersion: Property<Int> = project.objects.property(Int::class.java)

    @Internal
    val copyJavaSources: Property<Boolean> = project.objects.property(Boolean::class.java)

    @Internal
    val extraArguments: ListProperty<String> = project.objects.listProperty(String::class.java)

    @OutputDirectory
    val bridgingSrc: DirectoryProperty = project.objects.directoryProperty()

    @OutputDirectory
    val bridgingJavaSrc: DirectoryProperty = project.objects.directoryProperty()

    init {
        copyJavaSources.convention(true)

        bridgingSrc.set(
            product.map {
                buildDir.get().dir("plugins/generate-java-bridging/outputs/$it/main")
            }
        )
        bridgingJavaSrc.set(
            product.map {
                buildDir.get().dir("plugins/generate-java-bridging/outputs/$it/main/java")
            }
        )
    }

    // Host Apple swift (e.g. 6.2.4) is used by this task; scd ships a different
    // swift (e.g. 6.2.1). Sharing one scratch path lets host swift write
    // Modules-tool/ swiftmodules that scd can't import on the next build.
    // Run in a sibling scratch dir so the two compilers never overlap, then
    // mirror the plugin outputs into the main scratch path so scd archive's
    // own swift-build finds them where it expects.
    @get:Internal
    val bridgingScratchPath: java.io.File
        get() {
            val base = buildDirPath
            val sibling = java.io.File(base.parentFile, "${base.name}-bridging")
            sibling.mkdirs()
            return sibling
        }

    // Prebuilt swift-syntax is off here: with Swift 6.2 a host tool mixing it with
    // swift-syntax built from source broke. Swift 6.3's SwiftPM uses it only when
    // every host module can, and `-Pswiftpm.prebuilts=true` turns it back on.
    @get:Internal
    val prebuiltsArguments: List<String>
        get() = if (project.findProperty("swiftpm.prebuilts")?.toString() == "true") emptyList()
            else listOf("--disable-experimental-prebuilts")

    private val pluginAvailable: Boolean
        get() {
            return swift("package",
                *prebuiltsArguments.toTypedArray(),
                "--scratch-path", bridgingScratchPath,
                "--package-path", packageDir,
                "plugin", "--list"
            ) {_, out ->
                    out.split("\n").find { it.startsWith("‘generate-java-bridging’") } != null
            }
        }

    /**
     * Host tools (swift-syntax, the macros) built by one Swift cannot be imported by
     * another, and SwiftPM does not rebuild them when the compiler changes: switching
     * Xcode failed with `module compiled with Swift X cannot be imported by the Swift
     * Y compiler`. Record which Swift built them, in the host build dir so a copy of
     * that dir carries it too, and clean the scratch when it differs or is unknown.
     */
    private fun cleanIfHostSwiftChanged() {
        val version = swift("--version") { _, out -> out.trim() }
        val hostArch = if (System.getProperty("os.arch") == "aarch64") "arm64" else "x86_64"
        val hostBuild = java.io.File(bridgingScratchPath, "$hostArch-apple-macosx/debug")
        val stamp = java.io.File(hostBuild, ".host-swift-version")
        if (hostBuild.isDirectory && (!stamp.isFile || stamp.readText() != version)) {
            logger.lifecycle("[swiftpm] host tools in $bridgingScratchPath were built by another Swift; cleaning")
            swift("package", "--scratch-path", bridgingScratchPath, "--package-path", packageDir, "clean")
        }
        hostBuild.mkdirs()
        stamp.writeText(version)
    }

    @TaskAction
    fun run() {
        cleanIfHostSwiftChanged()
        if (pluginAvailable) {
            val args = mutableListOf<Any>(
                "package",
                *prebuiltsArguments.toTypedArray(),
                "--scratch-path", bridgingScratchPath,
                "--package-path", packageDir,
                "plugin", "generate-java-bridging",
                "--product", product.get(),
                "--java-version", javaVersion.getOrElse(11),
            )

            if (copyJavaSources.get()) {
                args.add("--copy-java-sources")
            }

            swift(*args.toTypedArray(), *extraArguments.get().toTypedArray())

            mirrorPluginOutputs()
        }
    }

    /**
     * Mirror the bridging-scratch plugin outputs into the main scratch dir.
     * scd archive's own swift-build expects to find plugin outputs at
     * `<scratchPath>/plugins/generate-java-bridging/outputs/...`. We don't
     * want generateBridging to write there directly (host-swift Modules-tool
     * contamination), so copy the output tree post-hoc.
     */
    private fun mirrorPluginOutputs() {
        val src = java.io.File(bridgingScratchPath, "plugins/generate-java-bridging/outputs")
        if (!src.exists()) return
        val dst = java.io.File(buildDirPath, "plugins/generate-java-bridging/outputs")
        dst.parentFile.mkdirs()
        src.copyRecursively(dst, overwrite = true)
    }
}