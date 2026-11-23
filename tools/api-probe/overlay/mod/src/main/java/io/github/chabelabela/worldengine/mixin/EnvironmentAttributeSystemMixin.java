package io.github.chabelabela.worldengine.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.chabelabela.worldengine.level.UniverseTime;
import java.util.function.Consumer;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.level.Level;
import net.minecraft.world.timeline.Timeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The universe level's timelines run on local time ({@link UniverseTime}; M1f). */
@Mixin(EnvironmentAttributeSystem.class)
abstract class EnvironmentAttributeSystemMixin {
    @WrapOperation(method = "addDynamicLayers", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/core/HolderSet;forEach(Ljava/util/function/Consumer;)V"))
    private static void worldengine$localTimelines(HolderSet<Timeline> timelines, Consumer<Holder<Timeline>> addEach,
            Operation<Void> original, @Local(argsOnly = true) EnvironmentAttributeSystem.Builder builder,
            @Local(argsOnly = true) Level level) {
        if (UniverseTime.isUniverse(level)) {
            UniverseTime.addTimelineLayers(builder, timelines, level.clockManager());
        } else {
            original.call(timelines, addEach);
        }
    }
}
