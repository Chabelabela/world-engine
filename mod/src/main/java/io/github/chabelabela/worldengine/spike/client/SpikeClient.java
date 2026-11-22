package io.github.chabelabela.worldengine.spike.client;

import com.mojang.renderpearl.api.commands.RenderPass;
import io.github.chabelabela.worldengine.WorldEngineMod;
import io.github.chabelabela.worldengine.spike.ShipPayloads;
import io.github.chabelabela.worldengine.spike.Spike;
import java.util.List;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.attribute.EnvironmentAttributeProbe;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Client entrypoint for the M1b spike, and the static hooks its client mixins call. Does nothing without
 * {@code -Dworldengine.spike=true}.
 */
public final class SpikeClient implements ClientModInitializer {
    private static ShipRenderer renderer;
    private static BackdropRenderer backdrop;
    private static PopLogger pops;

    /** From {@code LevelRendererSpikeMixin}, every frame inside the main render pass, after solid features. */
    public static void drawShips(RenderPass pass) {
        if (renderer != null) {
            renderer.draw(pass);
        }
    }

    /** From {@code LevelRendererSpikeMixin}, every frame at the start of the main pass, before terrain. */
    public static void drawBackdrop(RenderPass pass) {
        if (backdrop != null && E5Client.settings.has(ShipPayloads.E5Settings.FIX_BACKDROP)) {
            backdrop.draw(pass);
        }
    }

    /** From {@code GameRendererSpikeMixin}: this frame's level is drawn, the GUI isn't yet. */
    public static void afterLevelDrawn() {
        if (pops != null && renderer != null) {
            pops.onFrame(net.minecraft.client.Minecraft.getInstance(), renderer);
        }
    }

    /** From {@code PacketDecoderSpikeMixin}, on the netty thread: one received packet and its decoded size. */
    public static void packetReceived(net.minecraft.network.ConnectionProtocol protocol, net.minecraft.network.protocol.Packet<?> packet,
                                      int bytes) {
        if (renderer != null && protocol == net.minecraft.network.ConnectionProtocol.PLAY) {
            E6Client.countPacket(packet, bytes);
        }
    }

    /** From {@code GameRendererSpikeMixin}, every frame before the camera update (packets are handled by now). */
    public static void beforeFrame() {
        if (renderer != null) {
            E6Client.beforeFrame(net.minecraft.client.Minecraft.getInstance());
        }
    }

    public static void beforePlayerTick(LocalPlayer player) {
        if (renderer != null) {
            E5Client.beforePlayerTick(player);
        }
    }

    public static void afterPlayerTick(LocalPlayer player) {
        if (renderer != null) {
            E5Client.afterPlayerTick(player);
        }
    }

    public static void afterTeleport(LocalPlayer player, net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket packet) {
        if (renderer != null) {
            E5Client.afterTeleport(player, packet);
        }
    }

    public static List<VoxelShape> withShipCollisions(Entity entity, AABB area, List<VoxelShape> colliders) {
        return renderer == null ? colliders : E5Client.withShip(entity, area, colliders);
    }

    public static Vec3 cloudCamera(Vec3 camera) {
        return renderer == null ? camera : E5Client.cloudCamera(camera);
    }

    public static Vec3 probePosition(EnvironmentAttributeProbe probe, Vec3 position) {
        return renderer == null ? position : E5Client.probePosition(probe, position);
    }

    public static boolean biomesFrozen(EnvironmentAttributeProbe probe) {
        return renderer != null && E5Client.biomesFrozen(probe);
    }

    /** Any thread (chunk meshing workers). */
    public static boolean hideFromVanilla(BlockPos pos) {
        return E5Client.hideFromVanilla(pos);
    }

    @Override
    public void onInitializeClient() {
        {
            if (!Spike.ENABLED) {
                return;
            }
            ClientShipStore ship = new ClientShipStore();
            E5Client.init(ship);
            E6Client.init(ship);
            ClientPlayNetworking.registerGlobalReceiver(ShipPayloads.Snapshot.TYPE, (payload, context) -> ship.onSnapshot(payload));
            ClientPlayNetworking.registerGlobalReceiver(ShipPayloads.Delta.TYPE, (payload, context) -> ship.onDelta(payload));
            ClientPlayNetworking.registerGlobalReceiver(ShipPayloads.Forget.TYPE, (payload, context) -> ship.onForget(payload));
            ClientPlayNetworking.registerGlobalReceiver(ShipPayloads.Pose.TYPE, (payload, context) -> {
                ship.onPose(payload);
                E6Client.posesReceived++;
            });
            ClientPlayNetworking.registerGlobalReceiver(ShipPayloads.PeerRel.TYPE, (payload, context) -> E6Client.onPeerRel(payload));
            ClientPlayNetworking.registerGlobalReceiver(ShipPayloads.FrameSwitch.TYPE, (payload, context) -> E5Client.onFrameSwitch(payload));
            ClientPlayNetworking.registerGlobalReceiver(ShipPayloads.E5Settings.TYPE, (payload, context) -> E5Client.onSettings(payload));
            ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
                ship.clear();
                E5Client.reset();
                E6Client.reset();
            });
            renderer = new ShipRenderer(ship);
            backdrop = new BackdropRenderer();
            pops = new PopLogger();
            SpikeHud hud = new SpikeHud(ship, renderer);
            HudElementRegistry.addLast(WorldEngineMod.id("spike_hud"), hud::extractRenderState);
            ClientTickEvents.END_CLIENT_TICK.register(hud::endTick);
            ClientTickEvents.END_CLIENT_TICK.register(E6Client::endTick);
            ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> hud.screenOpened(screen));
        }
    }
}
