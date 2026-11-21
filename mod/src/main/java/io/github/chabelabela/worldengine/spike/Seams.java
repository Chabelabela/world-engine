package io.github.chabelabela.worldengine.spike;

import io.github.chabelabela.worldengine.WorldEngineMod;
import io.github.chabelabela.worldengine.planet.FaceCell;
import io.github.chabelabela.worldengine.planet.PlanetFace;
import io.github.chabelabela.worldengine.planet.PlanetHit;
import io.github.chabelabela.worldengine.planet.PlanetLayout;
import io.github.chabelabela.worldengine.planet.PlanetSpec;
import io.github.chabelabela.worldengine.planet.Planets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

/**
 * E2 (spike): crossing a seam. Each tick, a player who has left their last face into level space that belongs to no
 * face (beyond one of the 7 seam edges of the cross-shaped net) is teleported to {@code CubeSphere::fold}'s point on
 * the neighbouring face, keeping momentum and look: grid velocity rotated by {@code QuarterTurns::apply}, yaw by
 * {@code QuarterTurns::rotate_yaw}. No overlap band yet (that's M1e): the teleport happens on the first tick past
 * the edge.
 */
public final class Seams {
    /** One edge of one face of a planet. */
    record Edge(PlanetSpec planet, PlanetFace face, String side) {
        String label() {
            return planet.name() + ":" + face + "-" + side;
        }
    }

    /** What the last crossing did, for the E2 test. */
    record Crossing(Edge from, PlanetFace to, int turns, double fromX, double fromZ, double toX, double toZ,
                    Vec3 velocityBefore, Vec3 velocityAfter, float yawBefore, float yawAfter, double serverError) {
    }

    static boolean enabled;
    /**
     * Land one tick of (rotated) velocity short of fold's point. E2's first run landed exactly one tick of travel
     * further than a jump on flat ground; this tests whether the client advances one step right after the teleport.
     */
    static boolean compensate;
    private static final Map<UUID, PlanetHit> LAST = new HashMap<>();
    /**
     * Server tick until which a player who just crossed is not checked again. Without it, a crossing that lands
     * within one tick of travel of the new face's edge (possible with {@link #compensate}) folds straight back: the
     * overlap band of M1e, which teleports far from any edge, would give this hysteresis for free.
     */
    private static final Map<UUID, Long> COOLDOWN = new HashMap<>();
    static final int COOLDOWN_TICKS = 3;
    static final Map<UUID, Crossing> LAST_CROSSING = new HashMap<>();

    private Seams() {
    }

    static void reset() {
        enabled = false;
        LAST.clear();
        LAST_CROSSING.clear();
        COOLDOWN.clear();
    }

    /** End of every server tick. */
    static void tick(MinecraftServer server) {
        if (!enabled) {
            return;
        }
        ServerLevel level = server.overworld();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level() != level || player.isSpectator()) {
                continue;
            }
            if (COOLDOWN.getOrDefault(player.getUUID(), 0L) > server.getTickCount()) {
                continue;
            }
            Vec3 pos = player.position();
            PlanetHit hit = Planets.at(Mth.floor(pos.x), Mth.floor(pos.z));
            if (hit != null) {
                LAST.put(player.getUUID(), hit);
            } else {
                PlanetHit last = LAST.get(player.getUUID());
                if (last != null) {
                    cross(player, level, last, pos);
                }
            }
        }
    }

    /** Forgets where a player was (after a test teleports them somewhere new). */
    static void forget(ServerPlayer player) {
        LAST.remove(player.getUUID());
    }

    private static void cross(ServerPlayer player, ServerLevel level, PlanetHit last, Vec3 pos) {
        PlanetSpec planet = last.planet();
        PlanetLayout layout = planet.layout();
        PlanetFace face = last.cell().face();
        double gx = pos.x - layout.faceOriginX(face);
        double gz = pos.z - layout.faceOriginZ(face);
        Vec3 v = player.getKnownMovement();
        float yaw = player.getYRot();
        SeamFold.Crossed c = SeamFold.cross(planet.cells(), face, gx, gz, v.x, v.z, yaw);
        if (c == null) {
            return; // not past exactly one edge (beyond a corner, or too far): left alone
        }
        Vec3 velocity = new Vec3(c.vx(), v.y, c.vz());
        Vec3 dest = new Vec3(layout.faceOriginX(c.face()) + c.x(), pos.y, layout.faceOriginZ(c.face()) + c.z());
        if (compensate) {
            dest = dest.subtract(velocity.x, 0, velocity.z);
        }
        player.teleport(new TeleportTransition(level, dest, velocity, c.yaw(), player.getXRot(), TeleportTransition.DO_NOTHING));
        double error = player.position().distanceTo(dest);
        String side = gx < 0 ? "west" : gx > planet.cells() ? "east" : gz < 0 ? "north" : "south";
        Crossing crossing = new Crossing(new Edge(planet, face, side), c.face(), c.turns(), gx, gz, c.x(), c.z(), v, velocity,
                yaw, c.yaw(), error);
        LAST.put(player.getUUID(), new PlanetHit(planet, new FaceCell(c.face(), Mth.floor(c.x()), Mth.floor(c.z()))));
        LAST_CROSSING.put(player.getUUID(), crossing);
        COOLDOWN.put(player.getUUID(), (long) level.getServer().getTickCount() + COOLDOWN_TICKS);
        WorldEngineMod.LOGGER.info(String.format(Locale.ROOT,
                "[spike] E2 cross %s -> %s turns=%d grid (%.3f, %.3f) -> (%.3f, %.3f) v (%.3f, %.3f) -> (%.3f, %.3f) "
                        + "yaw %.1f -> %.1f serverError=%.2e",
                crossing.from().label(), c.face(), c.turns(), gx, gz, c.x(), c.z(), v.x, v.z, c.vx(), c.vz(), yaw, c.yaw(), error));
    }

    // ---------------------------------------------------------------------------------------------
    // The E2 test: every directed seam, launched like a running jump.
    // ---------------------------------------------------------------------------------------------

    /** Horizontal launch speed (blocks per tick): a bit more than a sprint-jump's take-off. */
    static final double LAUNCH_SPEED = 0.45;
    static final double LAUNCH_UP = 0.42;
    /** Start this far inside the edge, so the crossing happens mid-air. */
    static final double START_INSIDE = 2.5;
    /** Control launches start this far inside, so they land without reaching the edge. */
    static final double CONTROL_INSIDE = 30.0;
    /** Superflat: standing on the grass at y = -61. */
    static final double GROUND_Y = -60.0;

    /** All directed seams: face edges whose far side is level space that belongs to no face. */
    static List<Edge> seams() {
        List<Edge> seams = new ArrayList<>();
        for (PlanetSpec planet : Planets.ALL) {
            PlanetLayout layout = planet.layout();
            int n = planet.cells();
            for (PlanetFace face : PlanetFace.values()) {
                int ox = layout.faceOriginX(face);
                int oz = layout.faceOriginZ(face);
                if (Planets.at(ox - 1, oz + n / 2) == null) {
                    seams.add(new Edge(planet, face, "west"));
                }
                if (Planets.at(ox + n, oz + n / 2) == null) {
                    seams.add(new Edge(planet, face, "east"));
                }
                if (Planets.at(ox + n / 2, oz - 1) == null) {
                    seams.add(new Edge(planet, face, "north"));
                }
                if (Planets.at(ox + n / 2, oz + n) == null) {
                    seams.add(new Edge(planet, face, "south"));
                }
            }
        }
        return seams;
    }

    /** Outward unit grid direction of an edge. */
    static double[] outward(String side) {
        return switch (side) {
            case "west" -> new double[] {-1, 0};
            case "east" -> new double[] {1, 0};
            case "north" -> new double[] {0, -1};
            default -> new double[] {0, 1};
        };
    }

    /** Minecraft yaw looking along {@code outward(side)}. */
    static float outwardYaw(String side) {
        return switch (side) {
            case "west" -> 90.0F;
            case "east" -> -90.0F;
            case "north" -> 180.0F;
            default -> 0.0F;
        };
    }

    /** Grid start point on {@code edge}'s face, {@code inside} blocks in from the edge, {@code along} along it. */
    static double[] start(Edge edge, double inside, double along) {
        int n = edge.planet().cells();
        return switch (edge.side()) {
            case "west" -> new double[] {inside, along};
            case "east" -> new double[] {n - inside, along};
            case "north" -> new double[] {along, inside};
            default -> new double[] {along, n - inside};
        };
    }

    static Vec3 levelPos(Edge edge, double[] grid) {
        PlanetLayout layout = edge.planet().layout();
        return new Vec3(layout.faceOriginX(edge.face()) + grid[0], GROUND_Y, layout.faceOriginZ(edge.face()) + grid[1]);
    }

    /** Stands the player at {@code grid} on the edge's face, still, looking outward. */
    static void place(ServerPlayer player, Edge edge, double[] grid) {
        ServerLevel level = player.level();
        forget(player);
        player.teleport(new TeleportTransition(level, levelPos(edge, grid), Vec3.ZERO, outwardYaw(edge.side()), 0.0F,
                TeleportTransition.DO_NOTHING));
    }

    /** A running jump outward. */
    static void launch(ServerPlayer player, Edge edge) {
        double[] out = outward(edge.side());
        player.setDeltaMovement(out[0] * LAUNCH_SPEED, LAUNCH_UP, out[1] * LAUNCH_SPEED);
        player.connection.send(new ClientboundSetEntityMotionPacket(player));
    }
}
