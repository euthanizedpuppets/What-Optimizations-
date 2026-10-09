package dev.euthanized.whatoptimizations;

import com.mojang.blaze3d.vertex.MeshData;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Debug-only normalized geometry check for the subset this prototype claims to
 * mesh: axis-aligned unit faces owned by full opaque cubes in the SOLID layer.
 * It intentionally ignores atlas UVs, render attributes, and all non-cube shapes.
 */
final class NativeMeshDifferential {
    private static final Logger LOGGER = LoggerFactory.getLogger("what-optimizations/diff");
    private static final int CELL_COUNT = 16 * 16 * 16;
    private static final int FACE_COUNT = 6;
    private static final int PACKED_VERTEX_STRIDE = Native.MESH_VERTEX_STRIDE;
    private static final float EPSILON = 0.0001f;

    private NativeMeshDifferential() {
    }

    static int[] captureVanillaFaces(
            SectionPos sectionPos,
            RenderSectionRegion region,
            SectionCompiler.Results vanillaResults) {
        int[] faces = new int[FACE_COUNT * CELL_COUNT];
        if (vanillaResults == null) {
            return faces;
        }

        MeshData mesh = vanillaResults.renderedLayers.get(ChunkSectionLayer.SOLID);
        if (mesh == null) {
            return faces;
        }

        MeshData.DrawState drawState = mesh.drawState();
        var positionElement = drawState.format().getElement("Position");
        if (positionElement == null || drawState.format().getVertexSize() < positionElement.offset() + 12) {
            return faces;
        }

        ByteBuffer vertices = mesh.vertexBuffer().duplicate().order(ByteOrder.nativeOrder());
        int vertexStride = drawState.format().getVertexSize();
        int positionOffset = positionElement.offset();
        int vertexCount = Math.min(drawState.vertexCount(), vertices.remaining() / vertexStride);
        BlockPos origin = sectionPos.origin();
        float[] xs = new float[4];
        float[] ys = new float[4];
        float[] zs = new float[4];
        BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos();

        // Minecraft MeshData is built from quads, four vertices per face.
        for (int vertex = 0; vertex + 3 < vertexCount; vertex += 4) {
            int base = vertices.position() + vertex * vertexStride + positionOffset;
            for (int corner = 0; corner < 4; corner++) {
                int offset = base + corner * vertexStride;
                xs[corner] = vertices.getFloat(offset);
                ys[corner] = vertices.getFloat(offset + 4);
                zs[corner] = vertices.getFloat(offset + 8);
            }

            int axis = faceAxis(xs, ys, zs);
            if (axis < 0) {
                continue;
            }

            float plane = axis == 0 ? xs[0] : axis == 1 ? ys[0] : zs[0];
            int roundedPlane = Math.round(plane);
            if (Math.abs(plane - roundedPlane) > EPSILON || roundedPlane < 0 || roundedPlane > 16) {
                continue;
            }

            float minX = min(xs), maxX = max(xs);
            float minY = min(ys), maxY = max(ys);
            float minZ = min(zs), maxZ = max(zs);
            if (axis != 0 && !isOneBlockWide(minX, maxX)) continue;
            if (axis != 1 && !isOneBlockWide(minY, maxY)) continue;
            if (axis != 2 && !isOneBlockWide(minZ, maxZ)) continue;

            // Determine the outward face direction from the first triangle's winding.
            float nx = (ys[1] - ys[0]) * (zs[2] - zs[0])
                    - (zs[1] - zs[0]) * (ys[2] - ys[0]);
            float ny = (zs[1] - zs[0]) * (xs[2] - xs[0])
                    - (xs[1] - xs[0]) * (zs[2] - zs[0]);
            float nz = (xs[1] - xs[0]) * (ys[2] - ys[0])
                    - (ys[1] - ys[0]) * (xs[2] - xs[0]);
            float direction = axis == 0 ? nx : axis == 1 ? ny : nz;
            if (Math.abs(direction) < EPSILON) {
                continue;
            }

            boolean positive = direction > 0.0f;
            int face = axis * 2 + (positive ? 1 : 0);
            int cellX = axis == 0
                    ? roundedPlane - (positive ? 1 : 0)
                    : clampCell((int) Math.floor((minX + maxX) * 0.5f));
            int cellY = axis == 1
                    ? roundedPlane - (positive ? 1 : 0)
                    : clampCell((int) Math.floor((minY + maxY) * 0.5f));
            int cellZ = axis == 2
                    ? roundedPlane - (positive ? 1 : 0)
                    : clampCell((int) Math.floor((minZ + maxZ) * 0.5f));
            if (!inside(cellX, cellY, cellZ)) {
                continue;
            }

            blockPos.set(origin.getX() + cellX, origin.getY() + cellY, origin.getZ() + cellZ);
            BlockState state = region.getBlockState(blockPos);
            if (!isNativeMeshable(state, region, blockPos)) {
                continue;
            }
            putFace(faces, face, cellX, cellY, cellZ, Block.getId(state));
        }

        return faces;
    }

    static void compare(
            long sectionKey,
            int[] vanillaFaces,
            byte[] nativeVertices) {
        if (vanillaFaces == null) {
            return;
        }

        int[] nativeFaces = captureNativeFaces(nativeVertices);
        int mismatchCount = 0;
        int firstSlot = -1;
        int firstVanilla = 0;
        int firstNative = 0;
        int faceSlots = Math.min(vanillaFaces.length, nativeFaces.length);
        for (int slot = 0; slot < faceSlots; slot++) {
            if (vanillaFaces[slot] != nativeFaces[slot]) {
                mismatchCount++;
                if (firstSlot < 0) {
                    firstSlot = slot;
                    firstVanilla = vanillaFaces[slot];
                    firstNative = nativeFaces[slot];
                }
            }
        }

        if (mismatchCount == 0) {
            LOGGER.debug(
                    "Vanilla/Rust opaque-cube geometry matches at section ({}, {}, {}) across {} face slots",
                    unpackX(sectionKey), unpackY(sectionKey), unpackZ(sectionKey), faceSlots);
            return;
        }

        int face = firstSlot / CELL_COUNT;
        int cell = firstSlot % CELL_COUNT;
        int x = cell & 15;
        int y = (cell >>> 4) & 15;
        int z = (cell >>> 8) & 15;
        LOGGER.warn(
                "Vanilla/Rust opaque-cube geometry mismatch at section ({}, {}, {}): {} face slots differ; first local block ({}, {}, {}), face {}, vanilla state {}, Rust state {} (0 means no face)",
                unpackX(sectionKey), unpackY(sectionKey), unpackZ(sectionKey),
                mismatchCount, x, y, z, faceName(face), stateFromStored(firstVanilla), stateFromStored(firstNative));
    }

    private static int[] captureNativeFaces(byte[] bytes) {
        int[] faces = new int[FACE_COUNT * CELL_COUNT];
        // Rust emits six triangle-list vertices per greedy quad.
        for (int quadOffset = 0; quadOffset + 6 * PACKED_VERTEX_STRIDE <= bytes.length;
                quadOffset += 6 * PACKED_VERTEX_STRIDE) {
            int face = Byte.toUnsignedInt(bytes[quadOffset + 3]);
            if (face < 0 || face >= FACE_COUNT) {
                continue;
            }
            int stateId = ByteBuffer.wrap(bytes, quadOffset + 4, 4)
                    .order(ByteOrder.nativeOrder()).getInt();
            int minX = 16, minY = 16, minZ = 16;
            int maxX = 0, maxY = 0, maxZ = 0;
            for (int i = 0; i < 6; i++) {
                int offset = quadOffset + i * PACKED_VERTEX_STRIDE;
                minX = Math.min(minX, Byte.toUnsignedInt(bytes[offset]));
                minY = Math.min(minY, Byte.toUnsignedInt(bytes[offset + 1]));
                minZ = Math.min(minZ, Byte.toUnsignedInt(bytes[offset + 2]));
                maxX = Math.max(maxX, Byte.toUnsignedInt(bytes[offset]));
                maxY = Math.max(maxY, Byte.toUnsignedInt(bytes[offset + 1]));
                maxZ = Math.max(maxZ, Byte.toUnsignedInt(bytes[offset + 2]));
            }

            if (face < 2) {
                for (int y = minY; y < maxY; y++) {
                    for (int z = minZ; z < maxZ; z++) {
                        putFace(faces, face, face == 0 ? minX : maxX - 1, y, z, stateId);
                    }
                }
            } else if (face < 4) {
                for (int x = minX; x < maxX; x++) {
                    for (int z = minZ; z < maxZ; z++) {
                        putFace(faces, face, x, face == 2 ? minY : maxY - 1, z, stateId);
                    }
                }
            } else {
                for (int x = minX; x < maxX; x++) {
                    for (int y = minY; y < maxY; y++) {
                        putFace(faces, face, x, y, face == 4 ? minZ : maxZ - 1, stateId);
                    }
                }
            }
        }
        return faces;
    }

    private static boolean isNativeMeshable(BlockState state, RenderSectionRegion region, BlockPos pos) {
        return state.isSolidRender()
                && Block.isShapeFullBlock(state.getShape(region, pos))
                && state.getRenderShape() == RenderShape.MODEL
                && state.getFluidState().isEmpty()
                && !state.hasBlockEntity();
    }

    private static int faceAxis(float[] xs, float[] ys, float[] zs) {
        boolean xPlane = nearAll(xs);
        boolean yPlane = nearAll(ys);
        boolean zPlane = nearAll(zs);
        int axes = (xPlane ? 1 : 0) + (yPlane ? 1 : 0) + (zPlane ? 1 : 0);
        if (axes != 1) {
            return -1;
        }
        return xPlane ? 0 : yPlane ? 1 : 2;
    }

    private static boolean nearAll(float[] values) {
        for (int i = 1; i < values.length; i++) {
            if (Math.abs(values[i] - values[0]) > EPSILON) {
                return false;
            }
        }
        return true;
    }

    private static boolean isOneBlockWide(float min, float max) {
        return Math.abs((max - min) - 1.0f) <= EPSILON
                && Math.abs(min - Math.round(min)) <= EPSILON
                && Math.abs(max - Math.round(max)) <= EPSILON;
    }

    private static float min(float[] values) {
        float result = Float.POSITIVE_INFINITY;
        for (float value : values) result = Math.min(result, value);
        return result;
    }

    private static float max(float[] values) {
        float result = Float.NEGATIVE_INFINITY;
        for (float value : values) result = Math.max(result, value);
        return result;
    }

    private static int clampCell(int value) {
        return Math.max(0, Math.min(15, value));
    }

    private static boolean inside(int x, int y, int z) {
        return x >= 0 && x < 16 && y >= 0 && y < 16 && z >= 0 && z < 16;
    }

    private static void putFace(int[] faces, int face, int x, int y, int z, int stateId) {
        if (!inside(x, y, z) || face < 0 || face >= FACE_COUNT) {
            return;
        }
        faces[face * CELL_COUNT + cellIndex(x, y, z)] = stateId + 1;
    }

    private static int cellIndex(int x, int y, int z) {
        return x + 16 * (y + 16 * z);
    }

    private static String faceName(int face) {
        return switch (face) {
            case 0 -> "west";
            case 1 -> "east";
            case 2 -> "down";
            case 3 -> "up";
            case 4 -> "north";
            case 5 -> "south";
            default -> "unknown";
        };
    }

    private static int stateFromStored(int value) {
        return value == 0 ? -1 : value - 1;
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
}
