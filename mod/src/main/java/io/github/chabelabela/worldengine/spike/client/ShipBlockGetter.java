package io.github.chabelabela.worldengine.spike.client;

import java.util.ArrayDeque;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;

/**
 * The ship's blocks as a level for Minecraft's block-model renderer, in local cell coordinates
 * ({@code 0..size-1}; everything outside is air). It carries its own tiny light engine, because the ship is
 * drawn somewhere other than where its blocks live:
 * <ul>
 *   <li>sky light falls from the top of the box and enters through the sides, at most {@code skyMax} (the world's
 *       sky light at the ship's pose), losing at least 1 per step, like vanilla;</li>
 *   <li>block light floods out from emitters (the lit lamp) the same way.</li>
 * </ul>
 */
final class ShipBlockGetter implements BlockAndTintGetter {
    private final int sx;
    private final int sy;
    private final int sz;
    private final BlockState[] states;
    private final byte[] sky;
    private final byte[] block;
    private final int skyMax;
    private final CardinalLighting cardinalLighting;

    ShipBlockGetter(ClientShipStore ship, int skyMax, CardinalLighting cardinalLighting) {
        this.sx = ship.sizeX();
        this.sy = ship.sizeY();
        this.sz = ship.sizeZ();
        this.skyMax = skyMax;
        this.cardinalLighting = cardinalLighting;
        int n = sx * sy * sz;
        states = new BlockState[n];
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    states[index(x, y, z)] = Block.stateById(ship.stateAt(x, y, z));
                }
            }
        }
        sky = new byte[n];
        block = new byte[n];
        computeLight();
    }

    private int index(int x, int y, int z) {
        return x + sx * (z + sz * y);
    }

    private boolean inside(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < sx && y < sy && z < sz;
    }

    private static int cost(BlockState state) {
        return Math.max(1, state.getLightDampening());
    }

    private void computeLight() {
        ArrayDeque<int[]> skyQueue = new ArrayDeque<>();
        ArrayDeque<int[]> blockQueue = new ArrayDeque<>();
        // Sky straight down each column, at full strength through blocks that let skylight pass.
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                int level = skyMax;
                for (int y = sy - 1; y >= 0 && level > 0; y--) {
                    BlockState s = states[index(x, y, z)];
                    if (!s.propagatesSkylightDown()) {
                        level = Math.max(0, level - cost(s));
                    }
                    if (level > sky[index(x, y, z)]) {
                        sky[index(x, y, z)] = (byte) level;
                        skyQueue.add(new int[] {x, y, z});
                    }
                }
            }
        }
        // Sky entering through the sides and the bottom of the box.
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    if (x == 0 || z == 0 || y == 0 || x == sx - 1 || z == sz - 1) {
                        int level = skyMax - cost(states[index(x, y, z)]);
                        if (level > sky[index(x, y, z)]) {
                            sky[index(x, y, z)] = (byte) level;
                            skyQueue.add(new int[] {x, y, z});
                        }
                    }
                    int emission = states[index(x, y, z)].getLightEmission();
                    if (emission > 0) {
                        block[index(x, y, z)] = (byte) emission;
                        blockQueue.add(new int[] {x, y, z});
                    }
                }
            }
        }
        flood(skyQueue, sky);
        flood(blockQueue, block);
    }

    private void flood(ArrayDeque<int[]> queue, byte[] light) {
        while (!queue.isEmpty()) {
            int[] p = queue.poll();
            int level = light[index(p[0], p[1], p[2])];
            for (Direction d : Direction.values()) {
                int x = p[0] + d.getStepX();
                int y = p[1] + d.getStepY();
                int z = p[2] + d.getStepZ();
                if (!inside(x, y, z)) {
                    continue;
                }
                int i = index(x, y, z);
                int next = level - cost(states[i]);
                if (next > light[i]) {
                    light[i] = (byte) next;
                    queue.add(new int[] {x, y, z});
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return inside(pos.getX(), pos.getY(), pos.getZ())
                ? states[index(pos.getX(), pos.getY(), pos.getZ())] : Blocks.AIR.defaultBlockState();
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
        return null; // block entities are drawn by their own renderers, not meshed (see E3 results)
    }

    @Override
    public int getBrightness(LightLayer layer, BlockPos pos) {
        if (!inside(pos.getX(), pos.getY(), pos.getZ())) {
            return layer == LightLayer.SKY ? skyMax : 0;
        }
        int i = index(pos.getX(), pos.getY(), pos.getZ());
        return layer == LightLayer.SKY ? sky[i] : block[i];
    }

    @Override
    public int getRawBrightness(BlockPos pos, int darkening) {
        return Math.max(getBrightness(LightLayer.SKY, pos) - darkening, getBrightness(LightLayer.BLOCK, pos));
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return LevelLightEngine.EMPTY; // unused: brightness is answered above
    }

    @Override
    public CardinalLighting cardinalLighting() {
        return cardinalLighting;
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver color) {
        return -1;
    }

    @Override
    public int getHeight() {
        return sy;
    }

    @Override
    public int getMinY() {
        return 0;
    }
}
