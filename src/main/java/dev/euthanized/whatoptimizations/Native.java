package dev.euthanized.whatoptimizations;

/**
 * Small JNI entry point for the Phase 0 native-loading smoke test.
 * Keep Java/native calls coarse-grained; rendering APIs will use bulk buffers.
 */
public final class Native {
    private Native() {
    }

    /**
     * Loads the platform library and asks Rust to print one greeting.
     *
     * @throws LinkageError if the native library cannot be extracted or loaded
     * @throws IllegalStateException if Rust reports a native error
     */
    public static void hello() {
        NativeLoader.ensureLoaded();
        int status = hello0();
        if (status != 0) {
            throw new IllegalStateException("Rust Native.hello returned error code " + status);
        }
    }

    private static native int hello0();
}
