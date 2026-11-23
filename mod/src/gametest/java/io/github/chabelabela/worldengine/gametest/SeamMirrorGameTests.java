package io.github.chabelabela.worldengine.gametest;

import io.github.chabelabela.worldengine.level.PlanetTerrain;
import io.github.chabelabela.worldengine.level.SeamMirror;
import io.github.chabelabela.worldengine.planet.PlanetFace;
import io.github.chabelabela.worldengine.planet.PlanetLayout;
import io.github.chabelabela.worldengine.planet.Planets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Edit mirroring in a real server. Mirroring is switched on for the flat GameTest level, and the tests edit blocks at
 * the home planet's net coordinates there: in an overlap band, and on the face strip it copies.
 */
public class SeamMirrorGameTests {
    private static final PlanetLayout HOME = Planets.HOME.layout();
    private static final int N = Planets.HOME.cells();
    private static final int Y = 100;

    @GameTest
    public void aBlockPlacedInABandAppearsOnItsTwin(GameTestHelper helper) {
        int east = HOME.faceOriginX(PlanetFace.E3) + N;
        int west = HOME.faceOriginX(PlanetFace.E0);
        int z = HOME.faceOriginZ(PlanetFace.E3) + 300;
        // E3's east band copies E0's west strip, unturned: 10 blocks into the band is 10 blocks into E0.
        run(helper, new int[] {east + 10, z, west + 10, z}, level -> {
            level.setBlock(new BlockPos(east + 10, Y, z), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            expect(helper, level, west + 10, z, Blocks.STONE.defaultBlockState(), "stone mirrored onto E0");
            level.setBlock(new BlockPos(east + 10, Y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            expect(helper, level, west + 10, z, Blocks.AIR.defaultBlockState(), "breaking it is mirrored too");
        });
    }

    @GameTest
    public void aBlockPlacedOnTheFaceAppearsInTheBand(GameTestHelper helper) {
        int east = HOME.faceOriginX(PlanetFace.E3) + N;
        int west = HOME.faceOriginX(PlanetFace.E0);
        int z = HOME.faceOriginZ(PlanetFace.E0) + 400;
        run(helper, new int[] {west + 20, z, east + 20, z}, level -> {
            level.setBlock(new BlockPos(west + 20, Y, z), Blocks.GLASS.defaultBlockState(), Block.UPDATE_ALL);
            expect(helper, level, east + 20, z, Blocks.GLASS.defaultBlockState(), "glass mirrored into E3's band");
        });
    }

    @GameTest
    public void facingTurnsWithTheSeam(GameTestHelper helper) {
        // North's west band copies E0's north strip three quarter turns round (E2 in the M1b spike: running west
        // carries on running south), so stairs facing west in the band face south on E0.
        int ox = HOME.faceOriginX(PlanetFace.NORTH);
        int oz = HOME.faceOriginZ(PlanetFace.NORTH);
        int bandX = ox - 10;
        int bandZ = oz + 500;
        int[] twin = PlanetTerrain.bands().at(bandX, bandZ).twinBlock(bandX, bandZ);
        run(helper, new int[] {bandX, bandZ, twin[0], twin[1]}, level -> {
            BlockState stairs = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.WEST);
            level.setBlock(new BlockPos(bandX, Y, bandZ), stairs, Block.UPDATE_ALL);
            BlockState copy = level.getBlockState(new BlockPos(twin[0], Y, twin[1]));
            helper.assertTrue(copy.is(Blocks.OAK_STAIRS), "stairs mirrored onto E0, got " + copy);
            helper.assertTrue(copy.getValue(StairBlock.FACING) == Direction.SOUTH, "facing south, got " + copy);
            helper.assertTrue(Planets.at(twin[0], twin[1]).cell().face() == PlanetFace.E0, "the twin is on E0");
        });
    }

    private static void expect(GameTestHelper helper, ServerLevel level, int x, int z, BlockState state, String what) {
        BlockState got = level.getBlockState(new BlockPos(x, Y, z));
        helper.assertTrue(got == state, what + ": got " + got);
    }

    /** Loads the chunks at each {x, z} pair, mirrors edits in the test level while {@code body} runs, cleans up. */
    private static void run(GameTestHelper helper, int[] load, Consumer<ServerLevel> body) {
        ServerLevel level = helper.getLevel();
        List<ChunkPos> tickets = new ArrayList<>();
        for (int i = 0; i < load.length; i += 2) {
            ChunkPos pos = new ChunkPos(load[i] >> 4, load[i + 1] >> 4);
            level.getChunkSource().addTicketWithRadius(TicketType.FORCED, pos, 1);
            level.getChunk(pos.x(), pos.z());
            tickets.add(pos);
        }
        SeamMirror.enableFor(level);
        try {
            body.accept(level);
            helper.succeed();
        } finally {
            SeamMirror.disableFor(level);
            for (int i = 0; i < load.length; i += 2) {
                level.setBlock(new BlockPos(load[i], Y, load[i + 1]), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
            tickets.forEach(pos -> level.getChunkSource().removeTicketWithRadius(TicketType.FORCED, pos, 1));
        }
    }
}
