package io.scade.gradle.plugins.spm.tasks

import io.scade.gradle.plugins.spm.SwiftBackend
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.process.ExecResult
import org.gradle.process.ExecSpec
import java.io.ByteArrayOutputStream
import java.io.File


abstract class SpmGradlePluginTask : DefaultTask() {
    /** Unset under the swift-sdk backend, which never runs scd. */
    @get:Optional
    @get:InputFile
    val scdFile: RegularFileProperty = project.objects.fileProperty()

    @get:Input
    val backend: Property<String> = project.objects.property(String::class.java)
        .convention(SwiftBackend.of(project).id)

    @get:Internal
    val swiftBackend: SwiftBackend
        get() = SwiftBackend.values().first { it.id == backend.get() }

    @get:InputDirectory
    val path: DirectoryProperty = project.objects.directoryProperty()

    @get:Internal
    val buildDir: DirectoryProperty = project.objects.directoryProperty()

    init {
        buildDir.set(project.layout.buildDirectory.map {
            it.dir("swiftpm/${path.asFile.get().name}")
        })
    }

    @get:Internal
    val packageDir: File
        get() = path.asFile.get().absoluteFile


    @get:Internal
    val buildDirPath: File
        get() {
            buildDir.get().asFile.mkdirs()
            return buildDir.asFile.get().absoluteFile
        }


    /**
     * The Xcode every tool runs with (`swiftpm { developerDir }`): host swift, scd, and
     * the clang that compiles the packages' C code. Passed to each process as
     * DEVELOPER_DIR, so neither PATH nor the environment the Gradle daemon started
     * with decides it. Unset, the environment's Xcode is used.
     */
    @get:Optional
    @get:Input
    val developerDir: Property<String> = project.objects.property(String::class.java)

    @get:Internal
    val toolEnvironment: Map<String, String>
        get() {
            val dir = developerDir.orNull ?: return emptyMap()
            if (!File(dir, "usr/bin/xcodebuild").isFile) {
                throw GradleException("swiftpm.developerDir: no Xcode at $dir")
            }
            return mapOf("DEVELOPER_DIR" to dir)
        }

    fun execTool(spec: ExecSpec, command: List<Any>) {
        spec.commandLine(command)
        spec.environment(toolEnvironment)
    }

    fun scd(vararg args: Any): ExecResult {
        return project.exec {
            execTool(it, listOf(scdFile.asFile.get(), *args))
        }
    }

    /**
     * The command that runs swift: the words of `-Pswiftpm.swiftCommand`, e.g. a
     * launcher that ends in `swift` and adds its own options; else `/usr/bin/swift`,
     * which follows DEVELOPER_DIR, when [developerDir] is set; else `swift` from PATH.
     * Not an input: it changes how swift runs, not what it builds.
     */
    @get:Internal
    val swiftCommand: List<String>
        get() = project.findProperty("swiftpm.swiftCommand")?.toString()
            ?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }
            ?.ifEmpty { null }
            ?: listOf(if (developerDir.isPresent) "/usr/bin/swift" else "swift")

    fun swift(vararg args: Any): ExecResult {
        return project.exec {
            execTool(it, swiftCommand + args)
        }
    }

    fun<T> swift(vararg args: Any, captureOutput: (ExecResult, String) -> T): T {
        val stdout = ByteArrayOutputStream()
        val res = project.exec {
            execTool(it, swiftCommand + args)
            it.standardOutput = stdout
        }
        return captureOutput(res, stdout.toString())
    }
}
