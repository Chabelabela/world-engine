package io.github.chabelabela.worldengine.spike.client;

import io.github.chabelabela.worldengine.WorldEngineMod;
import io.github.chabelabela.worldengine.spike.ShipPayloads;
import io.github.chabelabela.worldengine.spike.Spike;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.attribute.EnvironmentAttributeProbe;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.CardinalLighting;

/**
 * E5 (spike), client side of the handoff.
 * <ul>
 *   <li><b>Surface frame:</b> the ship pose advances one server tick per client tick ({@link ClientShipStore#tickPose}),
 *       right after vanilla saved the player's old position, and a player standing in the ship's box moves with it
 *       (so the camera interpolates the carry). The player collides with the ship's blocks at that pose.</li>
 *   <li><b>Reports:</b> where the player stands relative to the ship ({@link ShipPayloads.Rel}), when it changes.</li>
 *   <li><b>Frame switch:</b> the server sends {@link ShipPayloads.FrameSwitch} then teleports. When the teleport lands
 *       back on the surface, the ship snaps to the exact pose the server used.</li>
 *   <li><b>Ship frame:</b> the player is in the plot. Depending on {@link ShipPayloads.E5Settings}, the spike keeps
 *       drawing the ship (and hides vanilla's copy of the plot blocks), draws clouds and samples sky colours as if the
 *       camera were at the ship's surface position, and draws a stand-in planet.</li>
 * </ul>
 */
final class E5Client {
    static ShipPayloads.E5Settings settings = new ShipPayloads.E5Settings(0, "");
    /** Read from chunk-meshing worker threads (see {@link #hideFromVanilla}). */
    private static volatile boolean hidePlot;
    private static ClientShipStore ship;
    private static final ShipCollision COLLISION = new ShipCollision();
    private static ShipPayloads.FrameSwitch pendingSwitch;
    private static ShipPayloads.Rel lastSent;
    private static boolean frozenThisTick;
    static int carriedTicks;
    static int relSent;

    private E5Client() {
    }

    static void init(ClientShipStore store) {
        ship = store;
    }

    static void onSettings(ShipPayloads.E5Settings s) {
        boolean hide = s.has(ShipPayloads.E5Settings.FIX_RENDERER);
        if (hide != hidePlot) {
            hidePlot = hide;
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null) {
                BlockPos o = Spike.PLOT_ORIGIN;
                mc.level.setSectionRangeDirty(SectionPos.blockToSectionCoord(o.getX()), SectionPos.blockToSectionCoord(o.getY()),
                        SectionPos.blockToSectionCoord(o.getZ()), SectionPos.blockToSectionCoord(o.getX() + Spike.SHIP_X),
                        SectionPos.blockToSectionCoord(o.getY() + Spike.SHIP_Y), SectionPos.blockToSectionCoord(o.getZ() + Spike.SHIP_Z));
            }
        }
        settings = s;
        WorldEngineMod.LOGGER.info("[spike] E5 client settings: flags={} label=\"{}\"", s.flags(), s.label());
    }

    static void onFrameSwitch(ShipPayloads.FrameSwitch s) {
        pendingSwitch = s;
    }

    static void reset() {
        settings = new ShipPayloads.E5Settings(0, "");
        hidePlot = false;
        pendingSwitch = null;
        lastSent = null;
    }

    // -------------------------------------------------------------------------------------------
    // Frames
    // -------------------------------------------------------------------------------------------

    static boolean inShipFrame(Vec3 pos) {
        return Spike.inShipyard(pos.x, pos.z);
    }

    static Vec3 plotOrigin() {
        return Vec3.atLowerCornerOf(Spike.PLOT_ORIGIN);
    }

    /** The level position of the ship's cell (0, 0, 0) corner when its centre is at {@code centre}. */
    static Vec3 cellOrigin(Vec3 centre) {
        return centre.subtract(Spike.SHIP_X / 2.0, Spike.SHIP_Y / 2.0, Spike.SHIP_Z / 2.0);
    }

    static Vec3 plotCentre() {
        return plotOrigin().add(Spike.SHIP_X / 2.0, Spike.SHIP_Y / 2.0, Spike.SHIP_Z / 2.0);
    }

    /** Where {@code pos} (in the plot) would be in the surface frame, against the ship as drawn this frame. */
    static Vec3 surfaceEquivalent(Vec3 pos, float partialTick) {
        return pos.subtract(plotOrigin()).add(cellOrigin(ship.renderPos(partialTick)));
    }

    static float partialTick() {
        return Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
    }

    /**
     * Where the spike draws the ship this frame (its centre), or null to leave it to vanilla: in the ship frame
     * without {@code FIX_RENDERER}, vanilla draws the plot's real blocks.
     */
    static Vec3 drawCentre(Vec3 camera, float partialTick) {
        if (inShipFrame(camera)) {
            return settings.has(ShipPayloads.E5Settings.FIX_RENDERER) ? plotCentre() : null;
        }
        return ship.renderPos(partialTick);
    }

    // -------------------------------------------------------------------------------------------
    // Carry, collision, reports (from the LocalPlayer and Entity mixins)
    // -------------------------------------------------------------------------------------------

    static boolean aboard(Vec3 feet, Vec3 origin) {
        double x = feet.x - origin.x;
        double y = feet.y - origin.y;
        double z = feet.z - origin.z;
        return x >= 0 && x <= Spike.SHIP_X && z >= 0 && z <= Spike.SHIP_Z && y >= -0.5 && y <= Spike.SHIP_Y + 0.5;
    }

    /** {@code LocalPlayer.tick} HEAD, after {@code commonTick} saved the old position. */
    static void beforePlayerTick(LocalPlayer player) {
        if (ship == null || !ship.hasPose()) {
            return;
        }
        Vec3 before = ship.curPos();
        Vec3 delta = ship.tickPose();
        if (delta.lengthSqr() == 0 || !ship.axisAligned() || inShipFrame(player.position())) {
            return;
        }
        if (aboard(player.position(), cellOrigin(before))) {
            player.setPos(player.position().add(delta));
            carriedTicks++;
        }
    }

    /** {@code LocalPlayer.tick} TAIL: tells the server where the player stands relative to the ship. */
    static void afterPlayerTick(LocalPlayer player) {
        if (ship == null || !ship.hasPose() || !ship.has() || settings.flags() == 0 && settings.label().isEmpty()) {
            return;
        }
        boolean shipFrame = inShipFrame(player.position());
        Vec3 origin = shipFrame ? plotOrigin() : cellOrigin(ship.curPos());
        Vec3 rel = player.position().subtract(origin);
        boolean aboard = aboard(player.position(), origin);
        boolean changed = lastSent == null || lastSent.shipFrame() != shipFrame || lastSent.aboard() != aboard
                || Math.abs(lastSent.x() - rel.x) > 1e-6 || Math.abs(lastSent.y() - rel.y) > 1e-6
                || Math.abs(lastSent.z() - rel.z) > 1e-6;
        if (changed) {
            lastSent = new ShipPayloads.Rel(1, shipFrame, aboard, rel.x, rel.y, rel.z, ship.curTick());
            ClientPlayNetworking.send(lastSent);
            relSent++;
        }
    }

    /** {@code Entity.collide}: adds the ship's block shapes to the local player's colliders. */
    static List<VoxelShape> withShip(Entity entity, AABB area, List<VoxelShape> colliders) {
        Minecraft mc = Minecraft.getInstance();
        if (entity != mc.player || ship == null || !ship.has() || !ship.hasPose() || !ship.axisAligned()) {
            return colliders;
        }
        Vec3 origin = inShipFrame(entity.position()) ? plotOrigin() : cellOrigin(ship.curPos());
        List<VoxelShape> extra = COLLISION.shapes(ship, origin, area);
        if (extra.isEmpty()) {
            return colliders;
        }
        List<VoxelShape> all = new ArrayList<>(colliders.size() + extra.size());
        all.addAll(colliders);
        all.addAll(extra);
        return all;
    }

    /** {@code ClientPacketListener.handleMovePlayer} TAIL: a teleport just landed. */
    static void afterTeleport(LocalPlayer player, net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket packet) {
        if (!settings.label().isEmpty()) {
            WorldEngineMod.LOGGER.info(String.format(Locale.ROOT, "[spike] E5 client teleport id %d relatives %s -> (%.3f, %.3f, %.3f)",
                    packet.id(), packet.relatives(), player.getX(), player.getY(), player.getZ()));
        }
        if (pendingSwitch == null || ship == null) {
            return;
        }
        boolean shipFrame = inShipFrame(player.position());
        if (pendingSwitch.shipFrame() != shipFrame) {
            return;
        }
        long before = ship.curTick();
        boolean snapped = !shipFrame && ship.snapTo(pendingSwitch.poseTick());
        Vec3 origin = shipFrame ? plotOrigin() : cellOrigin(ship.curPos());
        Vec3 rel = player.position().subtract(origin);
        WorldEngineMod.LOGGER.info(String.format(Locale.ROOT,
                "[spike] E5 client switch to %s frame (%s): server pose tick %d, client pose tick %d -> %d%s, rel (%.4f, %.4f, %.4f), pending %d",
                shipFrame ? "ship" : "surface", settings.label(), pendingSwitch.poseTick(), before, ship.curTick(),
                shipFrame ? "" : snapped ? " (snapped)" : " (SNAP FAILED)", rel.x, rel.y, rel.z, ship.pendingPoses()));
        pendingSwitch = null;
    }

    // -------------------------------------------------------------------------------------------
    // Sky, clouds, vanilla's plot blocks (from the render mixins)
    // -------------------------------------------------------------------------------------------

    /** {@code CloudRenderer.prepare}'s camera position: in the ship frame, the ship's surface position. */
    static Vec3 cloudCamera(Vec3 camera) {
        if (ship == null || !ship.hasPose() || !settings.has(ShipPayloads.E5Settings.FIX_CLOUDS) || !inShipFrame(camera)) {
            return camera;
        }
        return surfaceEquivalent(camera, partialTick());
    }

    /**
     * {@code EnvironmentAttributeProbe.tick} HEAD, for the camera's probe: in the ship frame, keep the biome blend
     * of the last surface tick (time-of-day still applies). Returns the position the probe should use.
     */
    static Vec3 probePosition(EnvironmentAttributeProbe probe, Vec3 position) {
        Minecraft mc = Minecraft.getInstance();
        boolean shipFrame = inShipFrame(position);
        // After a hand-back the surface chunks (and their biomes) take a moment to arrive; until the chunks around the
        // camera are there, sampling would blend in the client's default biome. Keep the last blend instead.
        frozenThisTick = ship != null && ship.hasPose() && settings.has(ShipPayloads.E5Settings.FIX_SKY)
                && mc.gameRenderer != null && probe == mc.gameRenderer.mainCamera().attributeProbe()
                && (shipFrame || !chunksAround(mc, position));
        return frozenThisTick && shipFrame ? surfaceEquivalent(position, 1.0F) : position;
    }

    /** Whether the client has the 3×3 chunks around {@code pos}. */
    private static boolean chunksAround(Minecraft mc, Vec3 pos) {
        if (mc.level == null) {
            return false;
        }
        int cx = SectionPos.blockToSectionCoord(pos.x);
        int cz = SectionPos.blockToSectionCoord(pos.z);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (mc.level.getChunkSource().getChunk(cx + dx, cz + dz,
                        net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    static boolean biomesFrozen(EnvironmentAttributeProbe probe) {
        return frozenThisTick && probe == Minecraft.getInstance().gameRenderer.mainCamera().attributeProbe();
    }

    /** {@code RenderSectionRegion.getBlockState}: hide the plot's ship blocks from vanilla's mesher. Any thread. */
    static boolean hideFromVanilla(BlockPos pos) {
        if (!hidePlot) {
            return false;
        }
        int x = pos.getX() - Spike.PLOT_ORIGIN.getX();
        int y = pos.getY() - Spike.PLOT_ORIGIN.getY();
        int z = pos.getZ() - Spike.PLOT_ORIGIN.getZ();
        return x >= 0 && y >= 0 && z >= 0 && x < Spike.SHIP_X && y < Spike.SHIP_Y && z < Spike.SHIP_Z;
    }

    static String hudLine() {
        if (settings.label().isEmpty()) {
            return null;
        }
        Minecraft mc = Minecraft.getInstance();
        boolean shipFrame = mc.player != null && inShipFrame(mc.player.position());
        return String.format(Locale.ROOT, "E5 %s | %s frame | flags %d | pose tick %d, pending %d, catch-ups %d | carried %d ticks, rel sent %d",
                settings.label(), shipFrame ? "ship" : "surface", settings.flags(), ship.curTick(), ship.pendingPoses(),
                ship.catchUps(), carriedTicks, relSent);
    }

    /** The ship's collision boxes in cell coordinates, rebuilt when its blocks change. */
    static final class ShipCollision {
        private int builtVersion = -1;
        private final List<AABB> boxes = new ArrayList<>();

        List<VoxelShape> shapes(ClientShipStore ship, Vec3 origin, AABB area) {
            if (builtVersion != ship.version()) {
                rebuild(ship);
            }
            AABB local = area.move(-origin.x, -origin.y, -origin.z);
            List<VoxelShape> out = new ArrayList<>();
            for (AABB box : boxes) {
                if (box.intersects(local)) {
                    out.add(Shapes.create(box.move(origin.x, origin.y, origin.z)));
                }
            }
            return out;
        }

        private void rebuild(ClientShipStore ship) {
            boxes.clear();
            ShipBlockGetter getter = new ShipBlockGetter(ship, 15, CardinalLighting.DEFAULT);
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int y = 0; y < ship.sizeY(); y++) {
                for (int z = 0; z < ship.sizeZ(); z++) {
                    for (int x = 0; x < ship.sizeX(); x++) {
                        pos.set(x, y, z);
                        BlockState state = getter.getBlockState(pos);
                        if (state.isAir()) {
                            continue;
                        }
                        for (AABB box : state.getCollisionShape(getter, pos).toAabbs()) {
                            boxes.add(box.move(x, y, z));
                        }
                    }
                }
            }
            builtVersion = ship.version();
        }
    }
}
