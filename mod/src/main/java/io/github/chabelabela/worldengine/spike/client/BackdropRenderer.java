package io.github.chabelabela.worldengine.spike.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import io.github.chabelabela.worldengine.planet.LevelShape;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.util.Mth;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3fc;

/**
 * E5's stand-in for M4's planet rendering: a sphere of the home planet's radius under the camera's column, its top at
 * sea level, textured with a coarse land/sea pattern that hazes into the fog colour at the horizon.
 * <ul>
 *   <li>Drawn at the start of the main pass (after the sky, before terrain) with vanilla's {@code DEBUG_QUADS}
 *       pipeline: no depth writes, no fog. Terrain covers it wherever terrain exists, so on the surface it only shows
 *       beyond the loaded chunks and through holes in them.</li>
 *   <li>In the ship frame it is drawn from the ship's surface-equivalent camera position, so it looks the same from
 *       both frames.</li>
 *   <li>Shrunk about the camera to fit inside the far plane. Only directions matter for something that never occludes
 *       anything.</li>
 * </ul>
 */
final class BackdropRenderer {
    /** The home planet's radius in blocks (ROADMAP §4.4). */
    static final double RADIUS = 10_430.0;
    /** The sphere's centre is drawn this far below the camera. */
    private static final double FIT = 400.0;
    private static final int RINGS = 48;
    private static final int SEGMENTS = 96;

    private final GpuBuffer[] buffers = new GpuBuffer[3];
    private int next;

    void draw(RenderPass pass) {
        Minecraft mc = Minecraft.getInstance();
        Vec3 camera = mc.gameRenderer.mainCamera().position();
        float partialTick = E5Client.partialTick();
        Vec3 surfaceCamera = E5Client.inShipFrame(camera) ? E5Client.surfaceEquivalent(camera, partialTick) : camera;
        double h = surfaceCamera.y - LevelShape.SEA_LEVEL;
        if (h < 2.0) {
            return;
        }
        double d = RADIUS + h;
        double scale = FIT / d;
        double r = RADIUS * scale;
        double thetaMax = Math.acos(RADIUS / d);
        Vector3fc fog = mc.gameRenderer.mainCamera().attributeProbe().getValue(EnvironmentAttributes.FOG_COLOR, partialTick);

        int quads = RINGS * SEGMENTS;
        try (ByteBufferBuilder bytes = new ByteBufferBuilder(quads * 4 * DefaultVertexFormat.POSITION_COLOR.getVertexSize())) {
            BufferBuilder builder = new BufferBuilder(bytes, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_COLOR);
            for (int i = 0; i < RINGS; i++) {
                // Rings crowd towards the horizon, where the sphere is seen edge-on.
                double t0 = thetaMax * ring(i / (double) RINGS);
                double t1 = thetaMax * ring((i + 1) / (double) RINGS);
                for (int j = 0; j < SEGMENTS; j++) {
                    double p0 = Math.PI * 2 * j / SEGMENTS;
                    double p1 = Math.PI * 2 * (j + 1) / SEGMENTS;
                    vertex(builder, surfaceCamera, r, t0, p0, thetaMax, fog);
                    vertex(builder, surfaceCamera, r, t0, p1, thetaMax, fog);
                    vertex(builder, surfaceCamera, r, t1, p1, thetaMax, fog);
                    vertex(builder, surfaceCamera, r, t1, p0, thetaMax, fog);
                }
            }
            try (MeshData mesh = builder.build()) {
                if (mesh == null) {
                    return;
                }
                if (buffers[next] != null) {
                    buffers[next].close();
                }
                GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "World Engine backdrop", GpuBuffer.USAGE_VERTEX,
                        mesh.vertexBuffer());
                buffers[next] = buffer;
                next = (next + 1) % buffers.length;
                int indexCount = quads * 6;
                RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
                pass.pushDebugGroup(() -> "World Engine backdrop");
                pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.DEBUG_QUADS));
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("DynamicTransforms", RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f(RenderSystem.getModelViewMatrixCopy())));
                pass.setVertexBuffer(0, buffer.slice());
                pass.setIndexBuffer(indices.getBuffer(indexCount), indices.type());
                pass.drawIndexed(indexCount, 1, 0, 0, 0);
                pass.popDebugGroup();
            }
        }
    }

    private static double ring(double u) {
        return 1.0 - (1.0 - u) * (1.0 - u);
    }

    /** A point at angle {@code theta} from the sub-camera point, azimuth {@code phi}, camera-relative and shrunk. */
    private static void vertex(BufferBuilder builder, Vec3 surfaceCamera, double r, double theta, double phi, double thetaMax,
                               Vector3fc fog) {
        double st = Math.sin(theta);
        float x = (float) (r * st * Math.cos(phi));
        float y = (float) (-FIT + r * Math.cos(theta));
        float z = (float) (r * st * Math.sin(phi));
        // Ground position this point stands for (arc length along the surface), for the pattern.
        double gx = surfaceCamera.x + RADIUS * theta * Math.cos(phi);
        double gz = surfaceCamera.z + RADIUS * theta * Math.sin(phi);
        double n = 0.65 * noise(gx / 900.0, gz / 900.0) + 0.35 * noise(gx / 160.0 + 17.3, gz / 160.0 - 4.1);
        float red;
        float green;
        float blue;
        if (n < 0.38) {
            red = 0.16F;
            green = 0.30F;
            blue = 0.55F;
        } else {
            float t = (float) Mth.clamp((n - 0.38) / 0.5, 0.0, 1.0);
            red = Mth.lerp(t, 0.30F, 0.46F);
            green = Mth.lerp(t, 0.50F, 0.42F);
            blue = Mth.lerp(t, 0.22F, 0.28F);
        }
        float haze = (float) Math.pow(theta / thetaMax, 3.0);
        red = Mth.lerp(haze, red, fog.x());
        green = Mth.lerp(haze, green, fog.y());
        blue = Mth.lerp(haze, blue, fog.z());
        builder.addVertex(x, y, z).setColor(red, green, blue, 1.0F);
    }

    /** Smooth value noise in [0, 1]. */
    private static double noise(double x, double z) {
        int xi = Mth.floor(x);
        int zi = Mth.floor(z);
        double fx = x - xi;
        double fz = z - zi;
        double sx = fx * fx * (3 - 2 * fx);
        double sz = fz * fz * (3 - 2 * fz);
        double a = hash(xi, zi);
        double b = hash(xi + 1, zi);
        double c = hash(xi, zi + 1);
        double e = hash(xi + 1, zi + 1);
        return Mth.lerp(sz, Mth.lerp(sx, a, b), Mth.lerp(sx, c, e));
    }

    private static double hash(int x, int z) {
        long h = x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return (h >>> 11) * 0x1.0p-53;
    }
}
