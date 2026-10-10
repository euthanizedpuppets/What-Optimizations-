package dev.euthanized.whatoptimizations;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Bulk JNI boundary. All buffers use the platform's native byte order. */
public final class Native {
    public static final int MESH_VERTEX_STRIDE = 16;

    /** WOM2 magic: the ASCII bytes "WOM2" read as a native-endian u32. */
    public static final int WOM2_MAGIC = 0x32_4D_4F_57;
    /** WOM2: vanilla BLOCK vertex (position 3f, color 4b ABGR, uv 2f, light 2x i16). */
    public static final int BLOCK_VERTEX_STRIDE = 28;
    /** WOM2 tiled vertex: BLOCK fields plus a per-vertex sprite rectangle (4f). */
    public static final int TILED_VERTEX_STRIDE = 44;
    public static final int WOM2_HEADER_BYTES = 64;
    public static final int WOM2_OUTPUT_HEADER_BYTES = 32;
    public static final int WOM2_LAYER_COUNT = 3;
    public static final int WOM2_LAYER_TABLE_BYTES = 16;
    public static final int WOM2_OUTPUT_LAYER_TABLE_BYTES = 24;
    public static final int LAYER_SOLID = 0;
    public static final int LAYER_CUTOUT = 1;
    public static final int LAYER_TRANSLUCENT = 2;
    /** WOM2 layer ids in fixed slot order (SOLID, CUTOUT, TRANSLUCENT). */
    public static final int[] LAYER_IDS = {LAYER_SOLID, LAYER_CUTOUT, LAYER_TRANSLUCENT};
    public static final int MAX_VERTICES_PER_LAYER = 131_072;
    /** Snapshots larger than this are rejected; the section stays vanilla. */
    public static final int MAX_INPUT_BYTES = 4 * 1024 * 1024;

    private Native() {
    }

    public static void hello() {
        NativeLoader.ensureLoaded();
        int status = hello0();
        if (status != 0) {
            throw new IllegalStateException("Rust Native.hello returned error code " + status);
        }
    }

    /** Synchronous ABI retained for deterministic debug comparison and tests. */
    public static int meshSection(ByteBuffer input, ByteBuffer output) {
        if (!direct(input) || !direct(output)) {
            return -2;
        }
        NativeLoader.ensureLoaded();
        return meshSection0(input.order(ByteOrder.nativeOrder()), output.order(ByteOrder.nativeOrder()));
    }

    /**
     * Copies a complete snapshot into Rust-owned memory and submits it to the
     * bounded worker pool. Positive return values are explicit release handles;
     * negative values are error codes.
     */
    public static long submitSection(ByteBuffer input, long sectionKey, long generation) {
        if (!direct(input)) {
            return -2;
        }
        NativeLoader.ensureLoaded();
        return submitSection0(input.order(ByteOrder.nativeOrder()), sectionKey, generation);
    }

    /**
     * Submits a WOM2 snapshot (captured vanilla vertex streams per layer).
     * Positive return values are explicit release handles; negative values are
     * error codes.
     */
    public static long submitSectionV2(ByteBuffer input, long sectionKey, long generation) {
        if (!direct(input)) {
            return -2;
        }
        NativeLoader.ensureLoaded();
        return submitSectionV2_0(input.order(ByteOrder.nativeOrder()), sectionKey, generation);
    }

    /**
     * Poll on the render thread. 0 means pending; positive means
     * vertexCount + 1 for v1 tickets and totalOutputBytes + 1 for WOM2
     * tickets; negative values are error codes.
     */
    public static int pollCompleted(long ticket, ByteBuffer output) {
        if (!direct(output)) {
            return -2;
        }
        NativeLoader.ensureLoaded();
        return pollCompleted0(ticket, output.order(ByteOrder.nativeOrder()));
    }

    /** Explicitly releases ticket/result memory. Never relies on the GC. */
    public static int release(long ticket) {
        NativeLoader.ensureLoaded();
        return release0(ticket);
    }

    /**
     * Native frustum/cave-graph visibility. Input/output records are documented
     * in docs/RUST_RENDERER_ARCHITECTURE.md and use native-endian direct buffers.
     */
    public static int visibleSections(
            ByteBuffer sections,
            ByteBuffer planes,
            ByteBuffer output,
            int cameraX,
            int cameraY,
            int cameraZ) {
        if (!direct(sections) || !direct(planes) || !direct(output)) {
            return -2;
        }
        NativeLoader.ensureLoaded();
        return visibleSections0(
                sections.order(ByteOrder.nativeOrder()),
                planes.order(ByteOrder.nativeOrder()),
                output.order(ByteOrder.nativeOrder()),
                cameraX,
                cameraY,
                cameraZ);
    }

    private static boolean direct(ByteBuffer buffer) {
        return buffer != null && buffer.isDirect();
    }

    private static native int hello0();

    private static native int meshSection0(ByteBuffer input, ByteBuffer output);

    private static native long submitSection0(ByteBuffer input, long sectionKey, long generation);

    private static native long submitSectionV2_0(ByteBuffer input, long sectionKey, long generation);

    private static native int pollCompleted0(long ticket, ByteBuffer output);

    private static native int release0(long ticket);

    private static native int visibleSections0(
            ByteBuffer sections,
            ByteBuffer planes,
            ByteBuffer output,
            int cameraX,
            int cameraY,
            int cameraZ);
}
