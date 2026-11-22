package io.github.chabelabela.worldengine.spike;

import io.github.chabelabela.worldengine.WorldEngineMod;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * E5 (spike): the surface ↔ ship handoff (Q4).
 * <ul>
 *   <li><b>Flight:</b> a scripted trip. The ship rests over the planet, climbs straight up at {@link #speed}, hovers
 *       at {@link #apex}, comes back down and rests again. While it moves, its pose goes to every client each tick
 *       ({@link ShipSync#broadcastPose}).</li>
 *   <li><b>Surface frame:</b> players aboard stay in the planet's level region. Their own client carries them with
 *       the ship and makes them collide with the drawn ship, and reports where they stand relative to it
 *       ({@link ShipPayloads.Rel}).</li>
 *   <li><b>Handoff:</b> climbing past {@link #up}, aboard players move into the plot at that same spot relative to the
 *       ship (the ship frame). Descending past {@link #down}, they move back to the surface at the same spot relative
 *       to the ship's current pose. A {@link ShipPayloads.FrameSwitch} goes just before each teleport.</li>
 * </ul>
 * The thresholds are ROADMAP's 1.5 km up / 1.2 km down, scaled to the level's height; the gap is the hysteresis.
 */
public final class Handoff {
    enum Phase { IDLE, REST, CLIMB, HOVER, DESCEND }

    /** Altitudes above the resting pose, in blocks. */
    static double up = 160;
    static double down = 128;
    static double apex = 200;
    /** Blocks per tick: 0.5 = 10 blocks/s. */
    static double speed = 0.5;
    static int hoverTicks = 40;
    static final int REST_TICKS = 60;
    /** The surface under the ship is ticketed this many ticks before the hand-back (E1's best pre-load). */
    static final int PRELOAD_TICKS = 60;
    static final int TICKET_LINGER_TICKS = 100;

    /** Where the ship rests: a column on the home planet's face E1, near the face centre. */
    static final int START_X = 256;
    static final int START_Z = 256;
    /** Where the player stands aboard (ship cells) and looks: west, through the port wall, a little down. */
    static final Vec3 ABOARD = new Vec3(4.5, 1.0, 4.5);
    /** E6: where the second, third… player stands (in front of the first one's view, off to the side). */
    static final Vec3[] MORE_ABOARD = {new Vec3(2.5, 1.0, 3.5), new Vec3(2.5, 1.0, 5.5), new Vec3(4.5, 1.0, 2.5)};
    static final float VIEW_YAW = 90.0F;
    static final float VIEW_PITCH = 20.0F;

    static Phase phase = Phase.IDLE;
    static Vec3 restPose;
    private static int phaseTicks;
    static int trip;
    static int trips;
    static int flags = ShipPayloads.E5Settings.ALL_FIXES | ShipPayloads.E5Settings.CAPTURE;
    private static final Map<UUID, ShipPayloads.Rel> REL = new HashMap<>();
    private static ChunkPos preloaded;
    private static long preloadRemoveAt = -1;
    static int handoffsUp;
    static int handoffsDown;

    private Handoff() {
    }

    static void reset() {
        phase = Phase.IDLE;
        restPose = null;
        trip = 0;
        trips = 0;
        REL.clear();
        preloaded = null;
        preloadRemoveAt = -1;
        handoffsUp = 0;
        handoffsDown = 0;
    }

    /**
     * From the client, whenever its ship-relative position changes. Runs on the server thread. E6: relayed to every
     * other player who has the ship, so they can draw this player against the ship as they draw it.
     */
    static void onRel(ServerPlayer player, ShipPayloads.Rel rel) {
        REL.put(player.getUUID(), rel);
        ShipPayloads.PeerRel peer = new ShipPayloads.PeerRel(player.getId(), rel.shipFrame(), rel.aboard(), rel.x(), rel.y(),
                rel.z(), rel.poseTick());
        for (ServerPlayer other : player.level().getServer().getPlayerList().getPlayers()) {
            if (other != player && ShipSync.tracking(other)) {
                ServerPlayNetworking.send(other, peer);
            }
        }
    }

    /** Rests the ship over the planet and stands {@code player} aboard, still, looking out of the port wall. */
    static String start(ServerPlayer player) {
        return start(List.of(player));
    }

    /** E6: the same with several players aboard: the first at {@link #ABOARD}, the others at {@link #MORE_ABOARD}. */
    static String start(List<ServerPlayer> players) {
        if (players.isEmpty() || players.size() > 1 + MORE_ABOARD.length) {
            return "E5/E6 start takes 1 to " + (1 + MORE_ABOARD.length) + " players";
        }
        ServerPlayer first = players.get(0);
        ServerLevel level = first.level();
        MinecraftServer server = level.getServer();
        level.getChunk(START_X >> 4, START_Z >> 4);
        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, START_X, START_Z);
        restPose = new Vec3(START_X + 0.5, ground + 1 + Spike.SHIP_Y / 2.0, START_Z + 0.5);
        ShipSync.pose = restPose;
        ShipSync.rotation = new Quaternionf();
        ShipSync.spinDegPerSec = 0;
        ShipSync.copies = 1;
        if (!ShipSync.active()) {
            ShipSync.setActive(server, true);
        }
        ShipSync.broadcastPose(server);
        phase = Phase.IDLE;
        trip = 0;
        StringBuilder who = new StringBuilder();
        for (int i = 0; i < players.size(); i++) {
            ServerPlayer player = players.get(i);
            // The ship must be on the client before the player lands in it, or they fall through the floor.
            ShipSync.ensureTracked(server, player);
            REL.remove(player.getUUID());
            player.setGameMode(GameType.CREATIVE);
            player.getAbilities().flying = false;
            player.onUpdateAbilities();
            Vec3 aboard = ShipSync.cellOrigin(restPose).add(i == 0 ? ABOARD : MORE_ABOARD[i - 1]);
            player.teleport(new TeleportTransition(level, aboard, Vec3.ZERO, VIEW_YAW, VIEW_PITCH, TeleportTransition.DO_NOTHING));
            who.append(i == 0 ? "" : ", ").append(player.getGameProfile().name()).append(" at ").append(fmt(aboard));
        }
        sendSettings(server, "start");
        return String.format(Locale.ROOT, "Ship resting at %s (ground y=%d), aboard: %s", fmt(restPose), ground, who);
    }

    /** Starts {@code n} round trips from the resting pose. */
    static String trips(MinecraftServer server, int n) {
        if (restPose == null) {
            return "Run /we spike e5 start <player> first";
        }
        trips = n;
        trip = 0;
        enter(Phase.REST);
        return String.format(Locale.ROOT, "E5: %d round trips, up at %.0f, down at %.0f, apex %.0f, %.1f blocks/s, flags %d, about %d s",
                n, up, down, apex, speed * 20, flags, n * tripTicks() / 20 + 3);
    }

    static int tripTicks() {
        return (int) Math.ceil(2 * apex / speed) + hoverTicks + REST_TICKS;
    }

    static void stop() {
        phase = Phase.IDLE;
        trips = 0;
    }

    static void sendSettings(MinecraftServer server, String label) {
        ShipPayloads.E5Settings settings = new ShipPayloads.E5Settings(flags, label);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(p, settings);
        }
    }

    private static void enter(Phase next) {
        phase = next;
        phaseTicks = 0;
    }

    /** End of every server tick: fly the ship, then hand players over where it crossed a threshold. */
    static void tick(MinecraftServer server) {
        ServerLevel level = server.overworld();
        long now = server.getTickCount();
        int radius = server.getPlayerList().getViewDistance();
        if (preloaded != null && preloadRemoveAt >= 0 && now >= preloadRemoveAt) {
            level.getChunkSource().removeTicketWithRadius(Spike.preloadTicket, preloaded, radius);
            preloaded = null;
            preloadRemoveAt = -1;
        }
        if (phase == Phase.IDLE || restPose == null) {
            return;
        }
        phaseTicks++;
        double alt = ShipSync.pose.y - restPose.y;
        double dy = 0;
        switch (phase) {
            case REST -> {
                if (phaseTicks >= REST_TICKS) {
                    if (trip >= trips) {
                        enter(Phase.IDLE);
                        WorldEngineMod.LOGGER.info("[spike] E5 done: {} trips, {} handoffs up, {} down", trips, handoffsUp, handoffsDown);
                        sendSettings(server, "done");
                        return;
                    }
                    trip++;
                    sendSettings(server, "trip " + trip + "/" + trips);
                    enter(Phase.CLIMB);
                }
            }
            case CLIMB -> {
                dy = Math.min(speed, apex - alt);
                if (alt + dy >= apex) {
                    enter(Phase.HOVER);
                }
            }
            case HOVER -> {
                if (phaseTicks >= hoverTicks) {
                    enter(Phase.DESCEND);
                }
            }
            case DESCEND -> {
                dy = -Math.min(speed, alt);
                if (alt + dy <= 0) {
                    enter(Phase.REST);
                }
            }
            default -> {
            }
        }
        if (dy != 0) {
            ShipSync.pose = ShipSync.pose.add(0, dy, 0);
            ShipSync.broadcastPose(server);
        }
        alt = ShipSync.pose.y - restPose.y;

        boolean anyInShipFrame = false;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.level() != level || !ShipSync.tracking(p)) {
                continue;
            }
            boolean inShip = Spike.inShipyard(p.getX(), p.getZ());
            anyInShipFrame |= inShip;
            ShipPayloads.Rel rel = REL.get(p.getUUID());
            if (!inShip && phase == Phase.CLIMB && alt >= up && rel != null && rel.aboard() && !rel.shipFrame()) {
                toShipFrame(level, p, rel, now, alt);
            } else if (inShip && (phase == Phase.DESCEND || phase == Phase.REST) && alt <= down) {
                toSurface(level, p, rel, now, alt);
            }
        }
        // Pre-load the surface under the ship before the hand-back.
        if (phase == Phase.DESCEND && anyInShipFrame && preloaded == null && (alt - down) / speed <= PRELOAD_TICKS) {
            preloaded = ChunkPos.containing(BlockPos.containing(ShipSync.pose));
            preloadRemoveAt = -1;
            level.getChunkSource().addTicketWithRadius(Spike.preloadTicket, preloaded, radius);
            WorldEngineMod.LOGGER.info("[spike] E5 preload surface {} radius {} at alt {}", preloaded, radius,
                    String.format(Locale.ROOT, "%.1f", alt));
        }
    }

    private static void toShipFrame(ServerLevel level, ServerPlayer p, ShipPayloads.Rel rel, long now, double alt) {
        Vec3 target = Vec3.atLowerCornerOf(Spike.PLOT_ORIGIN).add(rel.x(), rel.y(), rel.z());
        ServerPlayNetworking.send(p, new ShipPayloads.FrameSwitch(ShipSync.SHIP_ID, true, now));
        p.teleportTo(level, target.x, target.y, target.z, Relative.union(Relative.DELTA, Relative.ROTATION), 0.0F, 0.0F, false);
        handoffsUp++;
        WorldEngineMod.LOGGER.info(String.format(Locale.ROOT,
                "[spike] E5 handoff up %s: trip %d/%d tick %d alt %.2f rel (%.4f, %.4f, %.4f) relPoseTick %d (lag %d) -> %s",
                p.getGameProfile().name(), trip, trips, now, alt, rel.x(), rel.y(), rel.z(), rel.poseTick(),
                now - rel.poseTick(), fmt(target)));
    }

    private static void toSurface(ServerLevel level, ServerPlayer p, ShipPayloads.Rel rel, long now, double alt) {
        Vec3 relPos = rel != null && rel.shipFrame()
                ? new Vec3(rel.x(), rel.y(), rel.z())
                : p.position().subtract(Vec3.atLowerCornerOf(Spike.PLOT_ORIGIN));
        Vec3 target = ShipSync.cellOrigin(ShipSync.pose).add(relPos);
        ServerPlayNetworking.send(p, new ShipPayloads.FrameSwitch(ShipSync.SHIP_ID, false, now));
        p.teleportTo(level, target.x, target.y, target.z, Relative.union(Relative.DELTA, Relative.ROTATION), 0.0F, 0.0F, false);
        handoffsDown++;
        preloadRemoveAt = now + TICKET_LINGER_TICKS;
        WorldEngineMod.LOGGER.info(String.format(Locale.ROOT,
                "[spike] E5 handoff down %s: trip %d/%d tick %d alt %.2f rel (%.4f, %.4f, %.4f)%s -> %s",
                p.getGameProfile().name(), trip, trips, now, alt, relPos.x, relPos.y, relPos.z,
                rel != null && rel.shipFrame() ? "" : " (from server position)", fmt(target)));
    }

    static String status() {
        if (restPose == null) {
            return "E5 not started";
        }
        return String.format(Locale.ROOT, "E5 %s: trip %d/%d, alt %.1f, handoffs up %d / down %d, flags %d, hover %d ticks",
                phase, trip, trips, ShipSync.pose.y - restPose.y, handoffsUp, handoffsDown, flags, hoverTicks);
    }

    private static String fmt(Vec3 v) {
        return String.format(Locale.ROOT, "(%.3f, %.3f, %.3f)", v.x, v.y, v.z);
    }
}
