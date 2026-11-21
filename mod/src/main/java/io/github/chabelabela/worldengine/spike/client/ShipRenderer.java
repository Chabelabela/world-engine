package io.github.chabelabela.worldengine.spike.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import io.github.chabelabela.worldengine.WorldEngineMod;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * E3: draws the ship from the client's copy ({@link ClientShipStore}) at its pose, through 26.3's GPU API.
 * <ul>
 *   <li><b>Mesh, once per change:</b> {@link ModelBlockRenderer#tesselateBlock} into vanilla's terrain vertex
 *       format ({@code DefaultVertexFormat.BLOCK}), one buffer per layer, uploaded with
 *       {@code GpuDevice.createBuffer(VERTEX)}.</li>
 *   <li><b>Draw, every frame:</b> inside vanilla's main render pass (a mixin at the end of
 *       {@code LevelRenderer.executeSolid}), with vanilla's moving-block pipelines ({@code SOLID_BLOCK},
 *       {@code CUTOUT_BLOCK}, {@code TRANSLUCENT_BLOCK}), whose vertex shader takes a model-view matrix: the camera
 *       view times the ship's pose.</li>
 * </ul>
 * It times itself: CPU time to record the draws, and GPU time between two timestamps written into the pass.
 */
final class ShipRenderer {
    /** Copies are laid out in a square grid this far apart, for frame-time measurements. */
    private static final float COPY_SPACING = 12.0F;
    private static final ChunkSectionLayer[] LAYERS = {ChunkSectionLayer.SOLID, ChunkSectionLayer.CUTOUT, ChunkSectionLayer.TRANSLUCENT};
    private static final int QUERY_SLOTS = 4;

    private final ClientShipStore ship;
    private int builtVersion = -1;
    private int builtSkyMax = -1;
    private final Map<ChunkSectionLayer, GpuBuffer> buffers = new EnumMap<>(ChunkSectionLayer.class);
    private final Map<ChunkSectionLayer, Integer> quads = new EnumMap<>(ChunkSectionLayer.class);
    private int totalQuads;
    private double lastBuildMs;

    private GpuQueryPool queries;
    private final boolean[] queryPending = new boolean[QUERY_SLOTS];
    private int frame;

    // Measurement window.
    private long windowStart = System.nanoTime();
    private long lastFrameNanos;
    private long frameNanosSum;
    private int frames;
    private long cpuNanosSum;
    private long gpuNanosSum;
    private int gpuSamples;
    private String summary = "";

    ShipRenderer(ClientShipStore ship) {
        this.ship = ship;
    }

    /** Called every frame inside the main render pass, after opaque terrain and solid features. */
    void draw(RenderPass pass) {
        long start = System.nanoTime();
        if (lastFrameNanos != 0) {
            frameNanosSum += start - lastFrameNanos;
            frames++;
        }
        lastFrameNanos = start;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && ship.has() && ship.copies() > 0) {
            int skyMax = mc.level.getBrightness(LightLayer.SKY, BlockPos.containing(ship.posePos()));
            if (builtVersion != ship.version() || builtSkyMax != skyMax) {
                rebuild(mc, skyMax);
            }
            if (totalQuads > 0) {
                record(mc, pass);
            }
            cpuNanosSum += System.nanoTime() - start;
        }
        if (start - windowStart >= 5_000_000_000L) {
            closeWindow(start);
        }
    }

    private void record(Minecraft mc, RenderPass pass) {
        if (queries == null) {
            queries = RenderSystem.getDevice().createTimestampQueryPool(QUERY_SLOTS * 2);
        }
        int slot = frame++ % QUERY_SLOTS;
        if (queryPending[slot]) {
            OptionalLong[] t = queries.getValues(slot * 2, 2);
            if (t[0].isPresent() && t[1].isPresent()) {
                gpuNanosSum += (long) ((t[1].getAsLong() - t[0].getAsLong())
                        * RenderSystem.getDevice().getDeviceInfo().timestampPeriod());
                gpuSamples++;
            }
        }
        pass.writeTimestamp(queries, slot * 2);

        Vec3 camera = mc.gameRenderer.mainCamera().position();
        Vec3 pose = ship.posePos();
        Quaternionf rotation = ship.rotationNow();
        Matrix4f view = RenderSystem.getModelViewMatrixCopy();
        int copies = ship.copies();
        int side = (int) Math.ceil(Math.sqrt(copies));
        GpuBufferSlice[] transforms = new GpuBufferSlice[copies];
        for (int i = 0; i < copies; i++) {
            float ox = (i % side - (side - 1) / 2.0F) * COPY_SPACING;
            float oz = (i / side - (side - 1) / 2.0F) * COPY_SPACING;
            Matrix4f model = new Matrix4f(view)
                    .translate((float) (pose.x - camera.x) + ox, (float) (pose.y - camera.y), (float) (pose.z - camera.z) + oz)
                    .rotate(rotation)
                    .translate(-ship.sizeX() / 2.0F, -ship.sizeY() / 2.0F, -ship.sizeZ() / 2.0F);
            transforms[i] = RenderSystem.getDynamicUniforms().writeTransform(model);
        }

        AbstractTexture atlas = mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
        RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        pass.pushDebugGroup(() -> "World Engine ship");
        for (ChunkSectionLayer layer : LAYERS) {
            GpuBuffer buffer = buffers.get(layer);
            if (buffer == null) {
                continue;
            }
            int indexCount = quads.get(layer) * 6;
            pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline(layer)));
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("Sampler0", atlas.getTextureView(), atlas.getSampler());
            pass.setUniform("Sampler2", mc.gameRenderer.lightmap(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.setVertexBuffer(0, buffer.slice());
            pass.setIndexBuffer(indices.getBuffer(indexCount), indices.type());
            for (GpuBufferSlice transform : transforms) {
                pass.setUniform("DynamicTransforms", transform);
                pass.drawIndexed(indexCount, 1, 0, 0, 0);
            }
        }
        pass.popDebugGroup();
        pass.writeTimestamp(queries, slot * 2 + 1);
        queryPending[slot] = true;
    }

    private static RenderPipeline pipeline(ChunkSectionLayer layer) {
        return switch (layer) {
            case SOLID -> RenderPipelines.SOLID_BLOCK;
            case CUTOUT -> RenderPipelines.CUTOUT_BLOCK;
            case TRANSLUCENT -> RenderPipelines.TRANSLUCENT_BLOCK;
        };
    }

    /** Re-meshes the whole ship (it's small; a big ship would re-mesh per 16³ section). */
    private void rebuild(Minecraft mc, int skyMax) {
        long start = System.nanoTime();
        closeBuffers();
        CardinalLighting cardinal = mc.level != null ? mc.level.cardinalLighting() : CardinalLighting.DEFAULT;
        ShipBlockGetter level = new ShipBlockGetter(ship, skyMax, cardinal);
        ModelBlockRenderer renderer = new ModelBlockRenderer(mc.options.ambientOcclusion().get(), true, mc.getBlockColors());
        boolean cutoutLeaves = mc.options.cutoutLeaves().get();
        Map<ChunkSectionLayer, ByteBufferBuilder> memory = new EnumMap<>(ChunkSectionLayer.class);
        Map<ChunkSectionLayer, BufferBuilder> builders = new EnumMap<>(ChunkSectionLayer.class);
        java.util.function.Function<ChunkSectionLayer, BufferBuilder> builderFor = layer -> builders.computeIfAbsent(layer, l -> {
            ByteBufferBuilder bytes = new ByteBufferBuilder(64 * 1024);
            memory.put(l, bytes);
            return new BufferBuilder(bytes, PrimitiveTopology.QUADS, l.vertexFormat());
        });
        BlockQuadOutput output = (x, y, z, quad, instance) ->
                builderFor.apply(quad.materialInfo().layer()).putBlockBakedQuad(x, y, z, quad, instance);
        BlockQuadOutput opaqueOutput = (x, y, z, quad, instance) ->
                builderFor.apply(ChunkSectionLayer.SOLID).putBlockBakedQuad(x, y, z, quad, instance);
        BlockModelLighter.enableCaching();
        try {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int y = 0; y < ship.sizeY(); y++) {
                for (int z = 0; z < ship.sizeZ(); z++) {
                    for (int x = 0; x < ship.sizeX(); x++) {
                        pos.set(x, y, z);
                        BlockState state = level.getBlockState(pos);
                        if (state.isAir() || state.getRenderShape() != RenderShape.MODEL) {
                            continue;
                        }
                        renderer.tesselateBlock(ModelBlockRenderer.forceOpaque(cutoutLeaves, state) ? opaqueOutput : output,
                                x, y, z, level, pos.immutable(), state,
                                mc.getModelManager().getBlockStateModelSet().get(state), state.getSeed(pos));
                    }
                }
            }
        } finally {
            BlockModelLighter.clearCache();
        }
        totalQuads = 0;
        StringBuilder perLayer = new StringBuilder();
        for (Map.Entry<ChunkSectionLayer, BufferBuilder> e : builders.entrySet()) {
            try (MeshData mesh = e.getValue().build()) {
                if (mesh == null) {
                    continue;
                }
                int layerQuads = mesh.drawState().vertexCount() / 4;
                ChunkSectionLayer layer = e.getKey();
                buffers.put(layer, RenderSystem.getDevice().createBuffer(() -> "World Engine ship " + layer.label(),
                        GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer()));
                quads.put(layer, layerQuads);
                totalQuads += layerQuads;
                perLayer.append(' ').append(layer.label()).append('=').append(layerQuads);
            }
        }
        memory.values().forEach(ByteBufferBuilder::close);
        builtVersion = ship.version();
        builtSkyMax = skyMax;
        lastBuildMs = (System.nanoTime() - start) / 1e6;
        WorldEngineMod.LOGGER.info("[spike] E3 mesh: {} quads ({}), sky {}, built in {} ms",
                totalQuads, perLayer.toString().trim(), skyMax, String.format(Locale.ROOT, "%.2f", lastBuildMs));
    }

    private void closeWindow(long now) {
        double seconds = (now - windowStart) / 1e9;
        double frameMs = frames == 0 ? 0 : frameNanosSum / 1e6 / frames;
        double cpuUs = frames == 0 ? 0 : cpuNanosSum / 1e3 / frames;
        double gpuUs = gpuSamples == 0 ? 0 : gpuNanosSum / 1e3 / gpuSamples;
        int copies = ship.has() ? ship.copies() : 0;
        summary = String.format(Locale.ROOT, "ship render: %d copies x %d quads | cpu %.1f us, gpu %.1f us /frame | frame %.2f ms (%.0f fps)",
                copies, totalQuads, cpuUs, gpuUs, frameMs, frameMs == 0 ? 0 : 1000 / frameMs);
        WorldEngineMod.LOGGER.info("[spike] E3 window {}s: copies={} quads={} cpuUs={} gpuUs={} frameMs={} frames={} gpuSamples={}",
                String.format(Locale.ROOT, "%.1f", seconds), copies, totalQuads, String.format(Locale.ROOT, "%.2f", cpuUs),
                String.format(Locale.ROOT, "%.2f", gpuUs), String.format(Locale.ROOT, "%.3f", frameMs), frames, gpuSamples);
        windowStart = now;
        frameNanosSum = 0;
        frames = 0;
        cpuNanosSum = 0;
        gpuNanosSum = 0;
        gpuSamples = 0;
    }

    String hudLine() {
        return summary.isEmpty() ? null : summary;
    }

    private void closeBuffers() {
        buffers.values().forEach(GpuBuffer::close);
        buffers.clear();
        quads.clear();
    }
}
