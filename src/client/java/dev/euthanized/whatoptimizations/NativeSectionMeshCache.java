package dev.euthanized.whatoptimizations;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded CPU cache for Rust shadow meshes. Vanilla remains responsible for
 * visible rendering during Phase 1, so these bytes are diagnostic/future input.
 */
final class NativeSectionMeshCache {
    private static final long MAX_BYTES = 16L * 1024L * 1024L;

    private static final LinkedHashMap<Long, byte[]> MESHES = new LinkedHashMap<>(64, 0.75f, true);
    private static long cachedBytes;

    private NativeSectionMeshCache() {
    }

    static synchronized void put(long sectionKey, byte[] mesh) {
        byte[] previous = MESHES.remove(sectionKey);
        if (previous != null) {
            cachedBytes -= previous.length;
        }

        if (mesh == null || mesh.length == 0 || mesh.length > MAX_BYTES) {
            return;
        }

        MESHES.put(sectionKey, mesh);
        cachedBytes += mesh.length;

        Iterator<Map.Entry<Long, byte[]>> iterator = MESHES.entrySet().iterator();
        while (cachedBytes > MAX_BYTES && iterator.hasNext()) {
            Map.Entry<Long, byte[]> eldest = iterator.next();
            cachedBytes -= eldest.getValue().length;
            iterator.remove();
        }
    }

    static synchronized ByteBuffer getReadOnly(long sectionKey) {
        byte[] mesh = MESHES.get(sectionKey);
        return mesh == null ? null : ByteBuffer.wrap(mesh).asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
    }

    static synchronized void invalidate(long sectionKey) {
        byte[] previous = MESHES.remove(sectionKey);
        if (previous != null) {
            cachedBytes -= previous.length;
        }
    }

    static synchronized long cachedBytes() {
        return cachedBytes;
    }

    static synchronized int sectionCount() {
        return MESHES.size();
    }
}
