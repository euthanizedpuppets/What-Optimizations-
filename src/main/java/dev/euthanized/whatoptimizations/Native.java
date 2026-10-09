package dev.euthanized.whatoptimizations;

import java.nio.ByteBuffer;

/**
 * Bulk JNI surface. Future rendering calls must remain at section/frame
 * granularity rather than crossing JNI for individual blocks or vertices.
 */
public final class Native {
    public static final int MESH_VERTEX_STRIDE = 16;

    private Native() {
    }

    public static void hello() {
        NativeLoader.ensureLoaded();
        int status = hello0();
        if (status != 0) {
            throw new IllegalStateException("Rust Native.hello returned error code " + status);
        }
    }

    /**
     * Meshes one direct section snapshot into a caller-owned direct output
     * buffer. Returns vertex count on success, or a negative native error code.
     */
    public static int meshSection(ByteBuffer input, ByteBuffer output) {
        if (input == null || output == null || !input.isDirect() || !output.isDirect()) {
            return -2;
        }
        NativeLoader.ensureLoaded();
        return meshSection0(input, output);
    }

    private static native int hello0();

    private static native int meshSection0(ByteBuffer input, ByteBuffer output);
}
