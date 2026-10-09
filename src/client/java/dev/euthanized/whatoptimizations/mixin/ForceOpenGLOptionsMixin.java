package dev.euthanized.whatoptimizations.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Repairs the persisted 26.2 graphics-backend preference before Options loads.
 * The option key/target follows BackToGL's 26.2 implementation; verify against
 * generated 26.2 sources before depending on it across later game versions.
 */
@Mixin(targets = "net.minecraft.client.Options", remap = false)
public abstract class ForceOpenGLOptionsMixin {
    @Inject(method = "load", at = @At("HEAD"))
    private void whatOptimizations$preferOpenGLOption(CallbackInfo ci) {
        Path options = Path.of("options.txt");
        if (!Files.isRegularFile(options)) {
            return;
        }

        try {
            List<String> lines = new ArrayList<>(Files.readAllLines(options, StandardCharsets.UTF_8));
            boolean found = false;
            for (int index = 0; index < lines.size(); index++) {
                if (lines.get(index).startsWith("preferredGraphicsBackend:")) {
                    lines.set(index, "preferredGraphicsBackend:opengl");
                    found = true;
                }
            }
            if (!found) {
                lines.add("preferredGraphicsBackend:opengl");
            }
            Files.write(options, lines, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            // The early Window mixin still forces the process preference. Never
            // fail Minecraft startup only because options.txt is not writable.
        }
    }
}
