package io.scade.gradle.plugins.spm.tasks

import io.scade.gradle.plugins.spm.TargetPlatform
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit


/** A single buildable unit: a stable label + the scd args that select it. */
data class PerPlatformInvocation(val label: String, val args: List<String>)


abstract class AssembleSwiftPackageTask() : SpmGradlePluginTask() {
    @Internal
    val product: Property<String> = project.objects.property(String::class.java)

    @Internal
    val platforms: ListProperty<TargetPlatform> = project.objects.listProperty(TargetPlatform::class.java)

    @Internal
    val linkDependencies: ListProperty<String> = project.objects.listProperty(String::class.java)

    @Internal
    val assembleDebug: Property<Boolean> = project.objects.property(Boolean::class.java)

    @Internal
    val scdOptions: ListProperty<String> = project.objects.listProperty(String::class.java)

    @OutputDirectory
    val outputDirectory: DirectoryProperty = project.objects.directoryProperty()

    init {
        linkDependencies.convention(listOf())
        scdOptions.convention(listOf())
        outputDirectory.set(project.layout.buildDirectory.dir("lib"))
    }

    @TaskAction
    fun run() {
        val linkArgs = linkDependencies.get().flatMap { listOf("-l", it) }

        val invocations = expandedPlatformInvocations()
        val parallelRaw = project.findProperty("scd.parallelPlatforms")?.toString()
        val parallel = parallelRaw == "true" || parallelRaw == "1"
        logger.lifecycle("[scd] parallelPlatforms = $parallel (raw='$parallelRaw', invocations=${invocations.size})")

        if (!parallel || invocations.size <= 1) {
            scd("archive",
                "--build-dir", buildDirPath,
                "--path", packageDir,
                "--output", project.layout.buildDirectory.get(),
                "--product", product.get(),
                "--configuration", if (assembleDebug.get()) "Debug" else "Release",
                *scdOptions.get().toTypedArray(),
                *linkArgs.toTypedArray(),
                *platformArgs().toTypedArray())
            return
        }

        runParallel(invocations, linkArgs)
    }

    private fun runParallel(invocations: List<PerPlatformInvocation>, linkArgs: List<String>) {
        val rootBuildDir = project.layout.buildDirectory.asFile.get()
        val parallelRoot = File(rootBuildDir, "swiftpm-parallel")
        parallelRoot.mkdirs()

        // scd archive's underlying swift-build looks up plugin outputs at
        // `<scratchPath>/plugins/generate-java-bridging/outputs/...`. In
        // parallel mode each scd has its own scratch, so seed each one with
        // the bridging outputs that GenerateBridgingTask mirrored into the
        // main buildDirPath.
        val sharedPluginOutputs = File(buildDirPath, "plugins")
        val configuration = if (assembleDebug.get()) "Debug" else "Release"
        val scdPath = scdFile.asFile.get().absolutePath
        val productName = product.get()
        val packagePath = packageDir.absolutePath
        val scdOpts = scdOptions.get()

        val executor = Executors.newFixedThreadPool(invocations.size)
        val perPlatformOutputs = mutableMapOf<String, File>()
        try {
            val futures = invocations.map { inv ->
                val safeLabel = inv.label.replace("/", "-").replace(":", "-")
                val perBuildDir = File(parallelRoot, "$safeLabel-scratch")
                val perOutput = File(parallelRoot, "$safeLabel-output")
                perBuildDir.mkdirs()
                perOutput.mkdirs()

                if (sharedPluginOutputs.exists()) {
                    val dstPlugins = File(perBuildDir, "plugins")
                    sharedPluginOutputs.copyRecursively(dstPlugins, overwrite = true)
                }
                perPlatformOutputs[safeLabel] = perOutput
                val logFile = File(parallelRoot, "$safeLabel.log")

                executor.submit {
                    val cmd = mutableListOf(scdPath, "archive",
                        "--build-dir", perBuildDir.absolutePath,
                        "--path", packagePath,
                        "--output", perOutput.absolutePath,
                        "--product", productName,
                        "--configuration", configuration)
                    cmd.addAll(scdOpts)
                    cmd.addAll(linkArgs)
                    cmd.addAll(inv.args)

                    logger.lifecycle("[scd:$safeLabel] starting (log: ${logFile.absolutePath})")
                    val pb = ProcessBuilder(cmd)
                        .redirectErrorStream(true)
                        .redirectOutput(logFile)
                    val proc = pb.start()
                    val exit = proc.waitFor()
                    if (exit != 0) {
                        val tail = logFile.useLines { lines -> lines.toList().takeLast(80).joinToString("\n") }
                        throw RuntimeException("[scd:$safeLabel] failed (exit $exit). Tail:\n$tail")
                    }
                    logger.lifecycle("[scd:$safeLabel] done")
                }
            }
            futures.forEach { it.get() }
        } finally {
            executor.shutdown()
            executor.awaitTermination(1, TimeUnit.SECONDS)
        }

        mergeOutputs(perPlatformOutputs, File(rootBuildDir, "lib"))
    }

    private fun mergeOutputs(perPlatform: Map<String, File>, finalLib: File) {
        finalLib.mkdirs()
        perPlatform.values.forEach { srcRoot ->
            val srcLib = File(srcRoot, "lib")
            if (!srcLib.exists()) return@forEach
            srcLib.listFiles()?.forEach { entry ->
                val dst = File(finalLib, entry.name)
                if (entry.isDirectory) {
                    entry.copyRecursively(dst, overwrite = true)
                } else {
                    if (entry.name.endsWith(".jar") && dst.exists()) {
                        // Per-platform jars have identical Java content; keep the first one.
                        return@forEach
                    }
                    entry.copyTo(dst, overwrite = true)
                }
            }
        }
    }

    /**
     * Each entry becomes one scd archive invocation when running in parallel
     * mode. Default: one entry per TargetPlatform, simply emitting
     * `--platform <name>`. Subclasses (e.g. Android) override to expand a
     * single TargetPlatform's archs into multiple entries.
     */
    open fun expandedPlatformInvocations(): List<PerPlatformInvocation> {
        return platforms.get().map {
            val name = it.name.lowercase()
            PerPlatformInvocation(name, listOf("--platform", name))
        }
    }

    open fun platformArgs(): List<String> {
        return expandedPlatformInvocations().flatMap { it.args }
    }
}
