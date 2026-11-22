package io.github.chabelabela.worldengine.spike;

import io.github.chabelabela.worldengine.WorldEngineMod;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.IntRBTreeSet;
import it.unimi.dsi.fastutil.ints.IntSortedSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.zip.Deflater;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * E4 option B, server side: keeps every player within {@link #RANGE} of the ship's pose supplied with a copy of
 * the ship's blocks, however far they are from the plot. First sight sends a {@link ShipPayloads.Snapshot};
 * edits follow as {@link ShipPayloads.Delta}s (collected from {@code ServerLevel.sendBlockUpdated} by a mixin and
 * flushed once per tick); leaving range sends {@link ShipPayloads.Forget}.
 */
public final class ShipSync {
    public static final int SHIP_ID = 1;
    /** Players this close to the pose get the ship (roughly a render distance). */
    public static final double RANGE = 128.0;

    /** The ship's position in the surface frame. E3/E5 will move it; for E4 it only decides who gets copies. */
    static Vec3 pose = new Vec3(256.5, -50.0, 256.5);
    /** E3: orientation, spin and copy count, sent with {@link ShipPayloads.Pose}. */
    static Quaternionf rotation = new Quaternionf();
    static float spinDegPerSec;
    static int copies = 1;
    private static boolean active;
    private static final Set<UUID> TRACKING = new HashSet<>();
    private static final IntSortedSet DIRTY = new IntRBTreeSet();

    private ShipSync() {
    }

    static boolean active() {
        return active;
    }

    /** Turns sync on or off. While on, a ticket keeps the plot block-ticking: vanilla only reports edits then. */
    static void setActive(MinecraftServer server, boolean on) {
        ServerLevel level = server.overworld();
        ChunkPos plot = ChunkPos.containing(Spike.PLOT_ORIGIN);
        if (on && !active) {
            level.getChunkSource().addTicketWithRadius(Spike.shipTicket, plot, 1);
        } else if (!on && active) {
            level.getChunkSource().removeTicketWithRadius(Spike.shipTicket, plot, 1);
            for (UUID id : TRACKING) {
                ServerPlayer p = server.getPlayerList().getPlayer(id);
                if (p != null) {
                    ServerPlayNetworking.send(p, new ShipPayloads.Forget(SHIP_ID));
                }
            }
            TRACKING.clear();
        }
        active = on;
        DIRTY.clear();
    }

    static void reset() {
        active = false;
        TRACKING.clear();
        DIRTY.clear();
    }

    /** From the mixin, for every client-visible block change in any server level. */
    public static void onBlockUpdated(ServerLevel level, BlockPos pos) {
        if (!active || level.dimension() != Level.OVERWORLD) {
            return;
        }
        int cell = cellOf(pos);
        if (cell >= 0) {
            DIRTY.add(cell);
        }
    }

    /** End of every server tick. */
    static void tick(MinecraftServer server) {
        if (!active) {
            return;
        }
        ServerLevel level = server.overworld();
        if (server.getTickCount() % 10 == 0) {
            updateTracking(server, level);
        }
        if (!DIRTY.isEmpty()) {
            if (!TRACKING.isEmpty()) {
                flush(server, level);
            }
            DIRTY.clear();
        }
    }

    private static void updateTracking(MinecraftServer server, ServerLevel level) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            // E5: a player in the shipyard is aboard in the ship frame, however far the pose is.
            boolean near = player.level() == level && (player.position().distanceTo(pose) <= RANGE
                    || Spike.inShipyard(player.getX(), player.getZ()));
            boolean tracked = TRACKING.contains(player.getUUID());
            if (near && !tracked) {
                TRACKING.add(player.getUUID());
                ShipPayloads.Snapshot snapshot = snapshot(level);
                int[] size = measure(snapshot, ShipPayloads.Snapshot.CODEC::encode, level.registryAccess());
                ServerPlayNetworking.send(player, snapshot);
                ServerPlayNetworking.send(player, posePayload(server.getTickCount()));
                WorldEngineMod.LOGGER.info("[spike] E4 snapshot -> {}: {} cells, {} block entities, raw={} deflated={} hash={}",
                        player.getGameProfile().name(), snapshot.states().length, snapshot.blockEntities().size(),
                        size[0], size[1], snapshot.hash());
            } else if (!near && tracked) {
                TRACKING.remove(player.getUUID());
                ServerPlayNetworking.send(player, new ShipPayloads.Forget(SHIP_ID));
                WorldEngineMod.LOGGER.info("[spike] E4 forget -> {}", player.getGameProfile().name());
            }
        }
        TRACKING.removeIf(id -> server.getPlayerList().getPlayer(id) == null);
    }

    /** E5: makes sure {@code player} has the ship now (snapshot and pose), before they are placed aboard. */
    static void ensureTracked(MinecraftServer server, ServerPlayer player) {
        ServerLevel level = server.overworld();
        if (TRACKING.add(player.getUUID())) {
            ServerPlayNetworking.send(player, snapshot(level));
            WorldEngineMod.LOGGER.info("[spike] E4 snapshot -> {} (E5 start)", player.getGameProfile().name());
        }
        ServerPlayNetworking.send(player, posePayload(server.getTickCount()));
    }

    private static void flush(MinecraftServer server, ServerLevel level) {
        int n = DIRTY.size();
        int[] cells = new int[n];
        int[] states = new int[n];
        Map<Integer, CompoundTag> bes = new TreeMap<>();
        int i = 0;
        for (int cell : DIRTY) {
            BlockPos pos = posOf(cell);
            cells[i] = cell;
            states[i] = Block.getId(level.getBlockState(pos));
            BlockEntity be = level.getBlockEntity(pos);
            if (be != null) {
                bes.put(cell, be.getUpdateTag(level.registryAccess()));
            }
            i++;
        }
        ShipPayloads.Delta delta = new ShipPayloads.Delta(SHIP_ID, cells, states, bes, snapshot(level).hash());
        int[] size = measure(delta, ShipPayloads.Delta.CODEC::encode, level.registryAccess());
        for (UUID id : TRACKING) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) {
                ServerPlayNetworking.send(p, delta);
            }
        }
        WorldEngineMod.LOGGER.info("[spike] E4 delta: {} cells, {} block entities, raw={} hash={} -> {} players",
                n, bes.size(), size[0], delta.hash(), TRACKING.size());
    }

    static ShipPayloads.Pose posePayload(long tick) {
        return new ShipPayloads.Pose(SHIP_ID, pose.x, pose.y, pose.z, rotation.x, rotation.y, rotation.z, rotation.w,
                spinDegPerSec, copies, tick);
    }

    static boolean tracking(ServerPlayer player) {
        return TRACKING.contains(player.getUUID());
    }

    /** The level position of the ship's cell (0, 0, 0) corner at {@code pose} (unrotated, as in E5). */
    static Vec3 cellOrigin(Vec3 pose) {
        return pose.subtract(Spike.SHIP_X / 2.0, Spike.SHIP_Y / 2.0, Spike.SHIP_Z / 2.0);
    }

    /** Sends the current pose to everyone who has the ship. */
    static void broadcastPose(MinecraftServer server) {
        ShipPayloads.Pose payload = posePayload(server.getTickCount());
        for (UUID id : TRACKING) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) {
                ServerPlayNetworking.send(p, payload);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------

    /** The whole ship as it is now. */
    static ShipPayloads.Snapshot snapshot(ServerLevel level) {
        int sx = Spike.SHIP_X;
        int sy = Spike.SHIP_Y;
        int sz = Spike.SHIP_Z;
        int[] states = new int[sx * sy * sz];
        Map<Integer, CompoundTag> bes = new TreeMap<>();
        for (int cell = 0; cell < states.length; cell++) {
            BlockPos pos = posOf(cell);
            states[cell] = Block.getId(level.getBlockState(pos));
            BlockEntity be = level.getBlockEntity(pos);
            if (be != null) {
                bes.put(cell, be.getUpdateTag(level.registryAccess()));
            }
        }
        return new ShipPayloads.Snapshot(SHIP_ID, Spike.PLOT_ORIGIN, sx, sy, sz, states, bes,
                ShipPayloads.hash(states, bes));
    }

    /** The cell of {@code pos}, or -1 outside the ship's box. */
    static int cellOf(BlockPos pos) {
        int x = pos.getX() - Spike.PLOT_ORIGIN.getX();
        int y = pos.getY() - Spike.PLOT_ORIGIN.getY();
        int z = pos.getZ() - Spike.PLOT_ORIGIN.getZ();
        if (x < 0 || y < 0 || z < 0 || x >= Spike.SHIP_X || y >= Spike.SHIP_Y || z >= Spike.SHIP_Z) {
            return -1;
        }
        return x + Spike.SHIP_X * (z + Spike.SHIP_Z * y);
    }

    static BlockPos posOf(int cell) {
        int x = cell % Spike.SHIP_X;
        int z = (cell / Spike.SHIP_X) % Spike.SHIP_Z;
        int y = cell / (Spike.SHIP_X * Spike.SHIP_Z);
        return Spike.PLOT_ORIGIN.offset(x, y, z);
    }

    /** {raw payload bytes, bytes after zlib (what network compression would send, before framing)}. */
    static <T> int[] measure(T value, BiConsumer<RegistryFriendlyByteBuf, T> encode, RegistryAccess registries) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
        try {
            encode.accept(buf, value);
            byte[] raw = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), raw);
            return new int[] {raw.length, deflatedSize(raw)};
        } finally {
            buf.release();
        }
    }

    static int deflatedSize(byte[] raw) {
        Deflater deflater = new Deflater();
        try {
            deflater.setInput(raw);
            deflater.finish();
            byte[] out = new byte[raw.length + 64];
            int total = 0;
            while (!deflater.finished()) {
                total += deflater.deflate(out);
            }
            return total;
        } finally {
            deflater.end();
        }
    }
}
