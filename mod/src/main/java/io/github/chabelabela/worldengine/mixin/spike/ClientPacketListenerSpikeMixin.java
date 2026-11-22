package io.github.chabelabela.worldengine.mixin.spike;

import io.github.chabelabela.worldengine.spike.client.SpikeClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Spike (E5): a server teleport just landed; on a hand-back the ship snaps to the pose the server used. */
@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerSpikeMixin {
    @Inject(method = "handleMovePlayer", at = @At("TAIL"))
    private void worldengine$afterTeleport(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            SpikeClient.afterTeleport(mc.player, packet);
        }
    }
}
