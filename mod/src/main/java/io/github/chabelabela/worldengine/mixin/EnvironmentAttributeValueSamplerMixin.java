package io.github.chabelabela.worldengine.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.chabelabela.worldengine.level.UniverseTime;
import net.minecraft.world.attribute.SpatialAttributeInterpolator;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;

/** Tells the universe level's clocks where an attribute is being sampled ({@link UniverseTime}; M1f). */
@Mixin(targets = "net.minecraft.world.attribute.EnvironmentAttributeSystem$ValueSampler")
abstract class EnvironmentAttributeValueSamplerMixin {
    @WrapMethod(method = "computeValuePositional")
    private Object worldengine$sampledAt(Vec3 position, SpatialAttributeInterpolator interpolator,
            Operation<Object> original) {
        return UniverseTime.sampledAt(position, () -> original.call(position, interpolator));
    }
}
