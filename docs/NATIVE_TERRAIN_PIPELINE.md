# Native Terrain Pipeline (v2) — Design

This document describes the real native terrain renderer that replaces
Minecraft 26.2's CPU chunk-section meshing and drawing for supported sections.
It supersedes the Phase 1 shadow mesher (see `RUST_RENDERER_ARCHITECTURE.md`
for the v1 ABI, which is retained for debug comparison and tests).

All Minecraft API details below were verified against the Mojang-mapped
Minecraft 26.2 sources (exported by `.github/workflows/mc-sources.yml` to the
`mc-26.2-sources` branch).

## Goals

1. Supported sections are meshed into GPU-ready vertex buffers by Rust worker
   threads and drawn through Minecraft's own `RenderPipeline`/`RenderPass`
   machinery with the real block atlas, lightmap, tint, AO, fog and cutout /
   translucency state — not pseudo-colors.
2. Vanilla's per-vertex buffer emission, `MeshData` building and uber-buffer
   staging for supported sections are replaced. Model quad resolution, AO /
   light / tint computation stay on the Java compile worker (they require
   Minecraft objects); the per-vertex packing, greedy merging and upload are
   native and asynchronous.
3. A section is never invisible: vanilla geometry is only suppressed after a
   native mesh for that section has been produced, staged and uploaded.

## Pipeline overview

```
SectionCompiler.compile (vanilla worker thread)
  ├─ HEAD mixin: if the section is NATIVE_ACTIVE → takeover:
  │    capture real baked-model + fluid quads on this thread (vanilla
  │    ModelBlockRenderer / FluidRenderer with a capturing VertexConsumer),
  │    compute the VisGraph + block entities, submit the WOM2 snapshot to
  │    Rust, and return a Results with EMPTY renderedLayers (vanilla draws
  │    nothing for this section from now on).
  └─ RETURN mixin: shadow capture — copy vanilla's finished MeshData vertex
       buffers (already in the 28-byte BLOCK format) into a WOM2 snapshot and
       submit it to Rust. Used for the first build of every section and as
       the always-available fallback path.

Rust worker pool (rayon, ≤2 threads)
  └─ WOM2 snapshot → per-layer greedy meshing (conservative, visually safe)
       → packed 28-byte BLOCK vertices (section-local) per layer.

Render thread (LevelRenderer.render TAIL)
  ├─ poll completed tickets → validate → stage upload into per-layer
  │  UberGpuBuffer heaps (vanilla allocator + StagingBuffer)
  ├─ ownership: PENDING → ACTIVE (once staged) + queue a section re-dirty so
  │  the next compile takes over (vanilla mesh is released by vanilla itself)
  └─ flush staged uploads to the GPU (uploadStagedAllocations)

Render thread (ChunkSectionsToRender.renderGroup TAIL, inside the main pass)
  └─ for each visible section with an ACTIVE native mesh:
       build RenderPass.Draw records + DynamicUniforms.ChunkSectionInfo slices
       and draw with layer.pipeline() (SOLID_TERRAIN / CUTOUT_TERRAIN /
       TRANSLUCENT_TERRAIN), Sampler0 = block atlas, Sampler2 = lightmap,
       the shared sequential QUADS index buffer and bindDefaultUniforms.
```

## Vertex format

Exactly `DefaultVertexFormat.BLOCK` (28 bytes, `RGB32_FLOAT` Position,
`RGBA8_UNORM` Color, `RG32_FLOAT` UV0, `RG16_SINT` UV2 packed light):

| offset | type      | meaning                                             |
|--------|-----------|-----------------------------------------------------|
| 0      | f32 ×3    | position, section-local (0..16), float              |
| 12     | u8 ×4     | color, ABGR byte order (ARGB.toABGR), includes tint × AO × directional shade |
| 16     | f32 ×2    | UV0, block-atlas texture coordinates                |
| 24     | i16 ×2    | UV2 packed light: (block << 4), (sky << 4)          |

Quad vertex order is the baked-quad order (4 vertices per quad); the shared
sequential QUADS index buffer (`RenderSystem.getSequentialBuffer(QUADS)`)
expands each quad to the fan (0,1,2, 2,3,0) exactly like vanilla. Merged quads
synthesized by Rust use the canonical `FaceInfo` corner order per direction so
winding/culling matches vanilla.

## WOM2 snapshot ABI (Java → Rust, native byte order)

Input buffer (thread-local direct ByteBuffer, ≤ 11 MiB):

```
header (64 bytes)
  0  u32 magic "WOM2"
  4  u16 version = 2
  6  u16 headerBytes = 64
  8  u16 layerCount (always 3: SOLID, CUTOUT, TRANSLUCENT slots)
 10  u16 reserved
 12  i32 originX   (section world block origin — validation/telemetry only)
 16  i32 originY
 20  i32 originZ
 24  u32 layerTableOffset = 64
 28  u32 layerDataOffset
 32  u32 totalBytes
 36  u8  openFaces (6 bits, for the visibility kernel)
 37  u8  reserved
 38  u16 reserved
 40  u32 maxVerticesPerLayer (131072)
 44  u32 reserved ×5
layer table (3 × 16 bytes, slot order SOLID, CUTOUT, TRANSLUCENT)
  0  u8  layerId (0=SOLID, 1=CUTOUT, 2=TRANSLUCENT)
  1  u8  reserved
  2  u16 reserved
  4  u32 vertexCount (quads × 4)
  8  u32 vertexBytes = vertexCount × 28
 12  u32 dataOffset (absolute, into this buffer)
vertex data: vertexCount × 28 bytes per layer (BLOCK format, quad-major)
```

Output buffer (Rust → Java, native byte order):

```
header (32 bytes)
  0  u32 magic "WOM2"
  4  u16 version = 2
  6  u16 headerBytes = 32
  8  u16 layerCount (3)
 10  u16 reserved
 12  u32 layerTableOffset = 32
 16  u32 totalBytes
 20  u32 maxQuadsPerLayer (validation cap)
 24  u32 reserved ×2
layer table (3 × 16 bytes)
  0  u8  layerId
  1  u8  reserved
  2  u16 reserved
  4  u32 quadCount
  8  u32 vertexBytes = quadCount × 4 × 28
 12  u32 dataOffset
vertex data per layer (quad-major, BLOCK format)
```

Section keys are `BlockPos.asLong(sectionPos.origin())` — world-space block
origins, never shifted section indices. The origin is also stored in the
header so Rust can validate; vertices are section-local so negative and large
world coordinates need no special handling in the mesh itself (the camera-
relative transform happens in the vanilla shader via `ChunkPosition -
CameraBlockPos + CameraOffset`).

## Greedy meshing (Rust, per layer)

A captured quad is a *merge candidate* iff:

- its 4 vertices form an axis-aligned unit quad on an integer plane
  (exactly one constant axis; the other two span exactly [k, k+1] with
  integer k), and
- all 4 corner colors and all 4 corner packed lights are identical, and
- the quad's UVs span a full sprite rect with a consistent per-corner
  orientation (true for cube faces baked by `FaceBakery`).

Candidates are grouped by (normal axis, plane coordinate, color, light, UV
orientation) and merged into maximal rectangles. A merged rectangle keeps the
`FaceInfo` winding for its direction, positions at the rectangle extents,
the uniform corner attributes, and UVs taken from the corner unit quads of the
rectangle (exact for full-rect cube-face UVs, which interpolate linearly across
the run). Non-candidates pass through unchanged. This is conservative: merged
output is pixel-identical to the input for the merged regions, so it is safe
for every layer including TRANSLUCENT.

## Ownership state machine (per section key)

| state    | meaning                                                        |
|----------|----------------------------------------------------------------|
| VANILLA  | vanilla compiles and draws; shadow capture feeds Rust          |
| PENDING  | a native mesh job is in flight; vanilla still draws            |
| ACTIVE   | native mesh staged+uploaded; vanilla suppressed via takeover   |
| FAILED   | native failed; vanilla recompile queued, back to VANILLA       |

Transitions:

- VANILLA → PENDING: shadow capture submitted (worker thread, compile RETURN).
- PENDING → ACTIVE: job polled, validated, staged into the GPU store
  (render thread). A section re-dirty is queued once so the next compile
  takes over.
- ACTIVE → (takeover): compile HEAD captures quads and returns empty-layer
  Results; a new job is submitted; the old mesh keeps drawing until the new
  one is staged (no gap).
- ACTIVE/PENDING → FAILED: capture/mesh/upload error; re-dirty queued so
  vanilla recompiles (never leaves a section without geometry).
- any → VANILLA: kill switch / level unload; all sections re-dirtied so
  vanilla meshes are rebuilt.

Safety invariant: vanilla geometry for a section is only dropped (empty
`renderedLayers`) when state == ACTIVE, i.e. after a native mesh for that exact
section was staged and uploaded. The takeover compile completes at least one
frame after ACTIVE was set, and the native mesh has been drawable since the
frame after staging, so the native path always draws before vanilla's mesh
disappears.

Unsupported content (a quad whose sprite lives outside the block atlas, a
capture exceeding ABI limits, a model emitting element-wise vertices, any
exception) aborts the capture for that section and leaves it VANILLA.

## Lifecycle

- Resource reload: the block-atlas view is re-fetched every frame; vertex
  buffers and pipelines are unaffected (vanilla recompiles pipelines).
- Resize: render targets come from `ChunkSectionLayerGroup.outputTarget()` per
  frame.
- Device loss: the GPU store is recreated if `RenderSystem.getDevice()`
  changes identity; ownership is rebuilt from scratch (vanilla fallback).
- World unload / dimension change / shutdown: `LevelRenderer.close()` mixin
  drops all ownership, closes the GPU store and cancels native jobs.
- Section recycling: `SectionRenderDispatcher.RenderSection.reset()` mixin
  removes the store allocation + ownership entry for the recycled node.
- Translucency: native translucent sections draw in the TRANSLUCENT group
  pass with `TRANSLUCENT_TERRAIN` (blend + alpha cutout 0.1). Within-section
  back-to-front sorting is approximated by the shared sequential index buffer
  (vanilla sorts translucent quads per section); draw order per section is
  reversed like vanilla's translucent group. This is the one documented
  visual approximation.

## Controls & diagnostics

- F8: toggle native terrain replacement (default ON when the native library
  loads; `-Dwhatoptimizations.nativeRenderer=false` to start disabled).
- F7: kill switch — stop capturing, revert everything to vanilla.
- F9: debug mode — per-section vanilla-vs-native comparison (quad counts,
  bounding boxes, sampled vertex equality) via `NativeMeshDifferential`, plus
  sync/async mesh equality.
- F3 overlay: ownership state counts, GPU bytes, draw calls, vertices drawn,
  failures/fallbacks, average capture/mesh/upload times.

## Verification status

- Rust: `cargo test` (v1 + v2 meshers, tickets, visibility) and
  `cargo fmt --check` run locally and in CI on Linux/Windows/macOS.
- Java: full mod jar build against real Minecraft 26.2 in CI
  (`./gradlew build nativeTest nativeFormatCheck`).
- In-game visual verification cannot be performed in CI; see the final report
  for the exact checks to run (F3 telemetry, F8 toggle, chunk rebuild with
  F3+A, Nether/End dimension switch, resource reload with F3+F3).
