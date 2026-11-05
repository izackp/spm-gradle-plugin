package io.scade.gradle.plugins.spm.tasks

import io.scade.gradle.plugins.spm.swiftAndroidToolchainDir
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.*
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Paths

abstract class ResolveScdToolTask() : DefaultTask() {
    @Optional
    @InputFile
    val scd: RegularFileProperty = project.objects.fileProperty()

    @Optional
    @Input
    val scdAutoUpdate: Property<Boolean> = project.objects.property(Boolean::class.java)

    /** The scd version to install, replacing any other; unset follows the latest. */
    @Optional
    @Input
    val scdVersion: Property<String> = project.objects.property(String::class.java)

    /** The Swift for Android toolchain version to install; unset installs the latest. */
    @Optional
    @Input
    val toolchainVersion: Property<String> = project.objects.property(String::class.java)

    @OutputFile
    val scdFile: RegularFileProperty = project.objects.fileProperty()

    init {
        scdFile.convention {
            Paths.get(System.getProperty("user.home")).resolve("Library/Developer/Scade/Toolchains/scd/bin/scd").toFile()
        }
    }

    @TaskAction
    fun run() {
        val scd = scd.orNull?.asFile

        val pinned = scdVersion.orNull?.let(::parseVersion)

        if (scd != null) {
            scdFile.set(scd)

        } else if (pinned != null) {
            val cur = scdFile.asFile.get().takeIf { it.exists() }?.let(::getInstalledVersion)
            if (cur != pinned) {
                project.logger.lifecycle("Installing Scade Build Tool ${pinned.versionString()}" +
                    (cur?.let { " in place of ${it.versionString()}" } ?: "") + " ...")
                downloadAndInstall(pinned)
                val installed = scdFile.asFile.get().takeIf { it.exists() }?.let(::getInstalledVersion)
                if (installed != pinned) {
                    throw RuntimeException("Installed Scade Build Tool ${pinned.versionString()}, but " +
                        "${scdFile.asFile.get()} reports ${installed?.versionString()}")
                }
            }

        } else if (scdFile.asFile.get().exists()) {
            val cur = getInstalledVersion(scdFile.asFile.get())
            cur?.let {
                project.logger.lifecycle("Scade Build Tool version ${cur.first}.${cur.second}.${cur.third} is found ...")

                val latest = getLatestVersion()

                if ((latest.first > cur.first) ||
                    (latest.first == cur.first && latest.second > cur.second) ||
                    (latest.first == cur.first && latest.second == cur.second && latest.third > cur.third)) {

                    project.logger.lifecycle("New version of the Scade Build Tool is available: ${latest.versionString()}")


                    if(scdAutoUpdate.orElse(false).get()) {
                        project.logger.lifecycle("Updating Scade Build Tool to the latest version ... ")
                        downloadAndInstall(latest)
                    }
                }
            }
        } else {
            downloadAndInstallLatest()
        }

        if (!scdFile.asFile.get().exists()) {
            throw RuntimeException("Scade Build Tool could not be resolved.")

        } else {
            project.logger.lifecycle("Scade Build Tool is ready to use.")
            val toolchain = toolchainVersion.orNull
            if (toolchain == null) {
                project.logger.lifecycle("Check and update Swift for Android toolchain ...")
                project.exec {
                    it.commandLine(scdFile.asFile.get(), "toolchain", "install-latest")
                }
            } else if (!swiftAndroidToolchainDir(toolchain).isDirectory) {
                project.logger.lifecycle("Installing Swift for Android toolchain $toolchain ...")
                project.exec {
                    it.commandLine(scdFile.asFile.get(), "toolchain", "install", "--version", toolchain)
                }
            }
        }

    }

    private fun parseVersion(version: String): Triple<Int, Int, Int> {
        val parts = version.split('.').map { it.toIntOrNull() }
        if (parts.size != 3 || parts.any { it == null }) {
            throw RuntimeException("swiftpm.scdVersion '$version' is not a major.minor.patch version")
        }
        return Triple(parts[0]!!, parts[1]!!, parts[2]!!)
    }

    private fun downloadAndInstall(version: Triple<Int, Int, Int>?) {
        val pkgVer = (version ?: getLatestVersion()).versionString()
        val pkgFileName = "scd-${pkgVer}.pkg"

        val pkgFile = temporaryDir.resolve(pkgFileName)

        val downloadUrl = URI("https://github.com/scade-platform/scade-build-tool/releases/download/${pkgVer}/${pkgFileName}").toURL()

        if (!pkgFile.exists()) {
            project.logger.lifecycle("Downloading from ${downloadUrl}...")
            downloadUrl.openStream().use {
                it.copyTo(FileOutputStream(pkgFile))
            }
        }

        if (pkgFile.exists()) {
            project.logger.lifecycle("Installing Scade Build Tool...")

            project.exec {
                it.commandLine("installer", "-pkg", "$pkgFile", "-target", "CurrentUserHomeDirectory")
            }
        }
    }

    private fun downloadAndInstallLatest() {
        downloadAndInstall(null)
    }

    private fun getLatestVersion() : Triple<Int, Int, Int> {
        val latestUrl = URI("https://github.com/scade-platform/scade-build-tool/releases/latest").toURL()
        val conn = latestUrl.openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false

        val version = conn.getHeaderField("Location").split("/").last().split('.')
        return Triple(version[0].toInt(), version[1].toInt(), version[2].toInt())
    }

    private fun getInstalledVersion(scd:  java.io.File) : Triple<Int, Int, Int>? {
        val versionInfo = ByteArrayOutputStream().use { out ->
            project.exec {
                it.commandLine(scd.path, "--version")
                it.standardOutput = out
            }
            out.toString()
        }

        val versionRegex = """\D*(?<major>\d*)\.(?<minor>\d*).(?<patch>\d*)""".toRegex()
        val versionMatch = versionRegex.find(versionInfo)

        return versionMatch?.let {
            Triple(it.groups["major"]!!.value.toInt(),
                   it.groups["minor"]!!.value.toInt(),
                   it.groups["patch"]!!.value.toInt())

        } ?: return null
    }
}

private fun Triple<Int, Int, Int>.versionString() : String {
    return "${first}.${second}.${third}"
}
