package io.github.chabelabela.worldengine.mixin.spike;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import io.github.chabelabela.worldengine.spike.client.SpikeClient;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Spike (E5), client only: the local player collides with the drawn ship. Its block shapes join the "entity"
 * colliders that {@code Entity.collide} gathers (like a boat's or a shulker's), at the ship's current tick pose.
 */
@Mixin(Entity.class)
abstract class EntitySpikeMixin {
    @ModifyExpressionValue(method = "collide", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getEntityCollisions(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"))
    private List<VoxelShape> worldengine$shipColliders(List<VoxelShape> colliders, Vec3 movement) {
        Entity self = (Entity) (Object) this;
        AABB area = self.getBoundingBox().expandTowards(movement).expandTowards(0.0, self.maxUpStep(), 0.0);
        return SpikeClient.withShipCollisions(self, area, colliders);
    }
}
