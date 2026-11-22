package io.github.chabelabela.worldengine.mixin.spike;

import io.github.chabelabela.worldengine.spike.client.SpikeClient;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Spike (E5): the ship carries the local player. HEAD of {@code tick} runs after {@code commonTick} saved the old
 * position, so a carry applied here is interpolated by the camera like any movement. TAIL reports the player's
 * position relative to the ship.
 */
@Mixin(LocalPlayer.class)
abstract class LocalPlayerSpikeMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void worldengine$carry(CallbackInfo ci) {
        SpikeClient.beforePlayerTick((LocalPlayer) (Object) this);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void worldengine$report(CallbackInfo ci) {
        SpikeClient.afterPlayerTick((LocalPlayer) (Object) this);
    }
}
