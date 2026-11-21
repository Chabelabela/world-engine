package io.github.chabelabela.worldengine.spike.client;

import io.github.chabelabela.worldengine.WorldEngineMod;
import io.github.chabelabela.worldengine.spike.Spike;
import net.fabricmc.api.ClientModInitializer;
import io.github.chabelabela.worldengine.spike.ShipPayloads;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;

/** Client entrypoint for the M1b spike. Does nothing without {@code -Dworldengine.spike=true}. */
public final class SpikeClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        if (!Spike.ENABLED) {
            return;
        }
        ClientShipStore ship = new ClientShipStore();
        ClientPlayNetworking.registerGlobalReceiver(ShipPayloads.Snapshot.TYPE, (payload, context) -> ship.onSnapshot(payload));
        ClientPlayNetworking.registerGlobalReceiver(ShipPayloads.Delta.TYPE, (payload, context) -> ship.onDelta(payload));
        ClientPlayNetworking.registerGlobalReceiver(ShipPayloads.Forget.TYPE, (payload, context) -> ship.onForget(payload));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ship.clear());
        SpikeHud hud = new SpikeHud(ship);
        HudElementRegistry.addLast(WorldEngineMod.id("spike_hud"), hud::extractRenderState);
        ClientTickEvents.END_CLIENT_TICK.register(hud::endTick);
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> hud.screenOpened(screen));
    }
}
