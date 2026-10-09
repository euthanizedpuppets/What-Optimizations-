# Implementation phases

## Phase 0 — scaffold

- [x] Gradle/Fabric scaffold for Minecraft 26.2 and Java 25.
- [x] Rust cdylib using jni and rayon.
- [x] Platform library extraction and System.load.
- [x] Native.hello() smoke test.
- [x] OpenGL preference mixins adapted from the concrete BackToGL 26.2 implementation.
- [x] Cross-platform GitHub Actions native build and mod JAR packaging.
- [x] CI build passed.
- [ ] In-game startup and actual backend preference still need verification.

## Phase 1 — conservative Rust section meshing (shadow mode)

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
- [x] Unit tests: empty section, isolated cube, adjacent-cube greedy merge on all axes, solid 2×2×2 greedy merge, diagonal non-merge, padded-neighbor culling, six-face/local-coordinate validation, outward triangle winding, malformed header/total size, invalid palette index, insufficient output buffer.
- [ ] CI compile/tests must pass for this Phase 1 commit.
- [ ] Run Minecraft 26.2 and check native meshing logs with known arrangements (see README smoke-test checklist).
- [ ] Compare AO/light values against vanilla.
- [ ] Upload/draw, correct atlas UVs, biome tint, material/render-layer identity, non-cube model handling and block entity rendering are not implemented.

Safety boundary: native output is shadow data only. Vanilla is still the visible renderer. Do not use the native cache as a full section replacement mesh. Only full opaque model cubes without fluid and block entity are classified as meshable. Plants, stairs/slabs, transparent geometry, fluids, and custom models stay on vanilla.

## Phase 2 — visibility (not implemented)

Compute per-frame frustum visibility and investigate a section-connectivity/cave-culling graph. Return a compact visible-section list through a bulk JNI call. Validate in open terrain, caves, and water before changing rendering.

## Phase 3 — custom draw path (not implemented)

Implement GPU upload on the render thread, a bounded suballocator, and a custom OpenGL 3.3 core draw path. Preserve Minecraft graphics state and resource lifetimes. The current packed buffer is triangle-list data; OpenGL core does not support GL_QUADS.

## Phase 4 — optional Rust GL calls (stretch, not implemented)

Only after Java-owned upload and draw work. Resolve GL functions on the render thread, restore all mutated state and fail closed when thread/context checks are wrong. Never make GL calls from Rayon workers.
