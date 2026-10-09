# What-Optimizations-

A deliberately overengineered Minecraft Java 26.2 Fabric experiment: move section-mesh CPU work into Rust through JNI, then experiment with Rust-owned visibility and OpenGL draws.

## Target and CLI

- Minecraft Java Edition **26.2**, Java **25**, Fabric Loader **0.19.5**.
- Rust `cdylib`, JNI direct-buffer ABI, bounded Rayon workers.
- The Gradle wrapper is committed; a global Gradle installation is not required.
- The Phase 0 Force-OpenGL preference hooks are enabled again on this branch for the requested GL 3.3 path; backend selection still needs verification in the target client.

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

### Native meshing and worker jobs

The verified `SectionCompiler.compile(SectionPos, RenderSectionRegion, VertexSorting, SectionBufferBuilderPack)` hook runs after vanilla compilation. Java snapshots a 18×18×18 volume (section plus one-block border), builds a compact palette of native-endian state IDs/flags and cell light values, and sends it through direct `ByteBuffer`s.

Rust performs opaque-full-cube face culling, greedy rectangle merging, initial AO/light packing, and emits packed 16-byte vertices. The input snapshot is copied into Rust-owned memory before JNI returns. A bounded Rayon pool handles the work without touching the JVM. Java polls job tickets on the render thread. Tickets carry a section key and generation, stale results are discarded, result memory has a queue-wide budget, and Java explicitly calls `release(ticket)` for every completed/cancelled job.

The native registry is capped at 96 tickets, queued mesh bytes are capped at 32 MiB, the Java pending list is capped at 64, and the CPU cache is capped at 16 MiB. The Rust pool is limited to one or two workers to leave CPU headroom for Minecraft's own work and integrated-server ticking. Sampling defaults to one out of every eight section compiler callbacks; the F9 debug mode forces every section through the diagnostic path.

### Visibility and draw experiment

Rust includes an AABB frustum test plus a sparse six-neighbor open-face graph walk. Metadata is derived from sampled section borders. If the camera's section is absent from this partial graph, visibility fails open to frustum-only results so the diagnostic path does not hide unrelated sections.

The opt-in draw pass uses a 64 MiB OpenGL 3.3 arena, an explicit free-range allocator, cached per-section uploads, and `glMultiDrawArrays`. When `whatoptimizations.nativeRenderer.rustGl=true`, Rust receives OpenGL function pointers resolved on the current GLFW context and performs the state-save/configure/draw/restore portion of the call itself. Java still creates the diagnostic shader and GPU arena, does vertex conversion/uploads, and falls back to LWJGL drawing if native GL dispatch fails. The path does not use Rust GL calls from worker threads.

## Runtime controls

- **F7** — stop/restart Rust meshing. Turning it off releases pending tickets and clears the native mesh/visibility cache. Vanilla rendering is unchanged.
- **F8** — toggle the experimental native GL diagnostic draw pass. It remains **off by default**. Rebuild chunks (F3+A) after enabling so uncached sections are sampled.
- **F9** — toggle deep diagnostics. It enables full sampling and compares synchronous Rust output against async Rust output, then compares normalized native cube faces with the actual vanilla `SOLID` `MeshData`.

JVM properties:

- `-Dwhatoptimizations.nativeMesher=false` starts with Rust meshing disabled.
- `-Dwhatoptimizations.nativeMesher.sampleRate=1` samples every compile callback (values clamp to 1–64; default is 8).
- `-Dwhatoptimizations.nativeMesher.debug=true` starts F9 diagnostics enabled.
- `-Dwhatoptimizations.nativeRenderer=true` starts the experimental GL pass enabled.
- `-Dwhatoptimizations.nativeRenderer.rustGl=false` forces the LWJGL `glMultiDrawArrays` fallback instead of calling GL draw functions from Rust.

The F3 panel shows section callbacks, selected/completed/pending jobs, stale results, failures, average vanilla compile and native-stage times, and visible/tracked section counts. The icon is packaged at `src/main/resources/What.png` and referenced by `fabric.mod.json`.

## What the current renderer is — and is not

**Vanilla still builds and renders the real Minecraft meshes.** The optional native draw pass is a colored opaque-cube diagnostic overlay; it is not a full replacement for Minecraft's renderer and is not yet a performance claim. The current native eligibility rule is intentionally narrow: full opaque cubes with `MODEL` render shape, no fluid, and no block entity. State hashes are used for diagnostic colors, not the block atlas.

F9's normalized geometry check compares unit-aligned opaque-cube faces in vanilla's `SOLID` mesh to the native surface set and logs the first mismatching local block/face/state as well as the mismatch count. It does **not** validate UVs, biome tints, exact lighting/AO values, transparency, cutout/translucency layers, fluids, block entities, or other non-cube models. The mesher's AO/light data is still an approximation.

Those limits mean this branch is a real pipeline prototype and instrumentation pass, not the complete renderer rewrite. Replacing the vanilla renderer visibly still requires a model/material/atlas/tint/render-layer representation, correct lighting and transparency, special-renderer support, complete invalidation/lifecycle integration, and in-game visual testing. Until then, do not disable vanilla rendering.

## Test workflow

Use a disposable Minecraft 26.2 Fabric instance with Java 25 and no Sodium/Iris or other renderer replacements. Back up test worlds before experimenting.

1. Prefer the `minecraft-mod-jar` artifact from a fully successful **Build** workflow run. For Debian/Linux testing while the Windows runner has a checkout failure, use the `minecraft-mod-jar-linux-smoke` artifact produced by the independent wrapper build (that JAR contains the Linux x86-64 `.so` only). You can also use the attached Linux smoke-test JAR when supplied in chat.
2. Launch and travel through several sections. By default vanilla renders the world; Rust keeps shadow geometry for inspection.
3. Watch `logs/latest.log` for `Rust JNI smoke test completed successfully.`, `Rust async mesher:` lines, or native failure messages.
4. Press **F8** to view the diagnostic geometry overlay, **F9** to enable full sampling and vanilla-face comparisons, and **F7** to turn native meshing off completely.

The native GL pass has been built and tested by CI, but it still needs visual testing in the target game/runtime and graphics driver. OpenGL state preservation is deliberately conservative; if the pass causes trouble, leave it disabled or start with `-Dwhatoptimizations.nativeRenderer.rustGl=false`.

## Long-term direction

The architecture goal is a Rust-owned renderer for mesh building, visibility, batching, bounded memory management, and render-thread draw submission. Java remains a thin adapter for Minecraft-owned block/model/resource data and render lifecycle. See [Rust Renderer Architecture](docs/RUST_RENDERER_ARCHITECTURE.md) and [implementation phases](docs/PHASES.md).

The current phase provides the CPU job pipeline, native visibility kernel, bounded caches, diagnostic GL arena, runtime fallback keys, and initial debug comparison. It does not yet switch off vanilla's actual draw calls.
