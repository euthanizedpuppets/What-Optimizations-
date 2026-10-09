# Rust-Owned Chunk Renderer Architecture

## Goal

The long-term goal is a replacement chunk-rendering pipeline whose CPU-heavy renderer, mesh representation, visibility work, and bounded mesh/GPU-resource management are implemented in Rust. The current Phase 1 shadow mesher is a measurement prototype only; it must not be described as a rendering speedup because Minecraft still creates and draws the vanilla mesh.

"Rust-owned renderer" does not mean blindly porting Minecraft's entire object model or calling graphics APIs from arbitrary Rust workers. Java remains the thin Fabric/Minecraft integration layer where access to live game objects, model baking, registries, resource reloads, block entities, and renderer lifecycle is required. Rust owns the data-oriented algorithms and the renderer's hot-path data structures. The integration boundary should use bulk immutable snapshots and batch outputs, not JNI calls per block or vertex.

## Ownership boundaries

### Java / Minecraft adapter

- Capture a consistent section snapshot from Minecraft-owned objects while on the worker/thread allowed by the target method and API.
- Resolve block state IDs, model geometry, atlas UVs, material/render-layer identity, tint, emissive/full-bright behavior, and special-case model metadata into a versioned compact ABI.
- Notify native code when sections change, neighbor data becomes invalid, resources reload, dimensions/worlds unload, or GPU resources must be retired.
- Own Fabric mixins and lifecycle hooks. Validate the exact Minecraft 26.2 signature from generated sources rather than guessing names or descriptors.
- Make GPU upload/draw requests on the graphics/render thread only, with clear resource-lifetime rules.

### Rust renderer core

- Own compact section/vertex/material records, greedy or model-aware mesh building, face visibility, section bounds, CPU mesh caching, draw-batch planning, frustum/occlusion results, and bounded allocator metadata.
- Use reusable per-worker arenas/scratch buffers. Avoid per-face allocations, one-vertex JNI calls, duplicate copies, unbounded queues, and a second full copy of every mesh after upload.
- Reuse immutable material tables across sections. Material keys must be stable across a resource generation; clear/rebuild them on resource reload.
- Return packed arrays or write to caller-owned direct buffers in bulk. Every ABI has a magic/version, byte lengths, bounds checks, and graceful error paths.
- Keep the worker pool bounded and configurable. CPU-heavy mesh work must never starve Minecraft's main/render thread or the integrated server. Worker count must be chosen by measurements on low-core systems, not by desktop-class defaults.
- Keep all native memory accounted for. CPU caches, queued snapshots, pending uploads, and GPU allocations need explicit byte budgets, eviction policies, and shutdown/reload cleanup.

## GPU ownership

The eventual renderer should own its mesh storage and draw batching, with graphics commands issued only on the render thread while a valid graphics context is current. Rust may prepare upload packets or allocator plans on workers, but worker threads must not make OpenGL calls. Direct Rust-side graphics calls are optional and come later, only if their context/thread lifetime can be proven and all changed graphics state is restored.

Do not force a graphics backend during shadow mode. First verify which rendering backend and public/loader-facing integration path the target Minecraft 26.2 runtime actually uses. Any custom backend assumptions must be isolated behind an adapter and tested against the exact target build.

## Mesh fidelity before vanilla replacement

The current ABI represents conservative opaque cube faces. That is not enough to replace a complete Minecraft section renderer. Before switching visible geometry, the renderer must represent and validate:

- model triangles/quads and custom baked models, including non-cube shapes;
- block atlas UVs and texture/material identity;
- directional shading, approximate or parity-tested AO, block/sky light, and emissive vertices;
- biome tint and vertex color;
- opaque, cutout, translucent, and other target render layers with correct ordering/state;
- fluid surfaces, connected textures and other special geometry where applicable;
- block entities and renderer-owned dynamic effects, which may need a separate vanilla-compatible pass;
- neighbor invalidation at section edges, world changes, resource reloads, and removal/unload.

Unsupported geometry must remain on vanilla until parity is explicitly demonstrated. A partial mesh must never replace the full vanilla result for a section.

## CPU and memory budgets

Treat memory usage as a first-class performance metric, not an afterthought.

1. Snapshot memory: one reusable input workspace per active worker; store IDs/flags in compact arrays; skip empty/ineligible sections before sampling lighting where safe.
2. Native scratch: reuse palette/cell storage and greedy masks; avoid large pessimistic output reservations; collect allocation/bytes-per-section counters in benchmark builds.
3. Mesh retention: account cache bytes, evict least-recently-used CPU meshes, remove stale keys on section invalidation, and avoid copying the mesh again unless ownership requires it.
4. Pending work: cap queued snapshots and upload bytes. When saturated, merge/deduplicate stale section rebuilds or drop obsolete work instead of growing an unbounded backlog.
5. GPU storage: use a bounded suballocator with deferred frees safe for in-flight frames. Track allocated, live, fragmented, and peak bytes.
6. Threading: keep the native pool small by default on low-core CPUs. Measure chunk throughput and server-tick delay together; optimizing frame-time at the expense of integrated-server ticking is not a win.

## Rollout gates

### Gate A — shadow correctness and measurements

Keep vanilla as the visible renderer. Validate ABI, mesh counts, faces, winding, and error containment. Separate cold initialization from steady-state sample timings and report sampled/skipped/empty/failed counts. Measure on the same world/profile and record CPU, GC, server tick delay, chunk throughput, and memory.

### Gate B — complete material/model snapshot

Extend the ABI only after writing fixtures and tests for each new record type. Compare native output against vanilla for known block arrangements, lighting, tint, transparent/cutout layers, fluids, and modded/special models where supported.

### Gate C — Rust upload and draw prototype

On the render thread, upload only sections known to be representable, behind an opt-in JVM/system property. Keep vanilla meshes active as fallback for unsupported sections. Add explicit allocator budgets and deterministic resource cleanup. Do not enable automatic full replacement yet.

### Gate D — visible replacement in a clean test profile

Compare screenshots and geometry in open terrain, caves, edges, translucent scenes, resource reloads, teleports, dimension changes, and long sessions. Confirm no stale meshes, leaks, flicker, missing block entities, or render-thread violations. Verify performance over repeated runs with the same test conditions.

### Gate E — incremental removal of vanilla work

Only after parity and fallback coverage are demonstrated, bypass the vanilla compilation work for the specific supported mesh path. Expand supported render paths one category at a time. Keep a one-switch vanilla fallback until broad correctness and stability have been proven.

## Separate server-tick investigation

The renderer primarily targets client-side chunk compilation and drawing. Integrated-server tick stalls must be profiled independently; moving renderer work to Rust does not optimize entity AI, block entities, scheduled block/fluid ticks, world generation, or other server simulation. Record tick duration and client worker saturation alongside renderer metrics.
