package io.github.chabelabela.worldengine.mixin.spike;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.chabelabela.worldengine.spike.client.SpikeClient;
import net.minecraft.world.attribute.EnvironmentAttributeProbe;
import net.minecraft.world.attribute.GaussianSampler;
import net.minecraft.world.attribute.SpatialAttributeInterpolator;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Spike (E5, {@code FIX_SKY}), client only: in the ship frame the camera's attribute probe (sky, fog and cloud
 * colours) keeps the biome blend of the last surface tick instead of sampling the shipyard's void biome, and positional
 * attributes see the ship's surface-equivalent position. Time of day still applies.
 */
@Mixin(EnvironmentAttributeProbe.class)
abstract class EnvironmentAttributeProbeSpikeMixin {
    @ModifyVariable(method = "tick", at = @At("HEAD"), argsOnly = true)
    private Vec3 worldengine$position(Vec3 position) {
        return SpikeClient.probePosition((EnvironmentAttributeProbe) (Object) this, position);
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/attribute/SpatialAttributeInterpolator;clear()V"))
    private void worldengine$keepBlend(SpatialAttributeInterpolator interpolator, Operation<Void> original) {
        if (!SpikeClient.biomesFrozen((EnvironmentAttributeProbe) (Object) this)) {
            original.call(interpolator);
        }
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/attribute/GaussianSampler;sample(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/attribute/GaussianSampler$Sampler;Lnet/minecraft/world/attribute/GaussianSampler$Accumulator;)V"))
    private void worldengine$skipSample(Vec3 position, GaussianSampler.Sampler<?> sampler,
                                        GaussianSampler.Accumulator<?> accumulator, Operation<Void> original) {
        if (!SpikeClient.biomesFrozen((EnvironmentAttributeProbe) (Object) this)) {
            original.call(position, sampler, accumulator);
        }
    }
}
