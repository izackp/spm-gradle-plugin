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

    private val pluginAvailable: Boolean
        get() {
            return swift("package",
                "--disable-experimental-prebuilts",
                "--scratch-path", bridgingScratchPath,
                "--package-path", packageDir,
                "plugin", "--list"
            ) {_, out ->
                    out.split("\n").find { it.startsWith("‘generate-java-bridging’") } != null
            }
        }

    @TaskAction
    fun run() {
        if (pluginAvailable) {
            val args = mutableListOf(
                "package",
                "--disable-experimental-prebuilts",
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