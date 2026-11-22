package io.github.chabelabela.worldengine.spike.client;

import io.github.chabelabela.worldengine.WorldEngineMod;
import io.github.chabelabela.worldengine.spike.ShipPayloads;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.debugchart.LocalSampleLogger;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * E6 (spike): two players aboard one ship (Q5), client side.
 * <ul>
 *   <li><b>Track</b> ({@code TRACK}): every client tick, one log line with where this player stands relative to the
 *       ship as this client draws it, where each other player is drawn relative to it, and the bytes received
 *       ({@code [spike] E6 tick …}). {@code tools/spike/e6_report.py} joins two clients' logs.</li>
 *   <li><b>Peer fix</b> ({@code FIX_PEERS}): vanilla draws another player where the server last said they were,
 *       interpolated, so a player riding a moving ship lags behind it (and jumps late at a handoff, when the server
 *       moves them). With the fix, another player aboard is drawn at this client's ship (as drawn) plus that player's
 *       own ship-relative report ({@link ShipPayloads.PeerRel}), in either frame.</li>
 * </ul>
 */
final class E6Client {
    private static ClientShipStore ship;
    private static final Map<Integer, ShipPayloads.PeerRel> PEERS = new HashMap<>();

    /** Where the fix put a peer this tick: previous and current position, its rel, and the local frame then. */
    private record Placed(Vec3 prev, Vec3 cur, Vec3 rel, boolean localShipFrame) {
    }

    private static final Map<Integer, Placed> PLACED = new HashMap<>();
    private static long ticks;
    static int posesReceived;
    static int peerRelsReceived;
    /** Frames where a packet had moved a placed peer since the tick, and the fix put it back. */
    static int frameRepairs;
    /** Decoded bytes received per packet type (custom payloads by id) since the last per-second line. Netty thread. */
    private static final Map<String, LongAdder> BYTES_BY_TYPE = new ConcurrentHashMap<>();
    private static final Map<String, LongAdder> COUNT_BY_TYPE = new ConcurrentHashMap<>();
    private static long wireBytesThisSecond;

    private E6Client() {
    }

    static void init(ClientShipStore store) {
        ship = store;
    }

    static void onPeerRel(ShipPayloads.PeerRel rel) {
        PEERS.put(rel.entityId(), rel);
        peerRelsReceived++;
    }

    /** Any thread (netty). */
    static void countPacket(net.minecraft.network.protocol.Packet<?> packet, int bytes) {
        String type;
        if (packet instanceof net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket custom) {
            type = "payload/" + custom.payload().type().id();
        } else if (packet instanceof net.minecraft.network.protocol.game.ClientboundTrackedWaypointPacket waypoint) {
            // Which locator-bar operation and kind (Vec3i / Chunk / Azimuth / Empty), and about whom.
            String who = waypoint.waypoint().id().map(uuid -> uuid.toString().substring(0, 8), name -> name);
            type = "waypoint." + waypoint.operation() + "." + waypoint.waypoint().getClass().getSimpleName() + "." + who;
        } else {
            type = packet.type().id().getPath();
        }
        BYTES_BY_TYPE.computeIfAbsent(type, k -> new LongAdder()).add(bytes);
        COUNT_BY_TYPE.computeIfAbsent(type, k -> new LongAdder()).increment();
    }

    static void reset() {
        PEERS.clear();
        PLACED.clear();
        heldInput = null;
    }

    /** The ship's cell (0, 0, 0) corner as this client draws it: {previous tick, this tick}. */
    private static Vec3[] localOrigins(LocalPlayer player) {
        if (E5Client.inShipFrame(player.position())) {
            Vec3 o = E5Client.plotOrigin();
            return new Vec3[] {o, o};
        }
        return new Vec3[] {E5Client.cellOrigin(ship.renderPos(0.0F)), E5Client.cellOrigin(ship.curPos())};
    }

    /** The player's real input while {@code HOLD} has swapped in an empty one. */
    private static net.minecraft.client.player.ClientInput heldInput;

    /** {@code HOLD}: swap the player's keyboard input for one that never presses anything, and back. */
    private static void hold(LocalPlayer player) {
        boolean on = E5Client.settings.has(ShipPayloads.E5Settings.HOLD);
        if (on && heldInput == null) {
            heldInput = player.input;
            player.input = new net.minecraft.client.player.ClientInput();
            WorldEngineMod.LOGGER.info("[spike] E6 movement input held");
        } else if (!on && heldInput != null) {
            player.input = heldInput;
            heldInput = null;
            WorldEngineMod.LOGGER.info("[spike] E6 movement input released");
        }
    }

    /** End of every client tick (after entities ticked). */
    static void endTick(Minecraft mc) {
        if (mc.player != null) {
            hold(mc.player);
        }
        if (mc.level == null || mc.player == null || ship == null || !ship.hasPose()) {
            PLACED.clear();
            return;
        }
        ticks++;
        if (E5Client.settings.has(ShipPayloads.E5Settings.FIX_PEERS)) {
            place(mc);
        } else {
            PLACED.clear();
        }
        if (E5Client.settings.has(ShipPayloads.E5Settings.TRACK)) {
            log(mc);
            wireBytesThisSecond += latestBandwidthSample(mc);
            if (ticks % 20 == 0) {
                logBytes();
            }
        }
    }

    /** Once a second: wire bytes received, and decoded bytes by packet type, biggest first. */
    private static void logBytes() {
        List<Map.Entry<String, Long>> rows = new ArrayList<>();
        long decoded = 0;
        for (Map.Entry<String, LongAdder> e : BYTES_BY_TYPE.entrySet()) {
            long b = e.getValue().sumThenReset();
            if (b > 0) {
                rows.add(Map.entry(e.getKey(), b));
                decoded += b;
            }
        }
        rows.sort((x, y) -> Long.compare(y.getValue(), x.getValue()));
        StringBuilder top = new StringBuilder();
        for (Map.Entry<String, Long> row : rows) {
            LongAdder n = COUNT_BY_TYPE.get(row.getKey());
            top.append(top.isEmpty() ? "" : ",").append(row.getKey()).append('=').append(row.getValue())
                    .append('/').append(n == null ? 0 : n.sumThenReset());
        }
        WorldEngineMod.LOGGER.info("[spike] E6 bytes wire={} decoded={} types={} label=\"{}\"", wireBytesThisSecond, decoded,
                top.isEmpty() ? "-" : top, E5Client.settings.label());
        wireBytesThisSecond = 0;
    }

    /** Puts every other player aboard at this client's ship plus their own ship-relative report. */
    private static void place(Minecraft mc) {
        boolean localShip = E5Client.inShipFrame(mc.player.position());
        Vec3[] origin = localOrigins(mc.player);
        Map<Integer, Placed> next = new HashMap<>();
        for (AbstractClientPlayer other : mc.level.players()) {
            if (other == mc.player) {
                continue;
            }
            ShipPayloads.PeerRel report = PEERS.get(other.getId());
            if (report == null || !report.aboard()) {
                continue;
            }
            Vec3 rel = new Vec3(report.x(), report.y(), report.z());
            Vec3 cur = origin[1].add(rel);
            Placed last = PLACED.get(other.getId());
            Vec3 prev = last != null && last.localShipFrame() == localShip ? origin[0].add(last.rel()) : cur;
            other.getInterpolation().cancel();
            apply(other, prev, cur);
            next.put(other.getId(), new Placed(prev, cur, rel, localShip));
        }
        PLACED.clear();
        PLACED.putAll(next);
    }

    private static void apply(Entity entity, Vec3 prev, Vec3 cur) {
        entity.setPos(cur);
        entity.xo = entity.xOld = prev.x;
        entity.yo = entity.yOld = prev.y;
        entity.zo = entity.zOld = prev.z;
    }

    /**
     * {@code GameRenderer.update} HEAD, every frame: packets are handled at the start of a frame, before its ticks, and
     * on most frames no tick runs. A teleport packet may have moved a placed peer (put it back), or moved this player
     * into the other frame (place every peer again against the new frame).
     */
    static void beforeFrame(Minecraft mc) {
        if (PLACED.isEmpty() || mc.level == null || mc.player == null || ship == null) {
            return;
        }
        boolean localShip = E5Client.inShipFrame(mc.player.position());
        if (PLACED.values().iterator().next().localShipFrame() != localShip) {
            place(mc);
            frameRepairs++;
            return;
        }
        for (Map.Entry<Integer, Placed> entry : PLACED.entrySet()) {
            Entity entity = mc.level.getEntity(entry.getKey());
            if (entity != null && !entity.position().equals(entry.getValue().cur())) {
                apply(entity, entry.getValue().prev(), entry.getValue().cur());
                frameRepairs++;
            }
        }
    }

    private static void log(Minecraft mc) {
        LocalPlayer self = mc.player;
        boolean localShip = E5Client.inShipFrame(self.position());
        Vec3 origin = localOrigins(self)[1];
        StringBuilder peers = new StringBuilder();
        if (mc.getConnection() != null) {
            for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
                UUID id = info.getProfile().id();
                if (id.equals(self.getUUID())) {
                    continue;
                }
                if (!peers.isEmpty()) {
                    peers.append(';');
                }
                String name = info.getProfile().name();
                Player other = mc.level.getPlayerByUUID(id);
                if (other == null) {
                    peers.append(name).append(":absent");
                    continue;
                }
                boolean otherShip = E5Client.inShipFrame(other.position());
                Vec3 o = otherShip == localShip ? origin : otherShip ? E5Client.plotOrigin() : E5Client.cellOrigin(ship.curPos());
                peers.append(name).append(otherShip == localShip ? ":seen" : ":other").append(fmt(other.position().subtract(o)));
                ShipPayloads.PeerRel report = PEERS.get(other.getId());
                if (report != null) {
                    peers.append(":rep").append(fmt(new Vec3(report.x(), report.y(), report.z())))
                            .append(report.shipFrame() ? "s" : "w").append(report.aboard() ? "a" : "-").append(report.poseTick());
                }
            }
        }
        WorldEngineMod.LOGGER.info(String.format(Locale.ROOT,
                "[spike] E6 tick c=%d pose=%d pend=%d mv=%d mode=%s self=%s peers=%s rx=%d nPose=%d nPeer=%d fix=%d label=\"%s\"",
                ticks, ship.curTick(), ship.pendingPoses(), ship.renderPos(0.0F).equals(ship.curPos()) ? 0 : 1,
                localShip ? "ship" : "surface", fmt(self.position().subtract(origin)), peers.isEmpty() ? "-" : peers,
                latestBandwidthSample(mc), posesReceived, peerRelsReceived, frameRepairs, E5Client.settings.label()));
    }

    private static String fmt(Vec3 v) {
        return String.format(Locale.ROOT, "(%.4f,%.4f,%.4f)", v.x, v.y, v.z);
    }

    /** Bytes received during the last client tick (vanilla's bandwidth logger; real network connections only). */
    private static long latestBandwidthSample(Minecraft mc) {
        LocalSampleLogger logger = mc.getDebugOverlay().getBandwidthLogger();
        int size = logger.size();
        return size == 0 ? 0 : logger.get(size - 1);
    }
}
