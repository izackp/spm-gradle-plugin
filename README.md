# Swift Packages for Gradle

The Swift Package Manager Plugin provides tasks to build swift packages, generate Java bridgings and assembling final products. 

## Description         

The projects consists of two plugins: the `spm-gradle` for the integration into standard Java/Kotlin Gradle builds and the `spm-gradle-android` when building for the Android platform. 
       

## Getting Started

### Dependencies

-  [Swift Toolchain](https://www.swift.org)
-  [Swift Toolchain for Android](https://www.swift-android.com)

### Usage

#### Applying plugins

To apply one of the plugins, add the plugin's id into the plugins block in the build file `build.gradle.kts`:

`spm-gradle`
```kotlin
plugins { 
    id("io.scade.gradle.plugins.swiftpm") version "1.0.3"
}
```

`spm-gradle-android`
```kotlin
plugins {
    id("io.scade.gradle.plugins.android.swiftpm") version "1.0.3"
}
```

#### Plugins configuration

For both plugins add the following configuration section to the build file `build.gradle.kts`:

```kotlin
swiftpm { 
    // Path to the folder containing Package.swift
    path = file("<PACKAGE LOCATION>")
    // Name of the package's product 
    product = "<PRODUCT NAME>"
    
    // OPTIONAL PROPERTIES
    
    // Java version (8, 9, 11, ...) used for the generated code compatibility
    // Example: set compatibility with Java 8
    javaVersion = 8 // (default: Java 11)
    
    // Platform configuration including custom toolchain path
    // Example: a custom path to the Android toolchain    
    platforms = listOf(
        TargetPlatform.Android(
            // List of build architectures
            archs = listOf("<armeabi-v7a | arm64-v8a | x86 | x86_64>"),
            // Path to a custom toolchain location
            toolchain = file("<PATH TO THE TOOLCHAIN LOCATION>"))
    )
    
    // Configuration for the Scade Build Tool (scd)
    
    // Custom binary location     
    scd = file("<SCD BINARY LOCATION>")
    // Auto-update for the scd build tool
    scdAutoUpdate = true
    // List of custom options passed to the scd build tool
    scdOptions = listOf("<OPTIONS>")
}
```

**Note:** both plugins only support dynamic library products

#### Building Android without scd (Linux)

scd runs on macOS only. Elsewhere the Android plugin builds with plain
`swift build --swift-sdk <arch>-unknown-linux-android24` against an installed Swift
SDK for Android, such as SCADE's `scade-swift-android-sdk`
(`swift sdk install <bundle>.artifactbundle.zip`). The backend is picked by host:

- macOS: `scd` (unchanged).
- anything else: `swift-sdk`.
- `-Pswiftpm.backend=scd|swift-sdk` overrides it.
- `-Pswiftpm.androidApiLevel=<n>` changes the triple's API level (default 24, the
  only one SCADE's SDK declares).

It produces what `scd archive` does, so consumers need no changes:
`lib/<abi>/lib<product>.so`, any `--embed-library` files, the NDK's
`libc++_shared.so` (plus the Swift runtime libs unless `--static-swift-stdlib`),
and `SwiftFoundation.jar`. The one difference is Java: scd compiles the `.java`
resources of Swift targets (swift4j's runtime) into per-target jars, while this
backend has `generateBridging` copy them beside the generated bridging sources for
the app to compile.

`scdOptions` are translated per ABI:
- `-Xswiftc`, `-Xcc`, `-Xcxx`, `-Xlinker`, and their `:android-<abi>` scoped forms,
  go into a per-ABI toolset, so they reach the Android target but not host
  builds such as macros.
- `--embed-library[:android-<abi>]=<file>` copies the file next to the library.
- `--static-swift-stdlib` passes through.
- Any other option fails the build rather than being dropped.

## Development

### Local Installation

Both plugins can be published into the local Maven repository by executing

```shell
./gradlew publishAllToMavenLocal 
```

In order to use locally installed plugins in your applications, 
add the local Maven repository to the plugin management section in the Gradle settings file `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal() 
    }
}
```

## License

This project is licensed under the Apache 2.0 License - see the LICENSE file for details