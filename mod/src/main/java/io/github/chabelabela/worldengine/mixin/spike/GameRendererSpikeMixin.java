package io.github.chabelabela.worldengine.mixin.spike;

import io.github.chabelabela.worldengine.spike.client.SpikeClient;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Spike (E5): the frame's level has just been drawn, before post effects and the GUI. That's where vanilla takes its
 * own screenshots, and where E5 reads back each frame. (HUD callbacks run earlier, in the extract phase, while the
 * main target still holds the previous frame with its GUI.)
 */
@Mixin(GameRenderer.class)
abstract class GameRendererSpikeMixin {
    /** E6: the frame starts; packets handled since the last tick may have moved a peer the spike placed. */
    @Inject(method = "update", at = @At("HEAD"))
    private void worldengine$frameStart(CallbackInfo ci) {
        SpikeClient.beforeFrame();
    }

    @Inject(method = "tryTakeScreenshotIfNeeded", at = @At("HEAD"))
    private void worldengine$levelDrawn(CallbackInfo ci) {
        SpikeClient.afterLevelDrawn();
    }
}
