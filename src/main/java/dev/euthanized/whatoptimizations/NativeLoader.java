package dev.euthanized.whatoptimizations;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Extracts the packaged JNI library to a temporary file because System.load
 * requires a filesystem path (not a jar resource URL).
 */
final class NativeLoader {
    private static volatile boolean loaded;

    private NativeLoader() {
    }

    static void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (NativeLoader.class) {
            if (loaded) {
                return;
            }

            String platform = platformToken();
            String architecture = architectureToken();
            String filename = libraryFilename(platform);
            String resource = "/natives/" + platform + "-" + architecture + "/" + filename;

            try (InputStream input = NativeLoader.class.getResourceAsStream(resource)) {
                if (input == null) {
                    throw new UnsatisfiedLinkError(
                            "No bundled native library for " + platform + "-" + architecture
                                    + " (expected resource " + resource + ")");
                }

                Path extracted = Files.createTempFile("what-optimizations-", "-" + filename);
                Files.copy(input, extracted, StandardCopyOption.REPLACE_EXISTING);
                extracted.toFile().deleteOnExit();
                System.load(extracted.toAbsolutePath().toString());
                loaded = true;
            } catch (IOException failure) {
                UnsatisfiedLinkError error = new UnsatisfiedLinkError(
                        "Could not extract native library resource " + resource);
                error.initCause(failure);
                throw error;
            }
        }
    }

    private static String platformToken() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "macos";
        }
        if (os.contains("linux")) {
            return "linux";
        }
        throw new UnsatisfiedLinkError("Unsupported operating system: " + os);
    }

    private static String architectureToken() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.equals("amd64") || arch.equals("x86_64")) {
            return "x86_64";
        }
        if (arch.equals("aarch64") || arch.equals("arm64")) {
            return "aarch64";
        }
        throw new UnsatisfiedLinkError("Unsupported native architecture: " + arch);
    }

    private static String libraryFilename(String platform) {
        return switch (platform) {
            case "windows" -> "what_optimizations_native.dll";
            case "macos" -> "libwhat_optimizations_native.dylib";
            default -> "libwhat_optimizations_native.so";
        };
    }
}
