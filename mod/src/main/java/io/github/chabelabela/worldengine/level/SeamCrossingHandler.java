package io.github.chabelabela.worldengine.level;

import io.github.chabelabela.worldengine.WorldEngineMod;
import io.github.chabelabela.worldengine.planet.PlanetHit;
import io.github.chabelabela.worldengine.planet.Planets;
import io.github.chabelabela.worldengine.planet.SeamBand;
import io.github.chabelabela.worldengine.planet.SeamBands;
import io.github.chabelabela.worldengine.planet.SeamCrossings;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

/**
 * Carries everything that walks across a seam to the neighbouring face, at the end of every server tick, in the
 * universe level (see {@link SeamCrossings}; ADR 0001; M1e-2).
 *
 * <ul>
 *   <li>Beyond each seam lies an overlap band copying the neighbouring face ({@link SeamBands}). Something in a band
 *       short of its middle is just walking; at the middle it is teleported to its twin on the neighbouring face,
 *       with its grid velocity and yaw turned. A player lands one tick of travel short, as its client advances once
 *       more after a teleport, unless that point is off the net.
 *   <li>Positions on a face within {@link #EDGE_MARGIN} blocks of an edge, and in a band, are remembered. Something
 *       found where nothing is, in the no-man's land near a cube corner, goes back to where it last was, with no
 *       horizontal speed. Something that moved further than {@link SeamCrossings#MAX_STEP} was teleported away, and
 *       is left alone.
 *   <li>After a crossing, an entity isn't checked for {@link #COOLDOWN_TICKS} ticks.
 *   <li>Players near a seam get the far side pre-loaded, so the destination is generated before they arrive.
 * </ul>
 *
 * <p>Passengers ride along with their vehicle. Server thread only.
 */
public final class SeamCrossingHandler {
    /** Ticks after a crossing during which an entity isn't checked again. */
    public static final int COOLDOWN_TICKS = 3;
    /** Entities this close to a face edge have their position remembered: more than a tick's travel (10 blocks). */
    public static final int EDGE_MARGIN = 16;
    /** Chunk radius of the pre-loaded area around the far side. */
    public static final int PRELOAD_RADIUS = 2;
    /** Pre-load tickets are refreshed this often, and expire after {@link #PRELOAD_TICKS} without a refresh. */
    static final int PRELOAD_REFRESH_TICKS = 20;
    static final long PRELOAD_TICKS = 100;

    private static TicketType preloadTicket;
    private static final Map<UUID, double[]> LAST_POSITION = new HashMap<>();
    private static final Map<UUID, Long> COOLDOWN_UNTIL = new HashMap<>();

    private SeamCrossingHandler() {
    }

    /** Registers the pre-load ticket type and the tick hook. Call once, from the mod initializer. */
    public static void register() {
        preloadTicket = Registry.register(BuiltInRegistries.TICKET_TYPE, WorldEngineMod.id("seam_preload"),
                new TicketType(PRELOAD_TICKS, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION));
        ServerTickEvents.END_SERVER_TICK.register(SeamCrossingHandler::onEndTick);
        // Tick counts and positions are per server run; they must not survive a singleplayer stop and start.
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            LAST_POSITION.clear();
            COOLDOWN_UNTIL.clear();
        });
    }

    private static void onEndTick(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        if (overworld.getChunkSource().getGenerator() instanceof UniverseChunkGenerator) {
            tickLevel(overworld);
        }
        if (server.getTickCount() % 600 == 0) {
            forgetMissing(server);
        }
    }

    /** Checks every entity in {@code level} once. */
    private static void tickLevel(ServerLevel level) {
        SeamBands bands = PlanetTerrain.bands();
        long now = level.getServer().getTickCount();
        List<Runnable> moves = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            Runnable move = check(level, entity, now, bands);
            if (move != null) {
                moves.add(move);
            }
        }
        moves.forEach(Runnable::run); // after the loop: never move entities while iterating the level's list
    }

    /**
     * Checks one entity now, and moves it if it has crossed. For GameTests: they run in a flat test level, where an
     * entity in a chunk loaded that same tick may not be in the level's entity list yet.
     */
    public static void checkNow(ServerLevel level, Entity entity) {
        Runnable move = check(level, entity, level.getServer().getTickCount(), PlanetTerrain.bands());
        if (move != null) {
            move.run();
        }
    }

    /**
     * Remembers where {@code entity} is, pre-loads the far side for a player near a seam, and returns the teleport to
     * run if it has reached a band's middle or no-man's land, or null.
     */
    private static Runnable check(ServerLevel level, Entity entity, long now, SeamBands bands) {
        if (entity.isPassenger() || entity.isRemoved() || entity.isSpectator()) {
            return null;
        }
        UUID id = entity.getUUID();
        Long until = COOLDOWN_UNTIL.get(id);
        if (until != null) {
            if (until > now) {
                return null;
            }
            COOLDOWN_UNTIL.remove(id);
        }
        double x = entity.getX();
        double z = entity.getZ();
        int bx = Mth.floor(x);
        int bz = Mth.floor(z);
        if (entity instanceof ServerPlayer && now % PRELOAD_REFRESH_TICKS == 0) {
            for (double[] target : bands.preloadTargets(x, z)) {
                level.getChunkSource().addTicketWithRadius(preloadTicket,
                        new ChunkPos(Mth.floor(target[0]) >> 4, Mth.floor(target[1]) >> 4), PRELOAD_RADIUS);
            }
        }
        PlanetHit hit = Planets.at(bx, bz);
        if (hit != null) {
            if (SeamCrossings.nearEdge(hit, EDGE_MARGIN)) {
                LAST_POSITION.put(id, new double[] {x, z});
            } else {
                LAST_POSITION.remove(id);
            }
            return null;
        }
        SeamBand band = bands.at(bx, bz);
        if (band != null && band.depth(x, z) < SeamBands.TELEPORT_DEPTH) {
            LAST_POSITION.put(id, new double[] {x, z}); // walking in the band
            return null;
        }
        double[] last = LAST_POSITION.get(id);
        if (last == null && band != null) {
            last = new double[] {x, z}; // beyond a band's middle without having walked there (spawned, say)
        }
        if (last == null) {
            return null;
        }
        Vec3 velocity = entity instanceof ServerPlayer player ? player.getKnownMovement() : entity.getDeltaMovement();
        SeamCrossings.Outcome outcome =
                SeamCrossings.plan(last[0], last[1], x, z, velocity.x, velocity.z, entity.getYRot(), bands);
        if (outcome instanceof SeamCrossings.Cross cross) {
            LAST_POSITION.put(id, new double[] {cross.twinX(), cross.twinZ()});
            COOLDOWN_UNTIL.put(id, now + COOLDOWN_TICKS);
            // Only a player's client advances once more after the teleport. If that landing isn't on the net, use the
            // twin itself: never leave it where nothing is.
            boolean shortOfTwin = entity instanceof ServerPlayer
                    && Planets.at(Mth.floor(cross.x()), Mth.floor(cross.z())) != null;
            double landX = shortOfTwin ? cross.x() : cross.twinX();
            double landZ = shortOfTwin ? cross.z() : cross.twinZ();
            return () -> {
                if (!entity.isRemoved()) {
                    entity.teleport(new TeleportTransition(level, new Vec3(landX, entity.getY(), landZ),
                            new Vec3(cross.vx(), velocity.y, cross.vz()), cross.yaw(), entity.getXRot(),
                            TeleportTransition.DO_NOTHING));
                }
            };
        }
        if (outcome instanceof SeamCrossings.Bounce bounce) {
            return () -> {
                if (!entity.isRemoved()) {
                    entity.teleport(new TeleportTransition(level, new Vec3(bounce.x(), entity.getY(), bounce.z()),
                            new Vec3(0, velocity.y, 0), entity.getYRot(), entity.getXRot(),
                            TeleportTransition.DO_NOTHING));
                }
            };
        }
        LAST_POSITION.remove(id); // where nothing is, and not having walked there: teleported away
        return null;
    }

    /** Drops remembered state for entities that no longer exist anywhere. */
    private static void forgetMissing(MinecraftServer server) {
        LAST_POSITION.keySet().removeIf(id -> !exists(server, id));
        COOLDOWN_UNTIL.keySet().removeIf(id -> !exists(server, id));
    }

    private static boolean exists(MinecraftServer server, UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(id) != null) {
                return true;
            }
        }
        return false;
    }
}
