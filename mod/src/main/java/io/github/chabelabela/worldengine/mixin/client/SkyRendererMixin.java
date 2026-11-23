package io.github.chabelabela.worldengine.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.commands.RenderPass;
import io.github.chabelabela.worldengine.client.UniverseSky;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.world.level.MoonPhase;
import org.joml.Matrix4f;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Turns the sun, moon, stars and sunrise glow to the camera's place on its planet ({@link UniverseSky}; M1f). */
@Mixin(SkyRenderer.class)
abstract class SkyRendererMixin {
    @Unique
    private Matrix4f worldengine$tilt;

    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void worldengine$tiltForCamera(ClientLevel level, float partialTick, Camera camera, SkyRenderState state,
            CallbackInfo ci) {
        worldengine$tilt = UniverseSky.tiltAt(level, camera.position());
    }

    @Inject(method = "renderSunMoonAndStars", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V", ordinal = 0, shift = At.Shift.AFTER))
    private void worldengine$tiltCelestialBodies(RenderPass pass, PoseStack poseStack, float sunAngle, float moonAngle,
            float starAngle, MoonPhase moonPhase, float rainBrightness, float starBrightness, CallbackInfo ci) {
        if (worldengine$tilt != null) {
            poseStack.mulPose(worldengine$tilt);
        }
    }

    @Inject(method = "renderSunriseAndSunset", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V", ordinal = 0, shift = At.Shift.AFTER))
    private void worldengine$tiltSunriseGlow(RenderPass pass, PoseStack poseStack, float sunAngle, Vector4fc color,
            CallbackInfo ci) {
        if (worldengine$tilt != null) {
            poseStack.mulPose(worldengine$tilt);
        }
    }
}
