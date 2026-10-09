# Implementation phases

## Phase 0 — scaffold (this branch)

- [x] Gradle/Fabric client mod scaffold for Minecraft 26.2 and Java 25.
- [x] Rust cdylib using jni and rayon.
- [x] Native library extraction and System.load.
- [x] Native.hello with Rust stdout output and an integer error status.
- [x] OpenGL preference mixins adapted from BackToGL's 26.2 implementation.
- [x] GitHub Actions matrix for Linux x86_64, Windows x86_64, and macOS arm64 native builds plus a mod jar artifact.
- [ ] Launch Minecraft and verify actual backend selection on a real 26.2 installation.

**Phase 0 status:** scaffold committed; CI build must pass and runtime backend selection still needs an in-game smoke test. Do not proceed to Phase 1 until this phase is confirmed.

## Phase 1 — Rust section meshing (not implemented)

Before writing mixins, inspect the exact 26.2 decompiled sources using gradle genSources and identify the real section compiler, block-state storage/palette, block render shape, and lighting access points. Do not guess names or descriptors.

The Java side should marshal one complete section plus required neighbor border data into a direct ByteBuffer. Rust should process sections in parallel, with no JNI call per block or quad. Output should use a documented packed vertex format no larger than 16 bytes per vertex. Face culling, greedy merging, ambient occlusion, and light packing must be validated against vanilla output. Render-layer/material distinctions and non-cube models require explicit fallback handling; they cannot be safely meshed as generic cubes.

## Phase 2 — visibility (not implemented)

Compute frustum visibility per frame and investigate a cave-visibility graph based on section connectivity. Return a compact visible-section list through one bulk JNI call per frame. Validate against vanilla in open terrain, caves, water, and sections with disconnected openings.

## Phase 3 — draw path (not implemented)

Use a large vertex buffer with a bounded suballocator and explicit lifetime/fence handling. Draw visible spans in batches. Keep Minecraft's GL state and resource lifetime rules intact. Check OpenGL 3.3 baseline support and gate newer features behind detected extensions.

## Phase 4 — optional Rust OpenGL calls (stretch, not implemented)

Only after the Java-owned draw path is correct. Resolve GL entry points on the render thread, preserve/restore relevant state, and fail closed if the context/thread is wrong. Do not call OpenGL from Rayon worker threads.
