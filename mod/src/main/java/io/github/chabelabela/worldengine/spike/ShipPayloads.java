package io.github.chabelabela.worldengine.spike;

import io.github.chabelabela.worldengine.WorldEngineMod;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;

/**
 * E4 option B: the ship-sync payloads. A ship is an axis-aligned box of cells in its plot; a cell is
 * {@code x + sizeX * (z + sizeZ * y)}. Block states travel as global state ids, block entities as their
 * client update tag ({@code BlockEntity.getUpdateTag}).
 */
public final class ShipPayloads {
    private ShipPayloads() {
    }

    /** Ship contents order-independent of transport: states in cell order, then block entities by cell. */
    public static int hash(int[] states, Map<Integer, CompoundTag> blockEntities) {
        int h = 1;
        for (int s : states) {
            h = 31 * h + s;
        }
        for (Map.Entry<Integer, CompoundTag> e : new TreeMap<>(blockEntities).entrySet()) {
            h = 31 * h + e.getKey();
            h = 31 * h + e.getValue().hashCode();
        }
        return h;
    }

    // ---------------------------------------------------------------------------------------------
    // Snapshot: the whole ship, on first sight. Palette + packed indices, like a chunk section.
    // ---------------------------------------------------------------------------------------------

    public record Snapshot(int shipId, BlockPos origin, int sizeX, int sizeY, int sizeZ, int[] states,
                           Map<Integer, CompoundTag> blockEntities, int hash) implements CustomPacketPayload {
        public static final Type<Snapshot> TYPE = new Type<>(WorldEngineMod.id("ship_snapshot"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> CODEC =
                CustomPacketPayload.codec(Snapshot::write, Snapshot::read);

        private void write(RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(shipId);
            buf.writeBlockPos(origin);
            buf.writeVarInt(sizeX);
            buf.writeVarInt(sizeY);
            buf.writeVarInt(sizeZ);
            // Palette of distinct state ids, in first-seen order.
            int[] palette = java.util.Arrays.stream(states).distinct().toArray();
            Map<Integer, Integer> index = new java.util.HashMap<>();
            for (int i = 0; i < palette.length; i++) {
                index.put(palette[i], i);
            }
            buf.writeVarIntArray(palette);
            int bits = Math.max(1, Mth.ceillog2(palette.length));
            SimpleBitStorage packed = new SimpleBitStorage(bits, states.length);
            for (int i = 0; i < states.length; i++) {
                packed.set(i, index.get(states[i]));
            }
            buf.writeByte(bits);
            buf.writeLongArray(packed.getRaw());
            writeBlockEntities(buf, blockEntities);
            buf.writeInt(hash);
        }

        private static Snapshot read(RegistryFriendlyByteBuf buf) {
            int shipId = buf.readVarInt();
            BlockPos origin = buf.readBlockPos();
            int sx = buf.readVarInt();
            int sy = buf.readVarInt();
            int sz = buf.readVarInt();
            int[] palette = buf.readVarIntArray();
            int bits = buf.readByte();
            int n = sx * sy * sz;
            SimpleBitStorage packed = new SimpleBitStorage(bits, n, buf.readLongArray());
            int[] states = new int[n];
            for (int i = 0; i < n; i++) {
                states[i] = palette[packed.get(i)];
            }
            Map<Integer, CompoundTag> bes = readBlockEntities(buf);
            return new Snapshot(shipId, origin, sx, sy, sz, states, bes, buf.readInt());
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Delta: changed cells since the last packet, plus the hash the client must reach.
    // ---------------------------------------------------------------------------------------------

    /** {@code blockEntities} holds the new tag for each changed cell that has a block entity now. */
    public record Delta(int shipId, int[] cells, int[] states, Map<Integer, CompoundTag> blockEntities, int hash)
            implements CustomPacketPayload {
        public static final Type<Delta> TYPE = new Type<>(WorldEngineMod.id("ship_delta"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Delta> CODEC =
                CustomPacketPayload.codec(Delta::write, Delta::read);

        private void write(RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(shipId);
            buf.writeVarInt(cells.length);
            for (int i = 0; i < cells.length; i++) {
                buf.writeVarInt(cells[i]);
                buf.writeVarInt(states[i]);
            }
            writeBlockEntities(buf, blockEntities);
            buf.writeInt(hash);
        }

        private static Delta read(RegistryFriendlyByteBuf buf) {
            int shipId = buf.readVarInt();
            int n = buf.readVarInt();
            int[] cells = new int[n];
            int[] states = new int[n];
            for (int i = 0; i < n; i++) {
                cells[i] = buf.readVarInt();
                states[i] = buf.readVarInt();
            }
            Map<Integer, CompoundTag> bes = readBlockEntities(buf);
            return new Delta(shipId, cells, states, bes, buf.readInt());
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** The ship left this client's range: drop the copy. */
    public record Forget(int shipId) implements CustomPacketPayload {
        public static final Type<Forget> TYPE = new Type<>(WorldEngineMod.id("ship_forget"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Forget> CODEC =
                CustomPacketPayload.codec((p, buf) -> buf.writeVarInt(p.shipId), buf -> new Forget(buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static void writeBlockEntities(RegistryFriendlyByteBuf buf, Map<Integer, CompoundTag> bes) {
        buf.writeVarInt(bes.size());
        for (Map.Entry<Integer, CompoundTag> e : new TreeMap<>(bes).entrySet()) {
            buf.writeVarInt(e.getKey());
            ByteBufCodecs.TRUSTED_COMPOUND_TAG.encode(buf, e.getValue());
        }
    }

    private static Map<Integer, CompoundTag> readBlockEntities(RegistryFriendlyByteBuf buf) {
        int n = buf.readVarInt();
        Map<Integer, CompoundTag> bes = new TreeMap<>();
        for (int i = 0; i < n; i++) {
            int cell = buf.readVarInt();
            bes.put(cell, ByteBufCodecs.TRUSTED_COMPOUND_TAG.decode(buf));
        }
        return bes;
    }
}
