package io.github.chabelabela.worldengine.mixin;

import io.github.chabelabela.worldengine.level.UniverseTime;
import net.minecraft.core.Holder;
import net.minecraft.util.KeyframeTrackSampler;
import net.minecraft.world.attribute.modifier.AttributeModifier;
import net.minecraft.world.clock.ClockManager;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.timeline.AttributeTrackSampler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A timeline track keeps one value per tick. On local time ({@link UniverseTime}; M1f) the value depends on where it
 * is sampled, so those samples skip the cache, and the cache keeps the world clock's value.
 */
@Mixin(AttributeTrackSampler.class)
abstract class AttributeTrackSamplerMixin {
    @Shadow @Final private Holder<WorldClock> clock;
    @Shadow @Final private AttributeModifier<Object, Object> modifier;
    @Shadow @Final private KeyframeTrackSampler<Object> argumentSampler;
    @Shadow @Final private ClockManager clockManager;

    @Inject(method = "applyTimeBased", at = @At("HEAD"), cancellable = true)
    private void worldengine$localTime(Object value, int tickId, CallbackInfoReturnable<Object> cir) {
        if (clockManager instanceof UniverseTime.LocalClocks && UniverseTime.sampling()) {
            cir.setReturnValue(modifier.apply(value, argumentSampler.sample(clockManager.getInstance(clock).totalTicks())));
        }
    }
}
