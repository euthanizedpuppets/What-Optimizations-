package dev.euthanized.whatoptimizations;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL44C;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Experimental opaque-cube overlay. It uses a persistent 64 MiB GL 3.3 buffer,
 * an explicit suballocator and one glMultiDrawArrays call per frame. The current
 * phase intentionally colors native cube geometry diagnostically: it does not
 * yet have Minecraft's atlas UVs/material layers for exact visual replacement.
 */
final class NativeOpenGLRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger("what-optimizations/gl");
    private static final int ARENA_BYTES = 64 * 1024 * 1024;
    private static final int GPU_VERTEX_STRIDE = 16;
    private static final int MAX_METADATA = 32_768;
    private static final int VISIBILITY_HEADER = 16;
    private static final ThreadLocal<ByteBuffer> SECTION_RECORDS = ThreadLocal.withInitial(
            () -> ByteBuffer.allocateDirect(VISIBILITY_HEADER + MAX_METADATA * 16).order(ByteOrder.nativeOrder()));
    private static final ThreadLocal<ByteBuffer> PLANE_BUFFER = ThreadLocal.withInitial(
            () -> ByteBuffer.allocateDirect(96).order(ByteOrder.nativeOrder()));
    private static final ThreadLocal<ByteBuffer> VISIBILITY_OUTPUT = ThreadLocal.withInitial(
            () -> ByteBuffer.allocateDirect(MAX_METADATA * 4).order(ByteOrder.nativeOrder()));
    private static final ThreadLocal<ByteBuffer> FIRSTS = ThreadLocal.withInitial(
            () -> ByteBuffer.allocateDirect(MAX_METADATA * Integer.BYTES).order(ByteOrder.nativeOrder()));
    private static final ThreadLocal<ByteBuffer> COUNTS = ThreadLocal.withInitial(
            () -> ByteBuffer.allocateDirect(MAX_METADATA * Integer.BYTES).order(ByteOrder.nativeOrder()));
    private static final boolean RUST_GL_ENABLED = Boolean.parseBoolean(
            System.getProperty("whatoptimizations.nativeRenderer.rustGl", "true"));
    private static final String[] RUST_GL_FUNCTIONS = {
            "glGetIntegerv", "glGetBooleanv", "glIsEnabled", "glUseProgram",
            "glBindVertexArray", "glBindBuffer", "glDepthFunc", "glDepthMask",
            "glBlendFuncSeparate", "glEnable", "glDisable", "glMultiDrawArrays"
    };
    private static volatile long[] rustGlProcedureCache;

    private static final Map<Long, Allocation> ALLOCATIONS =
            new LinkedHashMap<>(256, 0.75f, true);
    private static final TreeMap<Integer, Integer> FREE_RANGES = new TreeMap<>();
    private static boolean initialized;
    private static int program;
    private static int vao;
    private static int vbo;
    private static int mvpLocation;
    private static long totalGpuBytes;
    private static long peakGpuBytes;
    private static long lastErrorLog;

    private NativeOpenGLRenderer() {
    }

    static void draw() {
        if (GLFW.glfwGetCurrentContext() == 0L || !NativeLoader.isLoaded()) {
            return;
        }

        GLState state = GLState.capture();
        try {
            ensureInitialized();
            if (!initialized) {
                return;
            }

            List<NativeSectionMeshCache.MeshSnapshot> meshes = NativeSectionMeshCache.meshSnapshot();
            synchronizeMeshes(meshes);
            List<NativeSectionMeshCache.SectionMetadata> metadata = NativeSectionMeshCache.metadataSnapshot();
            if (metadata.isEmpty() || ALLOCATIONS.isEmpty()) {
                NativeRendererControls.setVisibilityCounts(0, metadata.size());
                return;
            }

            Minecraft minecraft = Minecraft.getInstance();
            Vec3 camera = minecraft.gameRenderer.mainCamera().position();
            Matrix4f mvp = makeMvp(camera);
            ByteBuffer records = SECTION_RECORDS.get();
            records.clear();
            records.putInt(metadata.size());
            records.position(VISIBILITY_HEADER);
            for (NativeSectionMeshCache.SectionMetadata section : metadata) {
                long key = section.sectionKey();
                records.putInt(unpackX(key));
                records.putInt(unpackY(key));
                records.putInt(unpackZ(key));
                records.put((byte) section.openFaces());
                records.put((byte) 0);
                records.putShort((short) 0);
            }
            records.position(0);

            ByteBuffer planeBuffer = PLANE_BUFFER.get();
            planeBuffer.clear();
            writeFrustumPlanes(mvp, planeBuffer);
            planeBuffer.flip();
            ByteBuffer visibilityOutput = VISIBILITY_OUTPUT.get();
            visibilityOutput.clear();
            int visibleCount = Native.visibleSections(
                    records,
                    planeBuffer,
                    visibilityOutput,
                    floorToInt(camera.x),
                    floorToInt(camera.y),
                    floorToInt(camera.z));

            ByteBuffer firsts = FIRSTS.get();
            ByteBuffer counts = COUNTS.get();
            firsts.clear();
            counts.clear();
            int draws = 0;
            if (visibleCount >= 0 && visibleCount <= metadata.size()) {
                for (int index = 0; index < visibleCount; index++) {
                    int visibleIndex = visibilityOutput.getInt(index * 4);
                    if (visibleIndex < 0 || visibleIndex >= metadata.size()) {
                        continue;
                    }
                    NativeSectionMeshCache.SectionMetadata section = metadata.get(visibleIndex);
                    Allocation allocation = ALLOCATIONS.get(section.sectionKey());
                    if (allocation == null || allocation.vertexCount == 0) {
                        continue;
                    }
                    firsts.putInt(allocation.offset / GPU_VERTEX_STRIDE);
                    counts.putInt(allocation.vertexCount);
                    draws++;
                }
            } else {
                // Visibility ABI failure fails open to drawing available meshes.
                for (Allocation allocation : new ArrayList<>(ALLOCATIONS.values())) {
                    if (allocation.vertexCount != 0) {
                        firsts.putInt(allocation.offset / GPU_VERTEX_STRIDE);
                        counts.putInt(allocation.vertexCount);
                        draws++;
                    }
                }
            }

            int visibleForOverlay = visibleCount >= 0 && visibleCount <= metadata.size()
                    ? visibleCount
                    : draws;
            NativeRendererControls.setVisibilityCounts(visibleForOverlay, metadata.size());
            if (draws == 0) {
                return;
            }
            firsts.flip();
            counts.flip();

            // Uniform setup must not leak a bound program into Rust. At native
            // draw time Minecraft's original GL state should still be current.
            GLState uniformState = GLState.capture();
            try {
                GL33C.glUseProgram(program);
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    FloatBuffer matrix = stack.mallocFloat(16);
                    mvp.get(matrix);
                    matrix.flip();
                    GL33C.glUniformMatrix4fv(mvpLocation, false, matrix);
                }
            } finally {
                uniformState.restore();
            }

            // Rust saves the Minecraft GL state itself, configures the pass,
            // issues glMultiDrawArrays, then restores all changed state.
            boolean rustDrawn = false;
            if (RUST_GL_ENABLED) {
                int status = Native.drawMultiDrawOpenGL(
                        program, vao, vbo, firsts, counts, draws, rustGlProcedures());
                if (status == 0) {
                    rustDrawn = true;
                } else {
                    LOGGER.debug("Rust OpenGL multidraw returned {}; using LWJGL fallback", status);
                }
            }
            if (!rustDrawn) {
                GL33C.glEnable(GL33C.GL_DEPTH_TEST);
                GL33C.glDepthFunc(GL33C.GL_LEQUAL);
                GL33C.glDepthMask(false);
                GL33C.glDisable(GL33C.GL_BLEND);
                GL33C.glDisable(GL33C.GL_CULL_FACE);
                GL33C.glUseProgram(program);
                GL33C.glBindVertexArray(vao);
                GL33C.glBindBuffer(GL33C.GL_ARRAY_BUFFER, vbo);
                GL33C.glMultiDrawArrays(
                        GL33C.GL_TRIANGLES, firsts.asIntBuffer(), counts.asIntBuffer());
            }
        } catch (RuntimeException | LinkageError failure) {
            long now = System.nanoTime();
            if (now - lastErrorLog > 5_000_000_000L) {
                lastErrorLog = now;
                LOGGER.warn("Native GL pass failed; vanilla renderer remains active", failure);
            }
        } finally {
            state.restore();
        }
    }

    private static long[] rustGlProcedures() {
        long[] cached = rustGlProcedureCache;
        if (cached != null) {
            return cached;
        }
        synchronized (NativeOpenGLRenderer.class) {
            cached = rustGlProcedureCache;
            if (cached == null) {
                long[] resolved = new long[RUST_GL_FUNCTIONS.length];
                boolean allAvailable = true;
                for (int i = 0; i < RUST_GL_FUNCTIONS.length; i++) {
                    resolved[i] = GLFW.glfwGetProcAddress(RUST_GL_FUNCTIONS[i]);
                    allAvailable &= resolved[i] != 0L;
                }
                if (allAvailable) {
                    rustGlProcedureCache = resolved;
                }
                cached = resolved;
            }
        }
        return cached;
    }

    private static Matrix4f makeMvp(Vec3 camera) {
        // In 26.2 the projection is managed as a GPU buffer slice instead of a
        // public RenderSystem Matrix4f getter. Camera supplies the active
        // view-rotation-projection matrix; world-space vertices get camera-relative
        // translation here (P * R * T(-camera)).
        return Minecraft.getInstance().gameRenderer.mainCamera()
                .getViewRotationProjectionMatrix(new Matrix4f())
                .translate((float) -camera.x, (float) -camera.y, (float) -camera.z);
    }

    private static void writeFrustumPlanes(Matrix4f m, ByteBuffer output) {
        float[][] rows = new float[4][4];
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 4; column++) {
                rows[row][column] = m.get(column, row);
            }
        }
        for (int axis = 0; axis < 3; axis++) {
            for (int direction : new int[]{1, -1}) {
                for (int component = 0; component < 4; component++) {
                    output.putFloat(rows[3][component] + direction * rows[axis][component]);
                }
            }
        }
    }

    private static void ensureInitialized() {
        if (initialized) {
            return;
        }
        GLState originalState = GLState.capture();
        try {
            initializeResources();
        } finally {
            originalState.restore();
        }
    }

    private static void initializeResources() {
        int vertexShader = compileShader(GL33C.GL_VERTEX_SHADER, """
                #version 330 core
                layout(location = 0) in vec3 Position;
                layout(location = 1) in vec4 Color;
                uniform mat4 uMvp;
                out vec4 vColor;
                void main() {
                    gl_Position = uMvp * vec4(Position, 1.0);
                    vColor = Color;
                }
                """);
        int fragmentShader = compileShader(GL33C.GL_FRAGMENT_SHADER, """
                #version 330 core
                in vec4 vColor;
                out vec4 FragColor;
                void main() {
                    FragColor = vColor;
                }
                """);
        if (vertexShader == 0 || fragmentShader == 0) {
            return;
        }
        program = GL33C.glCreateProgram();
        GL33C.glAttachShader(program, vertexShader);
        GL33C.glAttachShader(program, fragmentShader);
        GL33C.glLinkProgram(program);
        GL33C.glDeleteShader(vertexShader);
        GL33C.glDeleteShader(fragmentShader);
        if (GL33C.glGetProgrami(program, GL33C.GL_LINK_STATUS) == 0) {
            LOGGER.error("Native renderer shader link failed: {}", GL33C.glGetProgramInfoLog(program));
            GL33C.glDeleteProgram(program);
            program = 0;
            return;
        }
        mvpLocation = GL33C.glGetUniformLocation(program, "uMvp");
        vao = GL33C.glGenVertexArrays();
        vbo = GL33C.glGenBuffers();
        GL33C.glBindVertexArray(vao);
        GL33C.glBindBuffer(GL33C.GL_ARRAY_BUFFER, vbo);
        int glMajor = GL33C.glGetInteger(GL33C.GL_MAJOR_VERSION);
        int glMinor = GL33C.glGetInteger(GL33C.GL_MINOR_VERSION);
        boolean bufferStorageAvailable = glMajor > 4
                || (glMajor == 4 && glMinor >= 4)
                || GLFW.glfwExtensionSupported("GL_ARB_buffer_storage");
        boolean useImmutableStorage = bufferStorageAvailable
                && Boolean.getBoolean("whatoptimizations.nativeRenderer.bufferStorage");
        if (useImmutableStorage) {
            // GL 4.4 / ARB_buffer_storage fast path. DYNAMIC_STORAGE_BIT keeps
            // glBufferSubData legal; GL 3.3 always retains the mutable fallback.
            GL44C.glBufferStorage(
                    GL33C.GL_ARRAY_BUFFER, (long) ARENA_BYTES, GL44C.GL_DYNAMIC_STORAGE_BIT);
        } else {
            GL33C.glBufferData(GL33C.GL_ARRAY_BUFFER, (long) ARENA_BYTES, GL33C.GL_DYNAMIC_DRAW);
        }
        LOGGER.info(
                "Native GL {}.{} core path; ARB_buffer_storage available={} selected={}",
                glMajor, glMinor, bufferStorageAvailable, useImmutableStorage);
        GL33C.glEnableVertexAttribArray(0);
        GL33C.glVertexAttribPointer(0, 3, GL33C.GL_FLOAT, false, GPU_VERTEX_STRIDE, 0L);
        GL33C.glEnableVertexAttribArray(1);
        GL33C.glVertexAttribPointer(1, 4, GL33C.GL_UNSIGNED_BYTE, true, GPU_VERTEX_STRIDE, 12L);
        FREE_RANGES.put(0, ARENA_BYTES);
        initialized = true;
        LOGGER.info("Native GL 3.3 overlay ready; persistent arena={} MiB", ARENA_BYTES / (1024 * 1024));
    }

    private static int compileShader(int type, String source) {
        int shader = GL33C.glCreateShader(type);
        GL33C.glShaderSource(shader, source);
        GL33C.glCompileShader(shader);
        if (GL33C.glGetShaderi(shader, GL33C.GL_COMPILE_STATUS) == 0) {
            LOGGER.error("Native renderer shader compile failed: {}", GL33C.glGetShaderInfoLog(shader));
            GL33C.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    private static void synchronizeMeshes(List<NativeSectionMeshCache.MeshSnapshot> meshes) {
        Set<Long> live = new HashSet<>(meshes.size());
        int previousArrayBuffer = GL33C.glGetInteger(GL33C.GL_ARRAY_BUFFER_BINDING);
        try {
            GL33C.glBindBuffer(GL33C.GL_ARRAY_BUFFER, vbo);
            synchronizeMeshesBound(meshes, live);
        } finally {
            GL33C.glBindBuffer(GL33C.GL_ARRAY_BUFFER, previousArrayBuffer);
        }
    }

    private static void synchronizeMeshesBound(
            List<NativeSectionMeshCache.MeshSnapshot> meshes,
            Set<Long> live) {
        for (NativeSectionMeshCache.MeshSnapshot mesh : meshes) {
            live.add(mesh.sectionKey());
            int vertexCount = mesh.bytes().length / Native.MESH_VERTEX_STRIDE;
            if (vertexCount == 0) {
                freeAllocation(mesh.sectionKey());
                continue;
            }
            Allocation current = ALLOCATIONS.get(mesh.sectionKey());
            if (current != null && current.generation == mesh.generation()
                    && current.vertexCount == vertexCount) {
                continue;
            }
            freeAllocation(mesh.sectionKey());
            int bytesRequired = Math.multiplyExact(vertexCount, GPU_VERTEX_STRIDE);
            int offset = allocateRange(bytesRequired);
            if (offset < 0) {
                LOGGER.debug("Native GPU arena full; section {} stays vanilla-only", mesh.sectionKey());
                continue;
            }
            ByteBuffer converted = convertVertices(mesh);
            GL33C.glBufferSubData(GL33C.GL_ARRAY_BUFFER, offset, converted);
            ALLOCATIONS.put(mesh.sectionKey(),
                    new Allocation(offset, bytesRequired, vertexCount, mesh.generation()));
            totalGpuBytes += bytesRequired;
            peakGpuBytes = Math.max(peakGpuBytes, totalGpuBytes);
        }

        for (Long key : new ArrayList<>(ALLOCATIONS.keySet())) {
            if (!live.contains(key)) {
                freeAllocation(key);
            }
        }
    }

    private static ByteBuffer convertVertices(NativeSectionMeshCache.MeshSnapshot mesh) {
        byte[] packed = mesh.bytes();
        ByteBuffer source = ByteBuffer.wrap(packed).order(ByteOrder.nativeOrder());
        ByteBuffer target = ByteBuffer.allocateDirect(packed.length).order(ByteOrder.nativeOrder());
        int originX = unpackX(mesh.sectionKey());
        int originY = unpackY(mesh.sectionKey());
        int originZ = unpackZ(mesh.sectionKey());
        for (int offset = 0; offset < packed.length; offset += Native.MESH_VERTEX_STRIDE) {
            float x = originX + Byte.toUnsignedInt(packed[offset]);
            float y = originY + Byte.toUnsignedInt(packed[offset + 1]);
            float z = originZ + Byte.toUnsignedInt(packed[offset + 2]);
            int face = Byte.toUnsignedInt(packed[offset + 3]);
            int state = source.getInt(offset + 4);
            int sky = Byte.toUnsignedInt(packed[offset + 8]) & 15;
            int block = Byte.toUnsignedInt(packed[offset + 9]) & 15;
            int ao = Byte.toUnsignedInt(packed[offset + 10]) & 3;
            int hash = state * 0x9e3779b9;
            float faceShade = switch (face) {
                case 2 -> 0.55f;
                case 3 -> 1.0f;
                case 4, 5 -> 0.72f;
                default -> 0.82f;
            };
            float light = Math.max(sky, block) / 15.0f;
            float shade = faceShade * (0.25f + 0.75f * light) * (0.45f + 0.55f * ao / 3.0f);
            int red = clampColor(((hash >>> 16) & 0xff) * shade);
            int green = clampColor(((hash >>> 8) & 0xff) * shade);
            int blue = clampColor((hash & 0xff) * shade);
            target.putFloat(x).putFloat(y).putFloat(z);
            target.put((byte) red).put((byte) green).put((byte) blue).put((byte) 255);
        }
        target.flip();
        return target;
    }

    private static int clampColor(float value) {
        return Math.max(28, Math.min(255, Math.round(value)));
    }

    private static int allocateRange(int bytes) {
        while (true) {
            for (Map.Entry<Integer, Integer> range : FREE_RANGES.entrySet()) {
                if (range.getValue() >= bytes) {
                    int offset = range.getKey();
                    int remaining = range.getValue() - bytes;
                    FREE_RANGES.remove(offset);
                    if (remaining > 0) {
                        FREE_RANGES.put(offset + bytes, remaining);
                    }
                    return offset;
                }
            }
            if (!evictOldest()) {
                return -1;
            }
        }
    }

    private static boolean evictOldest() {
        Iterator<Map.Entry<Long, Allocation>> iterator = ALLOCATIONS.entrySet().iterator();
        if (!iterator.hasNext()) {
            return false;
        }
        Map.Entry<Long, Allocation> entry = iterator.next();
        Allocation allocation = entry.getValue();
        iterator.remove();
        releaseRange(allocation.offset, allocation.bytes);
        totalGpuBytes -= allocation.bytes;
        return true;
    }

    private static void freeAllocation(long key) {
        Allocation allocation = ALLOCATIONS.remove(key);
        if (allocation == null) {
            return;
        }
        releaseRange(allocation.offset, allocation.bytes);
        totalGpuBytes -= allocation.bytes;
    }

    private static void releaseRange(int offset, int bytes) {
        Map.Entry<Integer, Integer> lower = FREE_RANGES.floorEntry(offset);
        if (lower != null && lower.getKey() + lower.getValue() == offset) {
            offset = lower.getKey();
            bytes += lower.getValue();
            FREE_RANGES.remove(lower.getKey());
        }
        Map.Entry<Integer, Integer> higher = FREE_RANGES.ceilingEntry(offset);
        if (higher != null && offset + bytes == higher.getKey()) {
            bytes += higher.getValue();
            FREE_RANGES.remove(higher.getKey());
        }
        FREE_RANGES.put(offset, bytes);
    }

    private static int floorToInt(double value) {
        if (value <= Integer.MIN_VALUE) return Integer.MIN_VALUE;
        if (value >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) Math.floor(value);
    }

    private static int unpackX(long packed) {
        return (int) (packed >> 38);
    }

    private static int unpackY(long packed) {
        return (int) (packed << 52 >> 52);
    }

    private static int unpackZ(long packed) {
        return (int) (packed << 26 >> 38);
    }

    private record Allocation(int offset, int bytes, int vertexCount, long generation) {
    }

    private record GLState(
            int program,
            int vao,
            int arrayBuffer,
            int depthFunc,
            boolean depthEnabled,
            boolean blendEnabled,
            boolean cullEnabled,
            boolean depthMask,
            int blendSrcRgb,
            int blendDstRgb,
            int blendSrcAlpha,
            int blendDstAlpha) {
        static GLState capture() {
            return new GLState(
                    GL33C.glGetInteger(GL33C.GL_CURRENT_PROGRAM),
                    GL33C.glGetInteger(GL33C.GL_VERTEX_ARRAY_BINDING),
                    GL33C.glGetInteger(GL33C.GL_ARRAY_BUFFER_BINDING),
                    GL33C.glGetInteger(GL33C.GL_DEPTH_FUNC),
                    GL33C.glIsEnabled(GL33C.GL_DEPTH_TEST),
                    GL33C.glIsEnabled(GL33C.GL_BLEND),
                    GL33C.glIsEnabled(GL33C.GL_CULL_FACE),
                    GL33C.glGetBoolean(GL33C.GL_DEPTH_WRITEMASK),
                    GL33C.glGetInteger(GL33C.GL_BLEND_SRC_RGB),
                    GL33C.glGetInteger(GL33C.GL_BLEND_DST_RGB),
                    GL33C.glGetInteger(GL33C.GL_BLEND_SRC_ALPHA),
                    GL33C.glGetInteger(GL33C.GL_BLEND_DST_ALPHA));
        }

        void restore() {
            GL33C.glUseProgram(program);
            GL33C.glBindVertexArray(vao);
            GL33C.glBindBuffer(GL33C.GL_ARRAY_BUFFER, arrayBuffer);
            GL33C.glDepthFunc(depthFunc);
            GL33C.glDepthMask(depthMask);
            GL33C.glBlendFuncSeparate(blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha);
            setEnabled(GL33C.GL_DEPTH_TEST, depthEnabled);
            setEnabled(GL33C.GL_BLEND, blendEnabled);
            setEnabled(GL33C.GL_CULL_FACE, cullEnabled);
        }

        private static void setEnabled(int capability, boolean enabled) {
            if (enabled) {
                GL33C.glEnable(capability);
            } else {
                GL33C.glDisable(capability);
            }
        }
    }
}
