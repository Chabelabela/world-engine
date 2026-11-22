package io.github.chabelabela.worldengine.mixin.spike;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.chabelabela.worldengine.spike.client.SpikeClient;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Spike (E5, {@code FIX_RENDERER}): vanilla's section mesher sees the plot's ship blocks as air, so in the ship frame
 * the ship is drawn only by the spike's renderer, exactly as in the surface frame. Collision and interaction still use
 * the real blocks. Runs on meshing worker threads.
 */
@Mixin(RenderSectionRegion.class)
abstract class RenderSectionRegionSpikeMixin {
    @ModifyReturnValue(method = "getBlockState", at = @At("RETURN"))
    private BlockState worldengine$hideShip(BlockState original, @Local(argsOnly = true) BlockPos pos) {
        return SpikeClient.hideFromVanilla(pos) ? Blocks.AIR.defaultBlockState() : original;
    }
}
