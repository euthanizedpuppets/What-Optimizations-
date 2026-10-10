package dev.euthanized.whatoptimizations;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.StagingBuffer;
import com.mojang.blaze3d.vertex.TlsfAllocator;
import com.mojang.blaze3d.vertex.UberGpuBuffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GPU vertex storage for native section meshes.
 *
 * <p>One {@link UberGpuBuffer} heap per render layer (vanilla's own allocator
 * and staging machinery, mirroring {@code SectionRenderDispatcher}'s chunk
 * uber buffers) holds one allocation per (section, layer). An allocation
 * contains the passthrough stream (28-byte BLOCK vertices) followed by the
 * merged stream (44-byte tiled vertices), separated by alignment padding so
 * both streams start at offsets that are exact multiples of their vertex
 * strides.
 *
 * <p>All methods run on the render thread. Uploads are staged with
 * {@link UberGpuBuffer#addAllocation} and flushed with
 * {@link UberGpuBuffer#uploadStagedAllocations} in the same frame phase, so a
 * completed mesh becomes drawable on the next frame — exactly like vanilla's
 * chunk buffer uploads.
 */
final class NativeTerrainStore {
    private static final Logger LOGGER = LoggerFactory.getLogger("what-optimizations/store");

    private static final int HEAP_BYTES = 48 * 1024 * 1024;
    private static final int STAGING_BYTES = 32 * 1024 * 1024;
    /** lcm(28, 44) so both streams start at stride-aligned offsets. */
    private static final int ALIGN = 308;

    private record MeshKey(long sectionKey, int layerId) {
    }

    /** Everything needed to build draw calls for one (section, layer). */
    record DrawInfo(
            GpuBuffer buffer,
            int passthroughBaseVertex,
            int passthroughIndexCount,
            int mergedBaseVertex,
            int mergedIndexCount) {
    }

    private final EnumMap<ChunkSectionLayer, UberGpuBuffer<MeshKey>> vertexBuffers =
            new EnumMap<>(ChunkSectionLayer.class);
    private final Map<MeshKey, DrawInfo> drawInfos = new HashMap<>();
    private StagingBuffer stagingBuffer;
    private GpuDevice device;
    private boolean closed;

    private static final NativeTerrainStore INSTANCE = new NativeTerrainStore();

    static NativeTerrainStore get() {
        return INSTANCE;
    }

    private NativeTerrainStore() {
    }

    private static int layerId(ChunkSectionLayer layer) {
        return switch (layer) {
            case SOLID -> Native.LAYER_SOLID;
            case CUTOUT -> Native.LAYER_CUTOUT;
            case TRANSLUCENT -> Native.LAYER_TRANSLUCENT;
        };
    }

    private static int alignUp(int value, int alignment) {
        return (value + alignment - 1) / alignment * alignment;
    }

    /** Render thread: recreates GPU resources if the device was lost. */
    void ensureDevicePublic() {
        ensureDevice();
    }

    private void ensureDevice() {
        GpuDevice current = RenderSystem.getDevice();
        if (this.device == current && !this.closed) {
            return;
        }
        // Device changed (context loss / backend reinit): drop everything and
        // rebuild lazily. Ownership is rebuilt through recompiles.
        closeBuffers();
        this.device = current;
        this.stagingBuffer = StagingBuffer.create("What-Optimizations native terrain", current, STAGING_BYTES);
        for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
            this.vertexBuffers.put(
                    layer,
                    new UberGpuBuffer<>(
                            "what-optimizations/" + layer.label(),
                            GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                            HEAP_BYTES,
                            ALIGN,
                            this.stagingBuffer));
        }
        this.drawInfos.clear();
        this.closed = false;
    }

    private void closeBuffers() {
        for (UberGpuBuffer<MeshKey> buffer : this.vertexBuffers.values()) {
            buffer.close();
        }
        this.vertexBuffers.clear();
        if (this.stagingBuffer != null) {
            this.stagingBuffer.close();
            this.stagingBuffer = null;
        }
        this.drawInfos.clear();
        this.closed = true;
    }

    /**
     * Stages one (section, layer) mesh for upload. {@code passthroughBytes}
     * and {@code mergedBytes} are the exact stream contents; this method
     * inserts the alignment padding between them.
     */
    void stageUpload(long sectionKey, ChunkSectionLayer layer, byte[] passthroughBytes, byte[] mergedBytes) {
        ensureDevice();
        MeshKey key = new MeshKey(sectionKey, layerId(layer));
        int paddedPassthrough = alignUp(passthroughBytes.length, ALIGN);
        int total = paddedPassthrough + mergedBytes.length;
        if (total == 0) {
            // Nothing to draw for this layer; drop any previous allocation.
            remove(sectionKey, layer);
            return;
        }
        ByteBuffer upload = ByteBuffer.allocate(total).order(ByteOrder.nativeOrder());
        upload.put(passthroughBytes);
        for (int i = passthroughBytes.length; i < paddedPassthrough; i++) {
            upload.put((byte) 0);
        }
        upload.put(mergedBytes);
        upload.flip();

        UberGpuBuffer<MeshKey> uberBuffer = this.vertexBuffers.get(layer);
        if (!uberBuffer.addAllocation(key, null, upload)) {
            // Staging buffer full: flush what is staged and retry once.
            flushUploads();
            if (!uberBuffer.addAllocation(key, null, upload)) {
                LOGGER.debug("Could not stage native mesh upload for {} {}", sectionKey, layer);
                return;
            }
        }
        // Remember the stream sizes; the DrawInfo is built after the flush
        // when the allocation offset is known.
        this.pendingSizes.put(key, new int[] {
            passthroughBytes.length / Native.BLOCK_VERTEX_STRIDE,
            mergedBytes.length / Native.TILED_VERTEX_STRIDE
        });
    }

    private final Map<MeshKey, int[]> pendingSizes = new HashMap<>();

    /** Flushes all staged uploads to the GPU. Render thread only. */
    void flushUploads() {
        if (this.closed || this.vertexBuffers.isEmpty()) {
            return;
        }
        GpuDevice current = RenderSystem.getDevice();
        try (StagingBuffer.Uploader uploader = this.stagingBuffer.startUploading(current.createCommandEncoder())) {
            for (UberGpuBuffer<MeshKey> buffer : this.vertexBuffers.values()) {
                buffer.uploadStagedAllocations(current, uploader);
            }
        } catch (RuntimeException failure) {
            LOGGER.warn("Native terrain buffer upload failed", failure);
            return;
        }
        // Build draw info for everything staged this round.
        for (Map.Entry<MeshKey, int[]> entry : this.pendingSizes.entrySet()) {
            MeshKey key = entry.getKey();
            int[] sizes = entry.getValue();
            ChunkSectionLayer layer = layerOf(key.layerId());
            UberGpuBuffer<MeshKey> uberBuffer = this.vertexBuffers.get(layer);
            if (uberBuffer == null) {
                continue;
            }
            TlsfAllocator.Allocation allocation = uberBuffer.getAllocation(key);
            if (allocation == null) {
                continue;
            }
            long allocOffset = allocation.getOffsetFromHeap();
            int passthroughVerts = sizes[0];
            int mergedVerts = sizes[1];
            int paddedPassthrough = alignUp(passthroughVerts * Native.BLOCK_VERTEX_STRIDE, ALIGN);
            this.drawInfos.put(
                    key,
                    new DrawInfo(
                            uberBuffer.getGpuBuffer(allocation),
                            (int) (allocOffset / Native.BLOCK_VERTEX_STRIDE),
                            passthroughVerts / 4 * 6,
                            (int) ((allocOffset + paddedPassthrough) / Native.TILED_VERTEX_STRIDE),
                            mergedVerts / 4 * 6));
        }
        this.pendingSizes.clear();
    }

    private static ChunkSectionLayer layerOf(int layerId) {
        return switch (layerId) {
            case Native.LAYER_SOLID -> ChunkSectionLayer.SOLID;
            case Native.LAYER_CUTOUT -> ChunkSectionLayer.CUTOUT;
            default -> ChunkSectionLayer.TRANSLUCENT;
        };
    }

    DrawInfo drawInfo(long sectionKey, ChunkSectionLayer layer) {
        return this.drawInfos.get(new MeshKey(sectionKey, layerId(layer)));
    }

    void remove(long sectionKey, ChunkSectionLayer layer) {
        MeshKey key = new MeshKey(sectionKey, layerId(layer));
        this.pendingSizes.remove(key);
        this.drawInfos.remove(key);
        if (this.closed) {
            return;
        }
        UberGpuBuffer<MeshKey> uberBuffer = this.vertexBuffers.get(layer);
        if (uberBuffer != null) {
            uberBuffer.removeAllocation(key);
        }
    }

    void removeSection(long sectionKey) {
        for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
            remove(sectionKey, layer);
        }
    }

    /** Closes all GPU resources. Render thread only. */
    void close() {
        closeBuffers();
        this.pendingSizes.clear();
    }

    boolean isClosed() {
        return this.closed;
    }

    long gpuBytes() {
        long bytes = 0L;
        for (DrawInfo info : this.drawInfos.values()) {
            bytes += (long) info.passthroughIndexCount / 6 * 4 * Native.BLOCK_VERTEX_STRIDE
                    + (long) info.mergedIndexCount / 6 * 4 * Native.TILED_VERTEX_STRIDE;
        }
        return bytes;
    }

    int meshCount() {
        return this.drawInfos.size();
    }
}
