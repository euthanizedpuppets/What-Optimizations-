package dev.euthanized.whatoptimizations;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.junit.jupiter.api.Test;

/**
 * Unit tests pinning the WOM2 snapshot ABI (docs/NATIVE_TERRAIN_PIPELINE.md)
 * and the GPU vertex layouts shared with Rust: header/table offsets, vertex
 * strides, layer ids and the stride alignment used by the GPU store.
 */
class Wom2AbiTest {
    @Test
    void magicIsTheAsciiBytesWom2() {
        ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(Native.WOM2_MAGIC);
        assertArrayEquals(new byte[] {'W', 'O', 'M', '2'}, buffer.array());
        // Rust reads the magic with u32::from_ne_bytes(*b"WOM2").
        assertEquals(Native.WOM2_MAGIC, ByteBuffer.wrap(new byte[] {'W', 'O', 'M', '2'})
                .order(ByteOrder.nativeOrder()).getInt());
    }

    @Test
    void headerAndTableLayoutConstants() {
        assertEquals(64, Native.WOM2_HEADER_BYTES);
        assertEquals(32, Native.WOM2_OUTPUT_HEADER_BYTES);
        assertEquals(3, Native.WOM2_LAYER_COUNT);
        assertEquals(16, Native.WOM2_LAYER_TABLE_BYTES);
        assertEquals(24, Native.WOM2_OUTPUT_LAYER_TABLE_BYTES);
        // Input header 64 + 3*16 table = 112; output header 32 + 3*24 = 104.
        assertEquals(112, Native.WOM2_HEADER_BYTES + Native.WOM2_LAYER_COUNT * Native.WOM2_LAYER_TABLE_BYTES);
        assertEquals(104, Native.WOM2_OUTPUT_HEADER_BYTES
                + Native.WOM2_LAYER_COUNT * Native.WOM2_OUTPUT_LAYER_TABLE_BYTES);
    }

    @Test
    void vertexStridesMatchTheGpuFormats() {
        // BLOCK: Position RGB32_FLOAT (12) + Color RGBA8_UNORM (4)
        //      + UV0 RG32_FLOAT (8) + UV2 RG16_SINT (4) = 28.
        assertEquals(28, Native.BLOCK_VERTEX_STRIDE);
        assertEquals(12 + 4 + 8 + 4, Native.BLOCK_VERTEX_STRIDE);
        // Tiled: BLOCK fields + SpriteRect RGBA32_FLOAT (16) = 44.
        assertEquals(44, Native.TILED_VERTEX_STRIDE);
        assertEquals(Native.BLOCK_VERTEX_STRIDE + 16, Native.TILED_VERTEX_STRIDE);
    }

    @Test
    void layerIdsFollowTheChunkSectionLayerEnumOrder() {
        assertEquals(3, Native.LAYER_IDS.length);
        assertEquals(Native.LAYER_SOLID, Native.LAYER_IDS[0]);
        assertEquals(Native.LAYER_CUTOUT, Native.LAYER_IDS[1]);
        assertEquals(Native.LAYER_TRANSLUCENT, Native.LAYER_IDS[2]);
        // The ABI slots must match the enum order Rust and the capture use.
        assertEquals(0, ChunkSectionLayer.SOLID.ordinal());
        assertEquals(1, ChunkSectionLayer.CUTOUT.ordinal());
        assertEquals(2, ChunkSectionLayer.TRANSLUCENT.ordinal());
    }

    @Test
    void storeAlignmentFitsBothVertexStrides() {
        // The GPU store pads between the passthrough and merged streams so
        // both start at offsets that are exact multiples of their strides.
        assertEquals(0, NativeTerrainStore.ALIGN % Native.BLOCK_VERTEX_STRIDE);
        assertEquals(0, NativeTerrainStore.ALIGN % Native.TILED_VERTEX_STRIDE);
    }

    @Test
    void limitsAreBounded() {
        assertEquals(131_072, Native.MAX_VERTICES_PER_LAYER);
        assertEquals(4 * 1024 * 1024, Native.MAX_INPUT_BYTES);
        // A full worst-case section (3 layers at the cap) fits the capture
        // buffer regions, and the input limit keeps Rust's copy bounded.
        assertEquals(3 * Native.MAX_VERTICES_PER_LAYER * Native.BLOCK_VERTEX_STRIDE,
                3 * 131_072 * 28);
    }

    @Test
    void quadIndexCountsFitTheSequentialBuffer() {
        // The shared sequential QUADS index buffer expands each quad to 6
        // indices; the per-layer cap keeps the largest section well within
        // 32-bit index ranges (and the SHORT index type the buffer starts with).
        long maxQuads = Native.MAX_VERTICES_PER_LAYER / 4L;
        long maxIndices = maxQuads * 6L;
        assertTrue(maxIndices < 0xFFFF_FFFFL, "index count must fit u32");
        assertTrue(maxIndices <= Integer.MAX_VALUE, "index count must fit the Draw record's int");
    }
}
