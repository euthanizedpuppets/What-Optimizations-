package dev.euthanized.whatoptimizations;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WhatOptimizationsClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("what-optimizations");

    @Override
    public void onInitializeClient() {
        LOGGER.info("What-Optimizations initialized (Minecraft 26.2 / Rust native terrain renderer).");
        LOGGER.info("Controls: F8 toggle native rendering (default ON), F9 capture-vs-vanilla debug, F7 kill switch.");
        LOGGER.info("Optional: -Dwhatoptimizations.nativeRenderer.tiled=true enables greedy-merged tiled terrain.");
        try {
            Native.hello();
            LOGGER.info("Rust JNI smoke test completed successfully.");
        } catch (LinkageError | RuntimeException failure) {
            // Keep the game usable even if a native binary is missing or
            // cannot be loaded on this host; every hook no-ops without it.
            LOGGER.error("Rust JNI smoke test failed; native rendering is unavailable.", failure);
        }
    }
}
