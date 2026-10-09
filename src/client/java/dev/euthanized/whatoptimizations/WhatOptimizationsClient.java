package dev.euthanized.whatoptimizations;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WhatOptimizationsClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("what-optimizations");

    @Override
    public void onInitializeClient() {
        LOGGER.info("What-Optimizations Phase 1 shadow mesher initialized (Minecraft 26.2 / OpenGL preference).");
        try {
            Native.hello();
            LOGGER.info("Rust JNI smoke test completed successfully.");
        } catch (LinkageError | RuntimeException failure) {
            // Keep Phase 0 usable for Java-side investigation even if a native
            // binary is missing or cannot be loaded on this host.
            LOGGER.error("Rust JNI smoke test failed; native rendering is unavailable.", failure);
        }
    }
}
