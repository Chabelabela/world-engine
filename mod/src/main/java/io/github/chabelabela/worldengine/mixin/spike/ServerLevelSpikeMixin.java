package io.github.chabelabela.worldengine.mixin.spike;

import io.github.chabelabela.worldengine.spike.Spike;
import io.github.chabelabela.worldengine.spike.ShipSync;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * E4 option B (spike): every block change vanilla would send to clients passes through
 * {@code ServerLevel.sendBlockUpdated}; the ship sync collects the ones inside a ship's plot box here.
 */
@Mixin(ServerLevel.class)
abstract class ServerLevelSpikeMixin {
    @Inject(method = "sendBlockUpdated", at = @At("HEAD"))
    private void worldengine$shipBlockChanged(BlockPos pos, BlockState old, BlockState current, int flags, CallbackInfo ci) {
        if (Spike.ENABLED) {
            ShipSync.onBlockUpdated((ServerLevel) (Object) this, pos);
        }
    }
}
