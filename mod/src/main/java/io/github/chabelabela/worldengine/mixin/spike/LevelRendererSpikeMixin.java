package io.github.chabelabela.worldengine.mixin.spike;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.commands.RenderPass;
import io.github.chabelabela.worldengine.spike.client.SpikeClient;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * E3 (spike): draws ships inside vanilla's main render pass, after opaque terrain and solid features. Fabric's
 * {@code LevelRenderEvents.AFTER_OPAQUE_TERRAIN} fires at about this point but doesn't expose the open pass, and only
 * one render pass may be open at a time, so the hook captures the pass argument here.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererSpikeMixin {
    @Inject(method = "executeSolid", at = @At("TAIL"))
    private void worldengine$drawShips(CallbackInfo ci, @Local(argsOnly = true) RenderPass renderPass) {
        SpikeClient.drawShips(renderPass);
    }
}
