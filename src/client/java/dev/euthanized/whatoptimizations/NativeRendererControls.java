package dev.euthanized.whatoptimizations;

import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** F8 toggles the native draw pass, F9 toggles diagnostics, and F7 stops native meshing. */
public final class NativeRendererControls {
    private static final Logger LOGGER = LoggerFactory.getLogger("what-optimizations/controls");

    private static volatile boolean rendererEnabled =
            Boolean.getBoolean("whatoptimizations.nativeRenderer");
    private static volatile int visibleSections;
    private static volatile int totalSections;
    private static boolean f8WasDown;
    private static boolean f9WasDown;
    private static boolean f7WasDown;

    private NativeRendererControls() {
    }

    static {
        NativeSectionMesher.setForceFullCapture(rendererEnabled);
        NativeSectionMesher.setDebugMode(Boolean.getBoolean("whatoptimizations.nativeMesher.debug"));
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
            NativeSectionMesher.setForceFullCapture(rendererEnabled);
            LOGGER.warn("Native draw pass {}; vanilla renderer remains the fallback. {}",
                    rendererEnabled ? "ENABLED" : "DISABLED",
                    rendererEnabled ? "Use F3+A to rebuild chunks for full capture." : "Vanilla rendering active.");
        }
        if (f9 && !f9WasDown) {
            NativeSectionMesher.setDebugMode(!NativeSectionMesher.isDebugMode());
            LOGGER.info("Native async/sync mesher diagnostics {}",
                    NativeSectionMesher.isDebugMode() ? "enabled" : "disabled");
        }
        if (f7 && !f7WasDown) {
            NativeSectionMesher.setEnabled(!NativeSectionMesher.isEnabled());
            LOGGER.warn("Rust section meshing {}", NativeSectionMesher.isEnabled() ? "enabled" : "disabled");
        }

        f8WasDown = f8;
        f9WasDown = f9;
        f7WasDown = f7;
    }

    public static void onRenderTail() {
        NativeSectionMesher.pollCompleted();
        if (rendererEnabled) {
            NativeOpenGLRenderer.draw();
        }
    }

    public static boolean rendererEnabled() {
        return rendererEnabled;
    }

    static void setVisibilityCounts(int visible, int total) {
        visibleSections = visible;
        totalSections = total;
    }

    public static int visibleSections() {
        return visibleSections;
    }

    public static int totalSections() {
        return totalSections;
    }
}
