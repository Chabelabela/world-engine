package io.github.chabelabela.worldengine.mixin;

import io.github.chabelabela.worldengine.level.SeamMirror;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every block change vanilla sends to clients passes through {@code ServerLevel.sendBlockUpdated}; edits in an
 * overlap band or the face strip it copies are mirrored to the other side from here (M1e-2). The M1b spike used the
 * same hook for ship edits.
 */
@Mixin(ServerLevel.class)
abstract class ServerLevelMixin {
    @Inject(method = "sendBlockUpdated", at = @At("HEAD"))
    private void worldengine$mirrorSeamEdits(BlockPos pos, BlockState old, BlockState current, int flags,
            CallbackInfo ci) {
        SeamMirror.onBlockUpdated((ServerLevel) (Object) this, pos, current);
    }
}
