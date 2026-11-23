package io.github.chabelabela.worldengine.level;

import io.github.chabelabela.worldengine.WorldEngineMod;
import io.github.chabelabela.worldengine.planet.PlanetHit;
import io.github.chabelabela.worldengine.planet.Planets;
import io.github.chabelabela.worldengine.planet.SeamBand;
import io.github.chabelabela.worldengine.planet.SeamBands;
import io.github.chabelabela.worldengine.planet.SeamCrossings;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Keeps each overlap band and the face strip it copies in step (M1e-2). Every block change on either side is made
 * on the other too, turned by the seam's quarter turns, so stairs, doors and other facing blocks still face the same
 * way across the seam.
 *
 * <p>Called for every block change vanilla sends to clients ({@code ServerLevel.sendBlockUpdated}, through
 * {@code ServerLevelMixin}). The copy is set without neighbour or shape updates and without drops, so it can't start
 * chains of updates of its own or duplicate items; whatever the change causes on its own side (a fence connecting, a
 * torch dropping) is copied as it happens. Block entity contents (chest items, sign text) are not copied. Server
 * thread only.
 */
public final class SeamMirror {
    private static final Set<ServerLevel> ENABLED_FOR_TESTS = Collections.newSetFromMap(new WeakHashMap<>());
    private static boolean copying;

    private SeamMirror() {
    }

    /** Mirrors edits in {@code level} too, though it isn't a universe level. For GameTests. */
    public static void enableFor(ServerLevel level) {
        ENABLED_FOR_TESTS.add(level);
    }

    public static void disableFor(ServerLevel level) {
        ENABLED_FOR_TESTS.remove(level);
    }

    /** Copies the block now at {@code pos} to its twin, or twins, if it is in a band or a strip a band copies. */
    public static void onBlockUpdated(ServerLevel level, BlockPos pos, BlockState state) {
        if (copying || WorldEngineMod.core() == null) {
            return;
        }
        if (!(level.getChunkSource().getGenerator() instanceof UniverseChunkGenerator)
                && !ENABLED_FOR_TESTS.contains(level)) {
            return;
        }
        int x = pos.getX();
        int z = pos.getZ();
        PlanetHit hit = Planets.at(x, z);
        SeamBands bands;
        if (hit != null) {
            if (!SeamCrossings.nearEdge(hit, SeamBands.WIDTH)) {
                return; // the common case: nowhere near a seam
            }
            bands = PlanetTerrain.bands();
            for (SeamBands.Column copy : bands.copiesOf(x, z)) {
                set(level, copy.x(), pos.getY(), copy.z(), state, (4 - copy.band().turns()) % 4);
            }
            return;
        }
        bands = PlanetTerrain.bands();
        SeamBand band = bands.at(x, z);
        if (band == null) {
            return;
        }
        int[] twin = band.twinBlock(x, z);
        set(level, twin[0], pos.getY(), twin[1], state, band.turns());
        // Near a face corner the twin is copied into a second band as well; keep that one in step too.
        for (SeamBands.Column copy : bands.copiesOf(twin[0], twin[1])) {
            if (copy.band() != band) {
                set(level, copy.x(), pos.getY(), copy.z(), state, (band.turns() + 4 - copy.band().turns()) % 4);
            }
        }
    }

    private static void set(ServerLevel level, int x, int y, int z, BlockState state, int turns) {
        copying = true;
        try {
            level.setBlock(new BlockPos(x, y, z), state.rotate(rotation(turns)),
                    Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        } finally {
            copying = false;
        }
    }

    /** Clockwise quarter turns as Minecraft's {@link Rotation} (east turns into south, like {@code QuarterTurns}). */
    static Rotation rotation(int turns) {
        return switch (turns) {
            case 0 -> Rotation.NONE;
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            default -> Rotation.COUNTERCLOCKWISE_90;
        };
    }
}
