# Implementation phases

## Phase 0 — scaffold and native loading

- [x] Fabric / Gradle scaffold for Minecraft 26.2 and Java 25.
- [x] Rust `cdylib`, JNI binding, platform library extraction/loading and smoke test.
- [x] Add the `What.png` icon to mod resources and `fabric.mod.json`.
- [x] Gradle 9.7.0 wrapper plus `nativeTest` and `nativeFormatCheck` tasks for CLI use.
- [x] Force-OpenGL preference mixins are active in this experiment branch, as required by the GL 3.3 path.
- [ ] Verify OpenGL backend selection and startup behavior in an actual Minecraft client.

## Phase 1 — native section snapshot and asynchronous meshing

- [x] Use exact Minecraft 26.2 sources to verify `SectionCompiler.compile(SectionPos, RenderSectionRegion, VertexSorting, SectionBufferBuilderPack)`.
- [x] Run the Rust shadow mesher at the compile RETURN hook while preserving the vanilla `Results` by default.
- [x] Java packs a 18×18×18 section plus border snapshot in native-endian direct buffers with compact palette IDs, mesh flags, and block/sky light.
- [x] Rust validates the ABI, culls opaque neighbors, greedy-merges eligible full-cube surfaces, and emits packed 16-byte vertices.
- [x] Bounded Rust worker pool; Rust jobs hold Rust-owned snapshots and never callback into the JVM.
- [x] Ticket handle, section key + generation, render-thread completion polling, stale-result rejection and explicit release.
- [x] Limits: 96 native jobs, 32 MiB live native mesh results, 64 Java pending tickets and 16 MiB CPU mesh cache.
- [x] Reuse snapshot workspace and greedy masks; prewarm Rayon; cap native pool to at most two workers.
- [x] Skip light sampling/JNI/Rust when no eligible interior opaque cube exists.
- [x] Rust regression tests for empty/isolated/adjacent/solid-box/diagonal shapes, face masks, padded neighbors, winding, malformed buffers and output limits.
- [x] Sync-versus-async Rust byte comparison and normalized vanilla `SOLID` geometry comparison for integer-aligned opaque-cube faces in F9 mode.
- [ ] Validate F9's vanilla geometry diff with in-game fixtures; it is intentionally not a comparison of UVs, materials, exact AO/light, or unsupported layers.
- [ ] Separate profile of integrated-server ticking remains necessary; renderer work does not optimize world simulation.

## Phase 2 — visibility kernel (initial prototype implemented)

- [x] Rust section-AABB frustum test exposed by JNI.
- [x] Six-neighbor open-face graph walk returns a compact visible-section index list.
- [x] Feed cache section metadata into the visibility call in the GL diagnostic pass.
- [ ] This is a sparse, coarse graph over cached sections, not a mature Sodium-equivalent occlusion graph. Test caves, doors, section boundaries and dynamic changes before relying on it.

## Phase 3 — GL upload and draw prototype (SUPERSEDED by Phase 5)

- [x] 64 MiB persistent OpenGL 3.3 vertex arena and a bounded first-fit/free-range allocator.
- [x] Render-thread uploads with `glBufferSubData` and `glMultiDrawArrays` over the native visible-section list.
- [x] F8 draw toggle / vanilla-visible fallback, F7 mesher kill switch and F3 telemetry.
- [x] Superseded: the pseudo-color overlay could not represent Minecraft's atlas/material rendering and has been removed in Phase 5.

## Phase 4 — optional Rust-side GL calls (SUPERSEDED by Phase 5)

- [x] Java resolved baseline GL function pointers through `glfwGetProcAddress`.
- [x] Rust executed the draw segment synchronously on the render thread.
- [x] Superseded: Phase 5 draws through vanilla's `RenderPipeline`/`RenderPass` API and needs no GL entry points, so the Rust GL path and the Force-OpenGL window/options mixins were removed.

## Phase 5 — real native terrain replacement (implemented; in-game verification pending)

- [x] Real baked-model geometry capture: takeover capture drives vanilla's own `ModelBlockRenderer`/`FluidRenderer` with a capturing `VertexConsumer` per layer (full cubes, partial/arbitrary models, rotations, state-dependent models, culling, atlas UVs, vertex color/tint, AO, packed light, offsets, biome tint, fluids); shadow capture memcpy's vanilla's finished `MeshData` for first builds.
- [x] WOM2 ABI + Rust v2 mesher: validation, per-layer passthrough (byte-identical), optional greedy merging into a 44-byte tiled stream (pixel-identical under the merge predicate), buffer pools, documented formats.
- [x] Real textures/materials: block atlas (`Sampler0`), lightmap (`Sampler2`), animation via atlas content updates, cutout alpha test, translucency, tint, fog, resource-reload-safe per-frame atlas binding, missing-texture fallback via the atlas.
- [x] Correct render layers: passthrough drawn with vanilla's `SOLID_TERRAIN`/`CUTOUT_TERRAIN`/`TRANSLUCENT_TERRAIN` pipelines (blend/depth/cull state identical to vanilla); opt-in tiled pipelines mirror them for the merged stream.
- [x] Ownership state machine (VANILLA/PENDING/ACTIVE/FAILED): vanilla geometry is only dropped after a native mesh is uploaded; stale-job rejection; render-thread upload; native draw in the correct phase (right after vanilla's terrain pass, same target/samplers); vanilla fallback for unsupported content (foreign atlas, capture errors, limits).
- [x] World unload / dimension change / shutdown handling (`LevelRenderer.close`), section recycling (`RenderSection.reset`), device-loss recreation, kill switch.
- [x] Diagnostics/controls: F8 native on/off (default ON), F9 capture-vs-vanilla vertex-exact debug diff, F7 kill switch, F3 telemetry. Normal mode draws real textures, never pseudo-colors.
- [x] Rust unit tests for the WOM2 mesher (22 tests: layout, validation, culling/merging, boundaries, negative/large coordinates, empty sections, stale rejection, index bounds, overflow, determinism).
- [ ] In-game verification (no game runtime in CI): F3 telemetry sanity, F8 toggle, chunk rebuild (F3+A), Nether/End dimension switch, resource reload (F3+F3), cave/indoor AO and smooth-lighting appearance, water translucency ordering, F9 debug diff = 0 mismatches.
- [ ] Translucency ordering within a section uses the shared sequential index buffer (vanilla sorts translucent quads per section); documented approximation.
- [ ] Greedy-merged tiled terrain is opt-in (`-Dwhatoptimizations.nativeRenderer.tiled=true`) because its custom shader cannot be exercised by CI.

See [Native Terrain Pipeline](NATIVE_TERRAIN_PIPELINE.md) for the design and [Rust Renderer Architecture](RUST_RENDERER_ARCHITECTURE.md) for the v1 ABI (retained for debug/tests).
