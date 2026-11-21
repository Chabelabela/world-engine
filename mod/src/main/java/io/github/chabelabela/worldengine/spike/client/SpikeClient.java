package io.github.chabelabela.worldengine.spike.client;

import io.github.chabelabela.worldengine.WorldEngineMod;
import io.github.chabelabela.worldengine.spike.Spike;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;

/** Client entrypoint for the M1b spike. Does nothing without {@code -Dworldengine.spike=true}. */
public final class SpikeClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        if (!Spike.ENABLED) {
            return;
        }
        SpikeHud hud = new SpikeHud();
        HudElementRegistry.addLast(WorldEngineMod.id("spike_hud"), hud::extractRenderState);
        ClientTickEvents.END_CLIENT_TICK.register(hud::endTick);
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> hud.screenOpened(screen));
    }
}
