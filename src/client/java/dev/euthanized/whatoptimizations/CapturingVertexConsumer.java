package dev.euthanized.whatoptimizations;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.nio.ByteBuffer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

/**
 * A {@link BufferBuilder} that records the final per-vertex data vanilla's
 * chunk meshing produces (position, ABGR color bytes, UV, packed light) into a
 * fixed region of the WOM2 snapshot buffer instead of emitting it into a
 * {@link ByteBufferBuilder}.
 *
 * <p>Vanilla's model path ({@code VertexConsumer.putBlockBakedQuad}) and fluid
 * path ({@code FluidRenderer}) both funnel every vertex through the 10-argument
 * {@code addVertex} fast path, so overriding exactly that method captures the
 * complete, already-tinted/shaded/lit vertex stream with vanilla's own culling,
 * AO, biome tint, directional shading and light smoothing. The 3-argument
 * element-wise path is rejected loudly: if any renderer ever emits vertices
 * that way, the capture aborts and the section stays with vanilla instead of
 * silently producing wrong geometry.
 */
final class CapturingVertexConsumer extends BufferBuilder {
    private final ByteBuffer target;
    private final int baseOffset;
    private final int capacity;
    private int offset;
    private int vertexCount;
    private boolean overflow;

    CapturingVertexConsumer(ChunkSectionLayer layer, ByteBuffer target, int baseOffset, int capacity) {
        super(new ByteBufferBuilder(64), PrimitiveTopology.QUADS, DefaultVertexFormat.BLOCK);
        this.target = target;
        this.baseOffset = baseOffset;
        this.capacity = capacity;
    }

    @Override
    public void addVertex(
            float x,
            float y,
            float z,
            int color,
            float u,
            float v,
            int overlayCoords,
            int lightCoords,
            float nx,
            float ny,
            float nz) {
        if (this.overflow || this.vertexCount >= Native.MAX_VERTICES_PER_LAYER
                || this.offset + Native.BLOCK_VERTEX_STRIDE > this.capacity) {
            this.overflow = true;
            return;
        }
        int at = this.baseOffset + this.offset;
        this.target.putFloat(at, x);
        this.target.putFloat(at + 4, y);
        this.target.putFloat(at + 8, z);
        // Color bytes in memory are R, G, B, A (matching RGBA8_UNORM uploads).
        this.target.put(at + 12, (byte) (color >> 16));
        this.target.put(at + 13, (byte) (color >> 8));
        this.target.put(at + 14, (byte) color);
        this.target.put(at + 15, (byte) (color >> 24));
        this.target.putFloat(at + 16, u);
        this.target.putFloat(at + 20, v);
        // Packed light (block << 4 | sky << 20) stored as two native-endian shorts.
        this.target.putInt(at + 24, lightCoords);
        this.offset += Native.BLOCK_VERTEX_STRIDE;
        this.vertexCount++;
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        throw new UnsupportedOperationException(
                "Native section capture only supports the packed block-quad vertex path");
    }

    int vertexCount() {
        return this.vertexCount;
    }

    boolean overflow() {
        return this.overflow;
    }

    /** Used by the shadow capture, which copies whole vertex buffers at once. */
    void noteCopiedVertices(int count) {
        this.vertexCount = count;
        this.offset = count * Native.BLOCK_VERTEX_STRIDE;
    }
}
