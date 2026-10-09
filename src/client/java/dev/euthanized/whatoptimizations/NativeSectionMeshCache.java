package dev.euthanized.whatoptimizations;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded CPU mesh cache plus recent section-connectivity metadata. */
final class NativeSectionMeshCache {
    private static final long MAX_BYTES = 16L * 1024L * 1024L;
    private static final int MAX_METADATA_SECTIONS = 32_768;

    private static final LinkedHashMap<Long, byte[]> MESHES = new LinkedHashMap<>(64, 0.75f, true);
    private static final LinkedHashMap<Long, SectionMetadata> METADATA =
            new LinkedHashMap<>(256, 0.75f, true);
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

    static synchronized void putMetadata(long sectionKey, int openFaces, long generation) {
        METADATA.put(sectionKey, new SectionMetadata(sectionKey, openFaces & 0x3f, generation));
        while (METADATA.size() > MAX_METADATA_SECTIONS) {
            Iterator<Long> iterator = METADATA.keySet().iterator();
            if (!iterator.hasNext()) {
                break;
            }
            iterator.next();
            iterator.remove();
        }
    }

    static synchronized ByteBuffer getReadOnly(long sectionKey) {
        byte[] mesh = MESHES.get(sectionKey);
        return mesh == null
                ? null
                : ByteBuffer.wrap(mesh).asReadOnlyBuffer().order(ByteOrder.nativeOrder());
    }

    /** Removes both geometry and metadata after a newer, unsampled compile. */
    static synchronized void invalidate(long sectionKey) {
        invalidateMesh(sectionKey);
        METADATA.remove(sectionKey);
    }

    /** Empty sections still participate in visibility metadata but have no mesh. */
    static synchronized void invalidateMesh(long sectionKey) {
        byte[] previous = MESHES.remove(sectionKey);
        if (previous != null) {
            cachedBytes -= previous.length;
        }
    }

    static synchronized List<SectionMetadata> metadataSnapshot() {
        return new ArrayList<>(METADATA.values());
    }

    static synchronized List<MeshSnapshot> meshSnapshot() {
        List<MeshSnapshot> result = new ArrayList<>(MESHES.size());
        for (Map.Entry<Long, byte[]> entry : MESHES.entrySet()) {
            SectionMetadata metadata = METADATA.get(entry.getKey());
            result.add(new MeshSnapshot(
                    entry.getKey(),
                    entry.getValue(),
                    metadata == null ? 0 : metadata.openFaces(),
                    metadata == null ? 0 : metadata.generation()));
        }
        return result;
    }

    static synchronized long cachedBytes() {
        return cachedBytes;
    }

    static synchronized int sectionCount() {
        return MESHES.size();
    }

    static synchronized int metadataCount() {
        return METADATA.size();
    }

    static synchronized void clear() {
        MESHES.clear();
        METADATA.clear();
        cachedBytes = 0;
    }

    record SectionMetadata(long sectionKey, int openFaces, long generation) {
    }

    record MeshSnapshot(long sectionKey, byte[] bytes, int openFaces, long generation) {
    }
}
