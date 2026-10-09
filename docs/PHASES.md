# Implementation phases

## Phase 0 — scaffold

- [x] Gradle/Fabric scaffold for Minecraft 26.2 and Java 25.
- [x] Rust cdylib using jni and rayon.
- [x] Platform library extraction and System.load.
- [x] Prewarm the bounded Rayon worker pool during mod initialization instead of the first section callback.
- [x] Native.hello() smoke test.
- [x] Draft OpenGL preference mixins adapted from the concrete BackToGL 26.2 implementation (kept out of the active Phase 1 mixin list until GPU drawing exists).
- [x] Cross-platform GitHub Actions native build and mod JAR packaging.
- [x] CI build passed.
- [ ] In-game startup and actual backend preference still need verification.

## Phase 1 — conservative Rust section meshing (shadow mode)

- [x] Deferred the OpenGL preference mixins during shadow mode; JNI meshing should not alter graphics-backend selection or rewrite `options.txt`.

- [x] Generated exact Minecraft 26.2 sources with Loom genSources in CI.
- [x] Verified real target: SectionCompiler.compile(SectionPos, RenderSectionRegion, VertexSorting, SectionBufferBuilderPack).
- [x] Injected at compile RETURN, preserving the completed vanilla result.
- [x] Java creates an 18×18×18 direct-buffer snapshot with a unique state palette, raw block-state registry IDs, face flags, and block/sky light values.
- [x] Native ABI validates header, dimensions, palette IDs, byte offsets and output capacity.
- [x] JNI is section-granular and Rust panics are contained before returning to Java.
- [x] Rayon handles six face directions on its worker pool.
- [x] Rust culls faces adjacent to occluding states and greedily merges uniform coplanar faces.
- [x] Emits 16-byte triangle-list vertices compatible with OpenGL 3.3 topology.
- [x] Initial approximate AO/light corner sampling.
- [x] Bounded 16 MiB native output cache.
- [x] Default 1-in-8 shadow sampling and stage-time diagnostics to limit duplicated CPU work and measure snapshot, native meshing, and cache-copy costs.
- [x] Reuse snapshot arrays, palette lookup capacity, and palette scalar storage per section worker.
- [x] Reduce per-direction native output reservation and keep the fixed greedy mask on the stack.
- [x] Compute corner AO/light from one shared 3×3 neighborhood for each exposed face.
- [x] Cap Rayon to two helper threads to reduce competition with Minecraft's workers.
- [x] Skip light sampling/JNI/Rust for sections containing no eligible interior opaque cubes; invalidate any stale shadow-cache entry.
- [x] Unit tests: empty section, isolated cube, adjacent-cube greedy merge on all axes, solid 2×2×2 greedy merge, diagonal non-merge, padded-neighbor culling, six-face/local-coordinate validation, outward triangle winding, malformed header/total size, invalid palette index, insufficient output buffer.
- [ ] CI compile/tests must pass for the latest Phase 1 commit after worker-pool prewarming.
- [x] Run Minecraft 26.2 successfully: thousands of sampled sections, zero native failures, and cache use below the 16 MiB bound.
- [ ] Use per-stage timings to target remaining snapshot/state/light sampling and native meshing overhead.
- [ ] Profile integrated-server ticking separately; the current native section hook does not optimize game simulation ticks.
- [ ] Compare AO/light values against vanilla.
- [ ] Upload/draw, correct atlas UVs, biome tint, material/render-layer identity, non-cube model handling and block entity rendering are not implemented.

Safety boundary: native output is shadow data only. Vanilla is still the visible renderer. Do not use the native cache as a full section replacement mesh. Only full opaque model cubes without fluid and block entity are classified as meshable. Plants, stairs/slabs, transparent geometry, fluids, and custom models stay on vanilla.

## Phase 2 — visibility (not implemented)

Compute per-frame frustum visibility and investigate a section-connectivity/cave-culling graph. Return a compact visible-section list through a bulk JNI call. Validate in open terrain, caves, and water before changing rendering.

The long-term goal is a Rust-owned chunk-rendering pipeline with Java as a thin Minecraft adapter. See [Rust Renderer Architecture](RUST_RENDERER_ARCHITECTURE.md) for ownership boundaries, CPU/memory budgets, model/material parity requirements, and rollout gates.

## Phase 3 — custom draw path (not implemented)

Implement GPU upload on the render thread, a bounded suballocator, and a target-version-verified draw path behind an opt-in switch. Preserve Minecraft graphics state and resource lifetimes; do not assume OpenGL until the 26.2 runtime backend is verified. The current packed buffer is triangle-list data; OpenGL core does not support GL_QUADS.

## Phase 4 — optional Rust GL calls (stretch, not implemented)

Only after Java-owned upload and draw work. Resolve GL functions on the render thread, restore all mutated state and fail closed when thread/context checks are wrong. Never make GL calls from Rayon workers.
