package io.scade.gradle.plugins.spm

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.internal.os.OperatingSystem


/**
 * What builds the Swift package.
 *
 * - [SCD]: SCADE's `scd archive`. scd ships for macOS only.
 * - [SWIFT_SDK]: plain `swift build --swift-sdk <triple>` against an installed
 *   Swift SDK (e.g. SCADE's scade-swift-android-sdk), for hosts scd does not run on.
 *
 * Defaults to [SCD] on macOS and [SWIFT_SDK] elsewhere; `-Pswiftpm.backend=scd|swift-sdk`
 * overrides it.
 */
enum class SwiftBackend(val id: String) {
    SCD("scd"),
    SWIFT_SDK("swift-sdk");

    companion object {
        fun of(project: Project): SwiftBackend {
            val requested = project.findProperty("swiftpm.backend")?.toString()
                ?: return if (OperatingSystem.current().isMacOsX) SCD else SWIFT_SDK
            return values().find { it.id == requested }
                ?: throw GradleException("swiftpm.backend=$requested is not one of ${values().joinToString { it.id }}")
        }
    }
}
