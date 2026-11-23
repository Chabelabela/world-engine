package io.github.chabelabela.worldengine.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.chabelabela.worldengine.level.UniverseTime;
import net.minecraft.world.attribute.SpatialAttributeInterpolator;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Tells the universe level's clocks where an attribute is being sampled, and that a value asked for without a
 * position has none ({@link UniverseTime}; M1f).
 */
@Mixin(targets = "net.minecraft.world.attribute.EnvironmentAttributeSystem$ValueSampler")
abstract class EnvironmentAttributeValueSamplerMixin {
    @WrapMethod(method = "computeValuePositional")
    private Object worldengine$sampledAt(Vec3 position, SpatialAttributeInterpolator interpolator,
            Operation<Object> original) {
        return UniverseTime.sampledAt(position, () -> original.call(position, interpolator));
    }

    /**
     * A value asked for without a position, even in the middle of a positional sample, reads the world clock (and is
     * cached for the tick).
     */
    @WrapMethod(method = "computeValueNotPositional")
    private Object worldengine$withoutPosition(Operation<Object> original) {
        return UniverseTime.sampledAt(null, original::call);
    }
}
