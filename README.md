# What-Optimizations-

A deliberately overengineered Minecraft Java 26.2 Fabric experiment: a custom chunk-rendering pipeline with CPU-heavy work planned for Rust through JNI.

## Current stage: Phase 0

This repository currently contains the scaffold only:

- Java 25, Minecraft 26.2, Mojang's unobfuscated names, and Fabric Loader 0.19.3+.
- A Rust cdylib loaded from a platform-specific jar resource through a temporary file.
- A Native.hello call that prints from Rust.
- A GitHub Actions build that compiles native libraries on Linux, Windows, and macOS and builds the mod jar.
- OpenGL backend preference mixins based on the concrete 26.2 targets used by BackToGL.

Chunk meshing, visibility, and custom drawing are not implemented yet. Those are later phases and must not be represented as working features before their Minecraft 26.2 internals are verified.

## Get the build

Open the repository's Actions tab, select the latest successful Build workflow run, and download the minecraft-mod-jar artifact. Put the resulting mod jar in the 26.2 Fabric instance's mods directory. The jar embeds the platform libraries built by that workflow.

A local build requires Java 25, Gradle 9.5.1 or newer compatible with the configured Loom release, and the Rust toolchain with Cargo. Run gradle build; this compiles the native library for the current host and bundles it.

## Compatibility warning

This mod intentionally prefers OpenGL. It will not enable the Vulkan renderer. Backend-selection mixins can conflict with other mods that change the same early initialization path. The future custom renderer is likely to conflict with Sodium, Iris, or any other mod that replaces chunk rendering; Phase 1 must define a clear compatibility policy before attempting to coexist with them.

## Native safety

JNI entry points must not unwind across the ABI boundary. Native code uses catch_unwind and error codes. Bulk direct buffers are a future phase requirement: never cross JNI once per block or quad.
