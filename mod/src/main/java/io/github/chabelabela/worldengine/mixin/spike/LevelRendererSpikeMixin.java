package io.github.chabelabela.worldengine.mixin.spike;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.commands.RenderPass;
import io.github.chabelabela.worldengine.spike.client.SpikeClient;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Spike (E3, E5): draws inside vanilla's main render pass, which Fabric's level-render events don't expose.
 * <ul>
 *   <li>HEAD of {@code executeSolid}: E5's stand-in planet, before terrain, so terrain covers it.</li>
 *   <li>TAIL of {@code executeSolid}: the ships, after opaque terrain and solid features.</li>
 *   <li>The clouds' camera position: E5 draws them as seen from the ship's surface position in the ship frame.</li>
 * </ul>
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererSpikeMixin {
    @Inject(method = "executeSolid", at = @At("HEAD"))
    private void worldengine$drawBackdrop(CallbackInfo ci, @Local(argsOnly = true) RenderPass renderPass) {
        SpikeClient.drawBackdrop(renderPass);
    }

    @Inject(method = "executeSolid", at = @At("TAIL"))
    private void worldengine$drawShips(CallbackInfo ci, @Local(argsOnly = true) RenderPass renderPass) {
        SpikeClient.drawShips(renderPass);
    }

    @ModifyArg(method = "prepareTranslucents", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/CloudRenderer;prepare(ILnet/minecraft/client/CloudStatus;FILnet/minecraft/world/phys/Vec3;JF)V"),
            index = 4)
    private Vec3 worldengine$cloudCamera(Vec3 camera) {
        return SpikeClient.cloudCamera(camera);
    }
}
