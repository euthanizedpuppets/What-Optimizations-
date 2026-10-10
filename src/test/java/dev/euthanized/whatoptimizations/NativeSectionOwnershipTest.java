package dev.euthanized.whatoptimizations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the ownership state machine's pure logic: section-key
 * conversion (world-space block origins, never shifted section indices),
 * coordinate transforms for negative/large coordinates, layer bits and the
 * lifecycle/fallback transitions.
 */
class NativeSectionOwnershipTest {
    @AfterEach
    void resetOwnership() {
        NativeSectionOwnership.clearAll();
    }

    private static long keyFor(int blockX, int blockY, int blockZ) {
        return BlockPos.asLong(blockX, blockY, blockZ);
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

    @Test
    void sectionKeyIsTheWorldSpaceBlockOrigin() {
        // Section keys are BlockPos.asLong(sectionPos.origin()) — world-space
        // block origins, NOT section indices shifted by 4 bits.
        int[][] origins = {
            {0, 0, 0},
            {16, 0, 16},
            {-16, -16, -16},
            {-3_000_000, -64, 29_999_984},
            {29_999_984, 320, -29_999_984},
            // BlockPos packing extremes: x/z in 26 bits, y in 12 bits.
            {33_554_431, -2_048, -33_554_432},
            {-33_554_432, 2_047, 33_554_431},
        };
        for (int[] origin : origins) {
            BlockPos blockPos = new BlockPos(origin[0], origin[1], origin[2]);
            SectionPos sectionPos = SectionPos.of(blockPos);
            long expected = keyFor(origin[0], origin[1], origin[2]);
            assertEquals(expected, NativeSectionOwnership.sectionKey(sectionPos),
                    "sectionKey(SectionPos) must equal BlockPos.asLong(origin)");
            assertEquals(expected, NativeSectionOwnership.sectionKey(blockPos),
                    "sectionKey(BlockPos) must equal BlockPos.asLong");
            assertEquals(expected, NativeSectionOwnership.sectionKey(sectionPos.asLong()),
                    "sectionKey(sectionNode) must resolve the node back to the origin");
        }
    }

    @Test
    void unpackingSectionKeysHandlesNegativeAndLargeCoordinates() {
        int[][] origins = {
            {0, 0, 0},
            {-1, -1, -1},
            {-3_000_000, -64, 29_999_984},
            {29_999_984, 320, -29_999_984},
            {33_554_431, -2_048, -33_554_432},
            {-33_554_432, 2_047, 33_554_431},
        };
        for (int[] origin : origins) {
            long key = keyFor(origin[0], origin[1], origin[2]);
            assertEquals(origin[0], unpackX(key), "x round trip for " + origin[0]);
            assertEquals(origin[1], unpackY(key), "y round trip for " + origin[1]);
            assertEquals(origin[2], unpackZ(key), "z round trip for " + origin[2]);
        }
    }

    @Test
    void sectionNodeRoundTripsThroughTheOrigin() {
        int sectionX = -187_500;
        int sectionY = -4;
        int sectionZ = 1_874_999;
        long node = SectionPos.asLong(sectionX, sectionY, sectionZ);
        SectionPos sectionPos = SectionPos.of(node);
        BlockPos origin = sectionPos.origin();
        assertEquals(SectionPos.blockToSectionCoord(origin.getX()), sectionX);
        assertEquals(SectionPos.blockToSectionCoord(origin.getY()), sectionY);
        assertEquals(SectionPos.blockToSectionCoord(origin.getZ()), sectionZ);
        assertEquals(keyFor(origin.getX(), origin.getY(), origin.getZ()),
                NativeSectionOwnership.sectionKey(node));
    }

    @Test
    void layerBitsAreDistinctAndComplete() {
        assertEquals(1, NativeSectionOwnership.LAYER_SOLID_BIT);
        assertEquals(2, NativeSectionOwnership.LAYER_CUTOUT_BIT);
        assertEquals(4, NativeSectionOwnership.LAYER_TRANSLUCENT_BIT);
        assertEquals(7, NativeSectionOwnership.ALL_LAYERS);
    }

    @Test
    void freshSectionsStartVanillaAndNotDrawable() {
        long key = keyFor(16, 0, 16);
        assertEquals(NativeSectionOwnership.State.VANILLA, NativeSectionOwnership.state(key));
        assertFalse(NativeSectionOwnership.isDrawable(key));
        assertFalse(NativeSectionOwnership.isUnsupported(key));
    }

    @Test
    void lifecycleTransitions() {
        long key = keyFor(32, 16, 32);
        assertTrue(NativeSectionOwnership.beginJob(key, 1L));
        assertEquals(NativeSectionOwnership.State.PENDING, NativeSectionOwnership.state(key));
        assertFalse(NativeSectionOwnership.isDrawable(key), "pending sections are drawn by vanilla");

        NativeSectionOwnership.activate(key, NativeSectionOwnership.ALL_LAYERS);
        assertEquals(NativeSectionOwnership.State.ACTIVE, NativeSectionOwnership.state(key));
        assertTrue(NativeSectionOwnership.isDrawable(key));
        assertEquals(1, NativeSectionOwnership.queuedRecompileCount(),
                "the first activation queues exactly one vanilla recompile (takeover)");
    }

    @Test
    void rebuildOfActiveSectionDoesNotRequeueRecompiles() {
        // Regression test for an infinite recompile loop: rebuilding an
        // already-ACTIVE section must not queue another vanilla recompile.
        long key = keyFor(48, 0, 48);
        NativeSectionOwnership.beginJob(key, 1L);
        NativeSectionOwnership.activate(key, NativeSectionOwnership.ALL_LAYERS);
        assertEquals(1, NativeSectionOwnership.queuedRecompileCount());

        // Simulate the recompile being applied, then a rebuild completing.
        NativeSectionOwnership.queuedRecompileCount(); // observation only
        long key2 = keyFor(64, 0, 64);
        NativeSectionOwnership.beginJob(key2, 1L);
        NativeSectionOwnership.activate(key2, NativeSectionOwnership.ALL_LAYERS);
        assertEquals(2, NativeSectionOwnership.queuedRecompileCount());

        // Rebuild of the first section (already ACTIVE): no new recompile.
        NativeSectionOwnership.beginJob(key, 2L);
        NativeSectionOwnership.activate(key, NativeSectionOwnership.ALL_LAYERS);
        assertEquals(2, NativeSectionOwnership.queuedRecompileCount(),
                "a rebuild of an ACTIVE section must not queue another recompile");
    }

    @Test
    void failureKeepsTheOldMeshDrawableAndRecovers() {
        long key = keyFor(80, 0, 80);
        NativeSectionOwnership.beginJob(key, 1L);
        NativeSectionOwnership.activate(key, NativeSectionOwnership.LAYER_SOLID_BIT);

        NativeSectionOwnership.markFailed(key, "test failure");
        assertEquals(NativeSectionOwnership.State.FAILED, NativeSectionOwnership.state(key));
        assertTrue(NativeSectionOwnership.isDrawable(key),
                "a failed section keeps drawing its uploaded mesh while vanilla rebuilds");

        // Recovery: vanilla recompiles (shadow capture), the job completes.
        NativeSectionOwnership.beginJob(key, 2L);
        NativeSectionOwnership.activate(key, NativeSectionOwnership.ALL_LAYERS);
        assertEquals(NativeSectionOwnership.State.ACTIVE, NativeSectionOwnership.state(key));
        assertFalse(NativeSectionOwnership.isUnsupported(key));
    }

    @Test
    void repeatedFailuresMakeTheSectionPermanentlyUnsupported() {
        long key = keyFor(96, 0, 96);
        for (int i = 0; i < 3; i++) {
            NativeSectionOwnership.markFailed(key, "failure " + i);
            assertFalse(NativeSectionOwnership.isUnsupported(key), "not yet unsupported at failure " + i);
        }
        assertTrue(NativeSectionOwnership.isUnsupported(key),
                "three consecutive failures leave the section with vanilla");
    }

    @Test
    void invalidationDropsTheMeshWithoutARecompile() {
        long key = keyFor(112, 0, 112);
        NativeSectionOwnership.beginJob(key, 1L);
        NativeSectionOwnership.activate(key, NativeSectionOwnership.ALL_LAYERS);
        NativeSectionOwnership.invalidate(key);
        assertEquals(NativeSectionOwnership.State.VANILLA, NativeSectionOwnership.state(key));
        assertFalse(NativeSectionOwnership.isDrawable(key));
    }

    @Test
    void removalDropsTheEntry() {
        long key = keyFor(128, 0, 128);
        NativeSectionOwnership.beginJob(key, 1L);
        NativeSectionOwnership.activate(key, NativeSectionOwnership.ALL_LAYERS);
        NativeSectionOwnership.remove(key);
        assertEquals(NativeSectionOwnership.State.VANILLA, NativeSectionOwnership.state(key));
        assertFalse(NativeSectionOwnership.isDrawable(key));
    }

    @Test
    void staleGenerationResultsAreRejected() {
        long key = keyFor(144, 0, 144);
        NativeSectionOwnership.beginJob(key, 1L);
        assertTrue(NativeSectionOwnership.isLatestGeneration(key, 1L));
        NativeSectionOwnership.beginJob(key, 2L);
        assertFalse(NativeSectionOwnership.isLatestGeneration(key, 1L),
                "a superseded job's result must be rejected as stale");
        assertTrue(NativeSectionOwnership.isLatestGeneration(key, 2L));
    }

    @Test
    void activationClampsTheLayerMask() {
        long key = keyFor(160, 0, 160);
        NativeSectionOwnership.activate(key, 0xFF);
        NativeSectionOwnership.Entry entry = NativeSectionOwnership.entry(key);
        assertEquals(NativeSectionOwnership.ALL_LAYERS, entry.activeLayers,
                "unknown layer bits are clamped away");
    }
}
