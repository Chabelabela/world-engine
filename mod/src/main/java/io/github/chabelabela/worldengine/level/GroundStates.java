package io.github.chabelabela.worldengine.level;

import io.github.chabelabela.worldengine.planet.Ground;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** The block state for each {@link Ground}. A separate class so blocks are looked up after bootstrap. */
final class GroundStates {
    private static final BlockState[] STATES = new BlockState[Ground.values().length];

    static {
        put(Ground.AIR, Blocks.AIR);
        put(Ground.WATER, Blocks.WATER);
        put(Ground.BEDROCK, Blocks.BEDROCK);
        put(Ground.STONE, Blocks.STONE);
        put(Ground.DIRT, Blocks.DIRT);
        put(Ground.GRASS, Blocks.GRASS_BLOCK);
        put(Ground.SAND, Blocks.SAND);
        put(Ground.SANDSTONE, Blocks.SANDSTONE);
        put(Ground.GRAVEL, Blocks.GRAVEL);
        put(Ground.SNOW, Blocks.SNOW_BLOCK);
    }

    private GroundStates() {
    }

    static BlockState of(Ground ground) {
        return STATES[ground.ordinal()];
    }

    private static void put(Ground ground, Block block) {
        STATES[ground.ordinal()] = block.defaultBlockState();
    }
}
