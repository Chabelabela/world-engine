package io.github.chabelabela.worldengine.spike;

import io.github.chabelabela.worldengine.WorldEngineMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.TicketType;

/**
 * M1b frame-handoff spike (throwaway, lives on {@code spike/m1-handoff} only). Everything here is enabled
 * only with {@code -Dworldengine.spike=true}.
 */
public final class Spike {
    public static final boolean ENABLED = Boolean.getBoolean("worldengine.spike");

    /** The 512×512 "face region" stand-in: block columns [0, 512) × [0, 512) around the origin. */
    public static final int FACE_REGION_SIZE = 512;
    /** Minimum corner of the test shipyard plot (one 512×512 plot, far from everything). */
    public static final BlockPos PLOT_ORIGIN = new BlockPos(20_000_000, 64, 20_000_000);
    public static final int PLOT_SIZE = 512;
    /** The test ship's box at the plot origin: 7 wide (x) × 5 tall (y) × 9 long (z), bow (door) at low z. */
    public static final int SHIP_X = 7;
    public static final int SHIP_Y = 5;
    public static final int SHIP_Z = 9;

    /**
     * Loads and simulates the destination of a pre-loaded jump. Not persisted (unlike vanilla's FORCED), so a
     * ticket left over by a stopped server can't keep chunks loaded forever. Flags: LOADING | SIMULATION.
     */
    public static TicketType preloadTicket;
    /** Keeps an active ship's plot block-ticking (vanilla reports block changes to clients only then). */
    public static TicketType shipTicket;

    private Spike() {
    }

    /** Called from the main initializer; registers nothing unless the spike flag is set. */
    public static void init() {
        if (!ENABLED) {
            return;
        }
        preloadTicket = Registry.register(BuiltInRegistries.TICKET_TYPE, WorldEngineMod.id("spike_preload"),
                new TicketType(0L, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION));
        shipTicket = Registry.register(BuiltInRegistries.TICKET_TYPE, WorldEngineMod.id("spike_ship"),
                new TicketType(0L, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION));
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.clientboundPlay()
                .register(ShipPayloads.Snapshot.TYPE, ShipPayloads.Snapshot.CODEC);
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.clientboundPlay()
                .register(ShipPayloads.Delta.TYPE, ShipPayloads.Delta.CODEC);
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.clientboundPlay()
                .register(ShipPayloads.Forget.TYPE, ShipPayloads.Forget.CODEC);
        SpikeCommands.init();
        WorldEngineMod.LOGGER.warn("World Engine M1b spike enabled (-Dworldengine.spike=true): /we spike …");
    }
}
