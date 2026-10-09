# What-Optimizations-

A deliberately overengineered Minecraft Java 26.2 Fabric experiment: moving chunk-section CPU work into Rust through JNI.

## Current stage: Phase 1 — shadow mesher

The Phase 0 build scaffold passes GitHub Actions. Phase 1 now intercepts the verified 26.2 section compiler return path, snapshots each section plus a one-block border, and hands the snapshot to Rust.

- Java 25, Minecraft 26.2, Mojang's unobfuscated names, Fabric Loader 0.19.5.
- Rust cdylib with jni and a bounded Rayon worker pool.
- Bulk direct-buffer ABI: one input/output pair per section, never one JNI call per block or quad.
- An 18×18×18 snapshot containing state IDs, palette flags, and block/sky light values.
- Rust face occlusion, greedy coplanar rectangle merging, initial corner AO/light sampling, and a 16-byte vertex stride.
- A 16 MiB access-ordered CPU cache for native output.
- GitHub Actions compiles native code and packages the platform libraries inside the Fabric mod JAR.

The source-audit workflow runs Loom's genSources on the exact Minecraft 26.2 dependency. It verified this actual method signature:

    SectionCompiler.compile(SectionPos, RenderSectionRegion, VertexSorting, SectionBufferBuilderPack)

The mixin injects at RETURN and leaves the original vanilla mesh untouched.

## Important rendering limitations

This is intentionally a shadow implementation, not yet a visible replacement renderer. Vanilla still renders all geometry. Rust's output is cached for inspection and later upload/draw work.

Only full opaque model cubes without fluids or block entities are admitted to the native cube path. Plants, stairs/slabs and other non-full shapes, transparent blocks, fluids, special models, and block entities remain exclusively on vanilla's renderer. This avoids silently pretending a generic cube mesh can replace every Minecraft model.

The initial AO and light samples are approximate and require visual/numeric comparison with vanilla before the native geometry may be used for actual rendering. Block state IDs currently stand in as material keys; they do not yet encode block atlas UVs, biome tint, shader/render-layer identity, or special vertex attributes.

## First in-game smoke test

Use a disposable Minecraft 26.2 Fabric instance with Java 25 and no Sodium/Iris or other renderer replacements for the first run. Back up any test world before launching.

1. Download `minecraft-mod-jar` from the latest **successful** GitHub Actions Build run and put the JAR in the instance's `mods` folder.
2. Launch once, create or open a test world, and travel through a few chunk sections. Vanilla is still rendering the world; the Rust mesh is diagnostic shadow data only.
3. In `logs/latest.log`, look for `Rust JNI smoke test completed successfully.` and a line beginning `Rust shadow mesher:`. The latter reports completed sections, packed vertices, failures, and cache size.
4. Investigate any `Native shadow meshing failed`, `Rust returned native error`, or mixin/bootstrap errors before further work. If the extra shadow work causes severe hitching, add `-Dwhatoptimizations.nativeMesher=false` to the launcher's JVM arguments to disable the mesher while keeping the vanilla renderer active.

This first run is **integration validation, not a performance benchmark**: shadow meshing deliberately adds CPU work and does not yet replace any visible geometry.

## Get the build

Open the repository's Actions tab, choose the latest successful Build run, and download the minecraft-mod-jar artifact. Extract the artifact ZIP and put its JAR in the Minecraft 26.2 Fabric instance's mods directory. The JAR contains the native binaries produced by CI.

Local build prerequisites are Java 25, Gradle 9.7.0 for the resolved Loom 1.18.3 plugin, and Rust/Cargo. GitHub Actions performs builds for you.

## Compatibility

The mod prefers OpenGL because the proposed custom draw path targets OpenGL 3.3 core. Backend-selection mixins can conflict with other mods changing early graphics initialization. The section compiler hook is also likely to conflict with Sodium or other mods that replace vanilla chunk compilation. Do not combine them without an explicit compatibility layer.

## Next stages

- Phase 1: validate snapshot and packed mesh output in game against known block arrangements; then add correct render-layer/material/UV/tint handling and upload.
- Phase 2: Rust visibility and cave graph, still validated against vanilla.
- Phase 3: bounded GPU allocator and custom OpenGL draw path.
- Phase 4: optional Rust-side GL calls on the render thread only.
