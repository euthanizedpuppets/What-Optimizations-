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

## Phase 3 — GL upload and draw prototype (initial prototype implemented)

- [x] 64 MiB persistent OpenGL 3.3 vertex arena and a bounded first-fit/free-range allocator.
- [x] Render-thread uploads with `glBufferSubData` and `glMultiDrawArrays` over the native visible-section list.
- [x] State preservation around initialization, synchronization and drawing.
- [x] F8 draw toggle / vanilla-visible fallback, F7 mesher kill switch and F3 telemetry.
- [ ] The output is currently a diagnostic pseudo-color opaque-cube overlay, not Minecraft's atlas/material renderer. Vanilla's original meshes and draws are still active.
- [ ] Build a model/material/atlas/render-layer ABI before replacing vanilla geometry. Full cubes alone cannot represent stairs, slabs, custom models, tint, fluids, transparency or block entities.

## Phase 4 — optional Rust-side GL calls (initial prototype implemented)

- [x] Java resolves baseline GL function pointers through `glfwGetProcAddress` while the render context is current.
- [x] Rust executes the state-save/configure/`glMultiDrawArrays`/restore segment synchronously on the render thread; worker threads never call GL.
- [x] `-Dwhatoptimizations.nativeRenderer.rustGl=false` falls back to LWJGL dispatch.
- [ ] Validate state restoration and drawing across supported GL drivers and the exact 26.2 runtime. Do not treat CI compilation as an in-game graphics test.

## Remaining renderer-replacement work

To switch this from a diagnostic pipeline to an actual vanilla renderer replacement, add full baked-model geometry, texture atlas UVs, layer/material identity, tint, exact-enough light/AO and emissive handling, transparent and cutout ordering, fluids and special-renderer support. Keep per-section fallback until a whole section is known to be representable. Integrate resource-generation and upload retirement properly; only then bypass vanilla compile/draw work for supported content.

The target is intentionally ambitious, but the current code must be described as a working native pipeline prototype—not a complete drop-in renderer replacement or an established FPS/CPU improvement. See [Rust Renderer Architecture](RUST_RENDERER_ARCHITECTURE.md).
