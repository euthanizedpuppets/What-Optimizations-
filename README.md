# What-Optimizations-

A deliberately overengineered Minecraft Java 26.2 Fabric experiment: replace
vanilla's CPU-heavy chunk-section meshing and drawing with an asynchronous
Rust pipeline that renders real, textured Minecraft geometry through
Minecraft's own render pipelines.

## Target and CLI

- Minecraft Java Edition **26.2**, Java **25**, Fabric Loader **0.19.5**.
- Rust `cdylib`, JNI direct-buffer ABI, bounded Rayon workers.
- The Gradle wrapper is committed; a global Gradle installation is not required.
- The renderer is backend-agnostic: it draws through vanilla's
  `RenderPipeline`/`RenderPass` API, so no GL/Vulkan backend is forced.

Build the Fabric mod and platform-native library:

```sh
./gradlew build
```

Run Rust unit/regression tests:

```sh
./gradlew nativeTest
```

Check Rust formatting:

```sh
./gradlew nativeFormatCheck
```

The wrapper downloads Gradle 9.7.0 once. Java 25 and Rust/Cargo are still required for a local native build. GitHub Actions builds and packages Linux, macOS, and Windows native libraries into the JAR.

## Current implementation

See [Native Terrain Pipeline](docs/NATIVE_TERRAIN_PIPELINE.md) for the full
design and [Implementation Phases](docs/PHASES.md) for the phase history.

### Capture (Java, vanilla compile worker threads)

`SectionCompiler.compile` is hooked at HEAD and RETURN. For a section the
native renderer already owns (ACTIVE), the HEAD hook runs a *takeover capture*:
vanilla's own `ModelBlockRenderer` and `FluidRenderer` tessellate the section
with a capturing `VertexConsumer` per render layer, replicating vanilla's loop
exactly (culling, AO, biome tint, directional shading, light smoothing, model
offsets, fluids, block entities, visibility graph), and the compile returns a
`Results` with **empty rendered layers** — vanilla meshes and draws nothing for
that section from then on. For the first build of a section, the RETURN hook
runs a *shadow capture*: vanilla's finished per-layer `MeshData` vertex buffers
(already in the 28-byte BLOCK format) are copied into a WOM2 snapshot at the
cost of one memcpy per layer.

### Meshing (Rust, asynchronous worker pool)

The WOM2 snapshot is copied into Rust-owned memory and meshed on a bounded
Rayon pool. Rust validates the ABI and emits, per layer, a **passthrough**
stream (byte-identical to vanilla) plus an optional **merged** stream: cube-face
runs greedily merged into maximal rectangles in a 44-byte tiled layout
(block-unit UVs + a per-vertex sprite rectangle). Merging only fires where it is
provably visually identical (axis-aligned unit quads in vanilla's `FaceInfo`
corner order with uniform corner color/light and a consistent UV orientation).
Tickets carry a section key and generation; stale results are discarded;
every job is explicitly released.

### Upload and drawing (Java, render thread)

Completed meshes are staged into per-layer `UberGpuBuffer` heaps (vanilla's own
allocator and `StagingBuffer`) and flushed after polling, so a mesh becomes
drawable the next frame — exactly like vanilla's chunk buffer uploads.
Ownership flips to ACTIVE once the mesh is uploaded, and a vanilla recompile is
queued so the next compile takes over (vanilla's mesh is released by vanilla
itself). `ChunkSectionsToRender.renderGroup` is hooked at TAIL: for each visible
section the native renderer owns, it issues `drawMultipleIndexed` calls with
vanilla's `SOLID_TERRAIN` / `CUTOUT_TERRAIN` / `TRANSLUCENT_TERRAIN` pipelines,
the block atlas (`Sampler0`), the lightmap (`Sampler2`), the shared sequential
QUADS index buffer and per-section `ChunkSection` uniform data — inside the
same frame pass, on the same render target, right after vanilla's terrain pass.
Opt-in tiled pipelines (`-Dwhatoptimizations.nativeRenderer.tiled=true`) draw
the merged stream with a custom `terrain_tiled` shader that reconstructs
per-block texturing exactly (`atlasUV = spriteRect.xy + fract(uv) * spriteRect.zw`).

### Safety model

A section is never invisible: vanilla geometry is only dropped (empty rendered
layers via takeover) after a native mesh for that exact section has been staged
and uploaded, and the native mesh has been drawable since the frame after
staging. Failures (capture errors, Rust errors, upload errors, queue limits)
mark the section FAILED and queue a vanilla recompile through
`levelExtractor.setSectionDirty`; any previously uploaded native mesh keeps
drawing meanwhile. The kill switch, level unload/dimension change and section
recycling all fall back to vanilla. Visibility for native draws comes from
vanilla's own `visibleSections` list (frustum + occlusion graph), so native
sections are never drawn when vanilla would not draw them.

## Runtime controls

- **F8** — toggle native terrain replacement (default **ON** when the native
  library loads). Turning it off reverts every section to vanilla and queues
  vanilla recompiles.
- **F9** — toggle debug mode: the takeover capture runs alongside vanilla
  compilation and is compared vertex-by-vertex against vanilla's output.
- **F7** — kill switch: stops all capturing and meshing immediately.

JVM properties:

- `-Dwhatoptimizations.nativeRenderer=false` starts with native rendering disabled.
- `-Dwhatoptimizations.nativeRenderer.tiled=true` enables the opt-in greedy-merged
  tiled terrain pipeline (custom shader; not exercised by CI).
- `-Dwhatoptimizations.nativeMesher=false` starts with Rust meshing disabled.
- `-Dwhatoptimizations.nativeMesher.debug=true` starts F9 diagnostics enabled.

## Verification status

- Rust: `cargo test` (43 unit tests: v1 cube mesher, WOM2 mesher, tickets,
  visibility) and `cargo fmt --check` pass locally and in CI on Linux, Windows
  and macOS; release `cdylib` builds on all three.
- Java: the full mod jar builds against real Minecraft 26.2 in CI
  (`./gradlew build nativeTest nativeFormatCheck`).
- **In-game visual verification has not been performed** (no game runtime in
  CI). See the final report for the exact in-game checks: F3 telemetry, F8
  toggle, chunk rebuild with F3+A, dimension switch, resource reload.
