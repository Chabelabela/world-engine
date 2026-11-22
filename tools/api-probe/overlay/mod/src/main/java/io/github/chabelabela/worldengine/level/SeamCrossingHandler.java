package io.github.chabelabela.worldengine.level;

import io.github.chabelabela.worldengine.WorldEngineMod;
import io.github.chabelabela.worldengine.nativebridge.NativeCore;
import io.github.chabelabela.worldengine.planet.PlanetHit;
import io.github.chabelabela.worldengine.planet.Planets;
import io.github.chabelabela.worldengine.planet.SeamCrossings;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * Carries everything that steps off a planet's net across a seam to the neighbouring face, at the end of every server
 * tick, in the universe level (see {@link SeamCrossings}; ADR 0001, E2 in the M1b spike).
 *
 * <ul>
 *   <li>Entities within {@link #EDGE_MARGIN} blocks of a face edge have their position remembered. One that is then
 *       on no face has left across a seam, and is teleported to fold's point, one tick of travel short, with its
 *       grid velocity and yaw turned.
 *   <li>Beyond a cube corner there is no neighbour: it goes back to where it last stood, with no horizontal speed.
 *   <li>After a crossing, an entity isn't checked for {@link #COOLDOWN_TICKS} ticks, so one that lands less than a
 *       tick of travel inside the new face doesn't fold straight back.
 *   <li>Players near a seam get the far side pre-loaded, so the destination is generated before they arrive.
 * </ul>
 *
 * <p>Passengers ride along with their vehicle. Server thread only.
 */
public final class SeamCrossingHandler {
    /** Ticks after a crossing during which an entity isn't checked again (E2 needed 3). */
    public static final int COOLDOWN_TICKS = 3;
    /** Entities this close to a face edge have their position remembered: more than a tick's travel (10 blocks). */
    public static final int EDGE_MARGIN = 16;
    /** Players this close to a seam have its far side pre-loaded. */
    public static final int PRELOAD_MARGIN = 64;
    /** Chunk radius of the pre-loaded area around the far side. */
    public static final int PRELOAD_RADIUS = 2;
    /** Pre-load tickets are refreshed this often, and expire after {@link #PRELOAD_TICKS} without a refresh. */
    static final int PRELOAD_REFRESH_TICKS = 20;
    static final long PRELOAD_TICKS = 100;

    private static TicketType preloadTicket;
    private static final Map<UUID, double[]> LAST_ON_NET = new HashMap<>();
    private static final Map<UUID, Long> COOLDOWN_UNTIL = new HashMap<>();

    private SeamCrossingHandler() {
    }

    /** Registers the pre-load ticket type and the tick hook. Call once, from the mod initializer. */
    public static void register() {
        preloadTicket = Registry.register(BuiltInRegistries.TICKET_TYPE, WorldEngineMod.id("seam_preload"),
                new TicketType(PRELOAD_TICKS, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION));
        ServerTickEvents.END_SERVER_TICK.register(SeamCrossingHandler::onEndTick);
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

    /** Checks every entity in {@code level} once. Public for GameTests, which run in a flat test level. */
    public static void tickLevel(ServerLevel level) {
        NativeCore core = PlanetTerrain.core();
        SeamCrossings.Folder folder = (cells, face, x, z, vx, vz, yaw) ->
                core.seamCross(cells, face.id(), x, z, vx, vz, yaw);
        long now = level.getServer().getTickCount();
        List<Runnable> moves = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (entity.isPassenger() || entity.isRemoved() || entity.isSpectator()) {
                continue;
            }
            UUID id = entity.getUUID();
            Long until = COOLDOWN_UNTIL.get(id);
            if (until != null) {
                if (until > now) {
                    continue;
                }
                COOLDOWN_UNTIL.remove(id);
            }
            double x = entity.getX();
            double z = entity.getZ();
            PlanetHit hit = Planets.at(Mth.floor(x), Mth.floor(z));
            if (hit != null) {
                if (SeamCrossings.nearEdge(hit, EDGE_MARGIN)) {
                    LAST_ON_NET.put(id, new double[] {x, z});
                } else {
                    LAST_ON_NET.remove(id);
                }
                if (entity instanceof ServerPlayer && now % PRELOAD_REFRESH_TICKS == 0
                        && SeamCrossings.nearEdge(hit, PRELOAD_MARGIN)) {
                    for (double[] side : SeamCrossings.farSides(hit, PRELOAD_MARGIN, folder)) {
                        level.getChunkSource().addTicketWithRadius(preloadTicket,
                                new ChunkPos(Mth.floor(side[0]) >> 4, Mth.floor(side[1]) >> 4), PRELOAD_RADIUS);
                    }
                }
                continue;
            }
            double[] last = LAST_ON_NET.get(id);
            if (last == null) {
                continue;
            }
            Vec3 velocity = entity instanceof ServerPlayer player ? player.getKnownMovement() : entity.getDeltaMovement();
            SeamCrossings.Outcome outcome = SeamCrossings.plan(last[0], last[1], x, z, velocity.x, velocity.z,
                    entity.getYRot(), folder);
            if (outcome instanceof SeamCrossings.Cross cross) {
                LAST_ON_NET.put(id, new double[] {cross.foldX(), cross.foldZ()});
                COOLDOWN_UNTIL.put(id, now + COOLDOWN_TICKS);
                moves.add(() -> entity.teleport(new TeleportTransition(level,
                        new Vec3(cross.x(), entity.getY(), cross.z()), new Vec3(cross.vx(), velocity.y, cross.vz()),
                        cross.yaw(), entity.getXRot(), TeleportTransition.DO_NOTHING)));
            } else if (outcome instanceof SeamCrossings.Bounce bounce) {
                moves.add(() -> entity.teleport(new TeleportTransition(level,
                        new Vec3(bounce.x(), entity.getY(), bounce.z()), new Vec3(0, velocity.y, 0),
                        entity.getYRot(), entity.getXRot(), TeleportTransition.DO_NOTHING)));
            }
        }
        moves.forEach(Runnable::run); // after the loop: never move entities while iterating the level's list
    }

    /** Drops remembered state for entities that no longer exist anywhere. */
    private static void forgetMissing(MinecraftServer server) {
        LAST_ON_NET.keySet().removeIf(id -> !exists(server, id));
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
