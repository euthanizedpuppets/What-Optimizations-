package dev.euthanized.whatoptimizations;

import com.mojang.blaze3d.vertex.MeshData;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Debug-mode differential: compares the native takeover capture (vanilla's own
 * model/fluid tessellation with capturing consumers) against vanilla's compiled
 * {@link MeshData} for the same section and compile, vertex by vertex.
 *
 * <p>This validates the capture engine — culling, AO, tint, light, UVs and
 * offsets — against exactly what vanilla produced, so capture divergence is
 * caught loudly instead of silently producing wrong native geometry. Only
 * active in debug mode (F9).
 */
final class NativeMeshDifferential {
    private static final Logger LOGGER = LoggerFactory.getLogger("what-optimizations/diff");

    private static final ConcurrentHashMap<Long, Long> COMPARISONS = new ConcurrentHashMap<>();
    private static final AtomicLong MISMATCHES = new AtomicLong();
    private static final AtomicLong MATCHED_VERTICES = new AtomicLong();
    private static final AtomicLong LAST_MISMATCH_LOG = new AtomicLong();

    private NativeMeshDifferential() {
    }

    static void compareCaptures(
            SectionPos sectionPos,
            SectionCompiler.Results vanillaResults,
            NativeSectionCapture.CapturedSection captured) {
        try {
            ByteBuffer snapshot = captured.snapshot();
            int mismatchedLayers = 0;
            long matchedVertices = 0;
            for (Map.Entry<ChunkSectionLayer, MeshData> entry : vanillaResults.renderedLayers.entrySet()) {
                ChunkSectionLayer layer = entry.getKey();
                MeshData mesh = entry.getValue();
                int vanillaVertexCount = mesh.drawState().vertexCount();
                int capturedVertexCount = capturedVertexCount(snapshot, layer);
                if (vanillaVertexCount != capturedVertexCount) {
                    LOGGER.warn(
                            "Capture mismatch at {} layer {}: vanilla {} vertices, capture {} vertices",
                            sectionPos, layer, vanillaVertexCount, capturedVertexCount);
                    mismatchedLayers++;
                    continue;
                }
                if (vanillaVertexCount == 0) {
                    continue;
                }
                ByteBuffer vanilla = mesh.vertexBuffer();
                int byteCount = vanillaVertexCount * Native.BLOCK_VERTEX_STRIDE;
                if (vanilla.remaining() < byteCount) {
                    LOGGER.warn("Capture mismatch at {} layer {}: vanilla buffer too small", sectionPos, layer);
                    mismatchedLayers++;
                    continue;
                }
                int capturedOffset = capturedLayerOffset(snapshot, layer);
                int differences = 0;
                int firstDifference = -1;
                ByteBuffer vanillaSlice = vanilla.duplicate();
                vanillaSlice.limit(vanillaSlice.position() + byteCount);
                for (int i = 0; i < byteCount; i++) {
                    byte expected = vanillaSlice.get(i);
                    byte actual = snapshot.get(capturedOffset + i);
                    if (expected != actual) {
                        differences++;
                        if (firstDifference < 0) {
                            firstDifference = i;
                        }
                    }
                }
                if (differences > 0) {
                    mismatchedLayers++;
                    long count = MISMATCHES.incrementAndGet();
                    long previous = LAST_MISMATCH_LOG.get();
                    if (count - previous >= 64L) {
                        LAST_MISMATCH_LOG.set(count);
                        LOGGER.warn(
                                "Capture mismatch at {} layer {}: {} differing bytes (first at {} of {})",
                                sectionPos, layer, differences, firstDifference, byteCount);
                    }
                } else {
                    matchedVertices += vanillaVertexCount;
                }
            }
            COMPARISONS.merge(NativeSectionOwnership.sectionKey(sectionPos), 1L, Long::sum);
            MATCHED_VERTICES.addAndGet(matchedVertices);
            if (mismatchedLayers > 0) {
                MISMATCHES.addAndGet(mismatchedLayers);
            }
        } catch (RuntimeException failure) {
            LOGGER.debug("Capture comparison failed for {}", sectionPos, failure);
        }
    }

    private static int capturedVertexCount(ByteBuffer snapshot, ChunkSectionLayer layer) {
        int entry = Native.WOM2_HEADER_BYTES + layerSlot(layer) * Native.WOM2_LAYER_TABLE_BYTES;
        return snapshot.getInt(entry + 4);
    }

    private static int capturedLayerOffset(ByteBuffer snapshot, ChunkSectionLayer layer) {
        int entry = Native.WOM2_HEADER_BYTES + layerSlot(layer) * Native.WOM2_LAYER_TABLE_BYTES;
        return snapshot.getInt(entry + 12);
    }

    private static int layerSlot(ChunkSectionLayer layer) {
        return switch (layer) {
            case SOLID -> 0;
            case CUTOUT -> 1;
            case TRANSLUCENT -> 2;
        };
    }

    static long comparisons() {
        long total = 0L;
        for (long value : COMPARISONS.values()) {
            total += value;
        }
        return total;
    }

    static long mismatches() {
        return MISMATCHES.get();
    }

    static long matchedVertices() {
        return MATCHED_VERTICES.get();
    }
}
