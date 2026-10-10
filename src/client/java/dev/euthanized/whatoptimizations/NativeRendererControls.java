package dev.euthanized.whatoptimizations;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runtime controls for the native terrain renderer.
 *
 * <ul>
 *   <li>F8 toggles native terrain replacement (default ON when the native
 *   library loads). Turning it off reverts every section to vanilla and queues
 *   vanilla recompiles.</li>
 *   <li>F9 toggles debug mode: the takeover capture runs alongside vanilla
 *   compilation and is compared vertex-by-vertex against vanilla's output.</li>
 *   <li>F7 is the kill switch: stops all capturing and meshing immediately.</li>
 * </ul>
 *
 * <p>Properties: {@code -Dwhatoptimizations.nativeRenderer=false} starts
 * disabled, {@code -Dwhatoptimizations.nativeRenderer.tiled=true} enables the
 * opt-in greedy-merged tiled pipeline (custom shader; not CI-verifiable),
 * {@code -Dwhatoptimizations.nativeMesher.debug=true} starts in debug mode.
 */
public final class NativeRendererControls {
    private static final Logger LOGGER = LoggerFactory.getLogger("what-optimizations/controls");

    private static volatile boolean rendererEnabled =
            Boolean.parseBoolean(System.getProperty("whatoptimizations.nativeRenderer", "true"));
    private static volatile boolean tiledEnabled =
            Boolean.parseBoolean(System.getProperty("whatoptimizations.nativeRenderer.tiled", "false"));
    private static boolean f8WasDown;
    private static boolean f9WasDown;
    private static boolean f7WasDown;

    private NativeRendererControls() {
    }

    public static void tick() {
        long window = GLFW.glfwGetCurrentContext();
        if (window == 0L) {
            return;
        }
        boolean f8 = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_F8) == GLFW.GLFW_PRESS;
        boolean f9 = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_F9) == GLFW.GLFW_PRESS;
        boolean f7 = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_F7) == GLFW.GLFW_PRESS;

        if (f8 && !f8WasDown) {
            rendererEnabled = !rendererEnabled;
            if (!rendererEnabled) {
                NativeSectionMesher.setEnabled(false);
            } else {
                NativeSectionMesher.setEnabled(true);
            }
            LOGGER.warn("Native terrain renderer {}; vanilla remains the fallback. Press F3+A to rebuild chunks.",
                    rendererEnabled ? "ENABLED" : "DISABLED");
        }
        if (f9 && !f9WasDown) {
            NativeSectionMesher.setDebugMode(!NativeSectionMesher.isDebugMode());
            LOGGER.info("Native capture-vs-vanilla debug comparison {}",
                    NativeSectionMesher.isDebugMode() ? "enabled" : "disabled");
        }
        if (f7 && !f7WasDown) {
            boolean nowEnabled = !NativeSectionMesher.isEnabled();
            NativeSectionMesher.setEnabled(nowEnabled);
            LOGGER.warn("Rust native meshing {}", nowEnabled ? "enabled" : "DISABLED (kill switch)");
        }

        f8WasDown = f8;
        f9WasDown = f9;
        f7WasDown = f7;
    }

    /**
     * Render thread, tail of {@code LevelRenderer.render}: polls finished
     * native jobs, uploads their meshes to the GPU store and applies queued
     * vanilla recompiles.
     */
    public static void onRenderTail() {
        if (!NativeLoader.isLoaded()) {
            return;
        }
        // Polls finished jobs, stages + flushes GPU uploads, flips ownership.
        NativeSectionMesher.pollCompleted();
        // Applies queued vanilla recompiles (activations, failures, kill
        // switch) even when no jobs are in flight.
        NativeSectionOwnership.applyQueuedRecompiles();
    }

    /** Render thread: level unload / shutdown. */
    public static void shutdown() {
        NativeTerrainRenderer.shutdown();
    }

    public static boolean rendererEnabled() {
        return rendererEnabled;
    }

    public static boolean tiledEnabled() {
        return tiledEnabled && rendererEnabled;
    }

    public static void setTiledEnabled(boolean enabled) {
        tiledEnabled = enabled;
    }
}
