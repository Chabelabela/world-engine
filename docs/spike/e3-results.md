# E3: ship rendering at a transform (Q2)

Spike branch only. Input for ADR 0001. Run on 2026-11-21.

**Q2: met.** The ship's blocks, taken from the client's own copy (E4 option B), are drawn at any position and rotation through 26.3's GPU API, with Minecraft's own block models and pipelines.
- With and without Sodium (0.9.2+mc26.3), with no conflict.
- The test ship costs **about 0.01 ms of frame time**: 23–25 µs CPU and 9–10 µs GPU per frame. The bar is ≤ 1 ms.

## How it's built (the API path)

1. **Mesh, once per change** (`ShipRenderer.rebuild`):
   - `ModelBlockRenderer.tesselateBlock` writes into a `BufferBuilder` in vanilla's terrain vertex format, `DefaultVertexFormat.BLOCK`.
   - One buffer per `ChunkSectionLayer` (solid, cutout, translucent), exactly as `SectionCompiler` does.
   - Each is uploaded with `GpuDevice.createBuffer(label, GpuBuffer.USAGE_VERTEX, data)`.
   - The "level" it reads from is `ShipBlockGetter`, a `BlockAndTintGetter` over the client's ship copy in local cell coordinates, with its own small light flood:
     - sky light from the top and sides of the box, capped at the world's sky light just above the ship;
     - block light from emitters.
2. **Draw, every frame**, inside vanilla's main render pass:
   - Mixin `LevelRendererSpikeMixin` injects at TAIL of `LevelRenderer.executeSolid`, capturing the open `RenderPass` with MixinExtras `@Local(argsOnly = true)`.
   - Pipelines are vanilla's moving-block ones, `RenderPipelines.SOLID_BLOCK` / `CUTOUT_BLOCK` / `TRANSLUCENT_BLOCK`. They take a model-view matrix, which terrain's pipelines don't (terrain only gets a per-section offset).
   - Per layer: `setPipeline`, `RenderSystem.bindDefaultUniforms`, `Sampler0` = block atlas, `Sampler2` = lightmap, the vertex buffer, `RenderSystem.getSequentialBuffer(QUADS)` as the index buffer.
   - Per copy: `DynamicTransforms` = `RenderSystem.getDynamicUniforms().writeTransform(view × translate(pose − camera) × rotate(q) × translate(−centre))`, then `drawIndexed`.
3. **Pose:**
   - The server sends `ship_pose`: position, quaternion, a spin rate about a tilted axis (animated on the client) and a copy count for measurements.
   - Commands: `/we spike pose|rot|spin`, `/we spike e3 copies N`.
4. **Timing:**
   - CPU is `System.nanoTime` around the recording.
   - GPU is `RenderPass.writeTimestamp` before and after the ship's draws, read back four frames later and multiplied by `DeviceInfo.timestampPeriod()`.
   - Frame time is between consecutive frames, logged in 5 s windows (`[spike] E3 window …`).

The test ship (217 blocks, now with two stained-glass windows) meshes to **540–542 quads**: about 357 solid, 2 cutout and 183 translucent. In 26.3 plain glass is on the translucent layer.

## Results

Setup:
- Dedicated server, auto-joining client, RTX 3070, windowed at the default size.
- Vsync off, fps cap off (`maxFps:260`), render distance 16, noon.
- The ship tilted (yaw 30°, pitch 20°, roll 15°) and spinning at 25°/s, about 10 blocks from the player.
- Each copy count was held for 11 s. The table uses only the 5 s windows that fall entirely inside one setting.

| Copies | Vanilla: CPU / GPU per frame | Vanilla: frame time | Sodium: CPU / GPU per frame | Sodium: frame time |
|---|---|---|---|---|
| 0 | — | 0.793 ms | — | 0.763–0.774 ms |
| 1 | 23 / 10 µs | 0.804 ms (+0.011) | 25 / 9 µs | 0.801 ms |
| 10 | 38 / 35 µs | 0.821 ms | 39 / 34 µs | 0.805 ms |
| 100 | 179 / 246 µs | 0.984 ms | 179 / 245 µs | 0.947 ms |
| 1,000 | 1,568 / 2,466 µs | 2.777 ms | 1,574 / 2,471 µs | 2.787 ms |

- **Per extra copy at scale:** about 1.6 µs CPU and 2.5 µs GPU (≈ 4.5 ns per quad). The fixed cost of the first copy is about 20 µs CPU, for pipeline and uniform setup.
- **Budget:** about 400 test-ship-sized ships fit in 1 ms of GPU time with no culling at all. The renderer draws every copy, including those off screen.
- **Re-meshing:** 1.1–1.9 ms warm (26–35 ms on the first build, JIT), synchronous on the render thread, so each edit causes a one-frame hitch.
- **Sodium vs vanilla:** the same within noise, as expected. The ship's draw doesn't go through the terrain renderer, and Sodium's mixins didn't touch `executeSolid` in a way that broke the hook.

## Visual check

Screenshots are in `docs/spike/e3/`.

| What | Result |
|---|---|
| Shape, textures, facing blocks (stairs, door, lever) | Correct at any rotation (1, 2, 3) |
| Rotation about an arbitrary axis | Smooth, no seams or cracks between blocks (2) |
| Depth against terrain | Correct with vanilla and Sodium: the buried part of the hull is hidden by grass (6) |
| Interior lighting | Sky through the glass by day (3); the lit lamp lights the cabin at night, falling off with distance (4) |
| Translucency (glass, stained glass) | Looks right from tested angles, but not sorted and drawn before world translucents (see errors) |

**Visual errors and gaps:**
1. **Block entities are missing.** The chest isn't drawn: chests, signs, banners and the like are drawn by block-entity renderers, not meshed. Fixing it needs client-side `BlockEntity` instances, built from the synced update tags, submitted through the submit/feature pipeline with the ship's pose.
2. **Translucent layer order:**
   - The ship's translucent quads are drawn in the solid pass, after opaque features, with blending.
   - They aren't sorted back to front (vanilla sorts per section with `MeshData.sortQuads`).
   - They're drawn before translucent terrain, water and clouds, so ship glass seen in front of water will look wrong.
   - With "improved transparency" (OIT) on, the ship needs the `OIT_TRANSLUCENT_BLOCK` pipelines.
3. **Lighting is baked into the mesh:**
   - The face shading (cardinal lighting) and the ship's light are computed in the ship's frame, so a tilted ship keeps "up"-face shading on its local top.
   - World light changes (the sky light above the ship, block light from nearby world lamps) only reach the ship when it re-meshes.
4. **Bug found and fixed in this run:** the world sky light was first sampled at the pose's own block. When the ship dipped into terrain that read 0, and the whole ship went black (5). It's now sampled just above the ship's box.
5. **No collision:** a player standing "in" the drawn ship falls through it, because the blocks live in the plot. Collision and riding are E5/M2.

## Decision input for the ADR

- **Q2 → yes.** The rendering path is vanilla's own:
  - meshing with `ModelBlockRenderer` into `DefaultVertexFormat.BLOCK`;
  - `GpuDevice.createBuffer`;
  - drawing with the moving-block pipelines and a dynamic transform, inside the main pass.
- It needs no shaders of its own and no raw GL/Vulkan, so it should survive the Vulkan backend. Not tested on a 26.4 Vulkan snapshot; see open points.
- **Mixin required:** `LevelRenderer.executeSolid`, TAIL, capturing the `RenderPass` argument. Risk: low.
  - It doesn't change vanilla's behaviour.
  - Sodium 0.9.2 coexists.
  - An injection-point change in a future version breaks it loudly (`defaultRequire: 1`).
  - It's needed because Fabric's `LevelRenderEvents.AFTER_OPAQUE_TERRAIN` fires inside the open pass without exposing it, and only one render pass can be open at a time. Worth asking Fabric for the pass in that context.
- **For M2:**
  - Mesh off the render thread, per 16³ section of the ship.
  - Frustum-cull ships and copies.
  - Sort translucent quads.
  - Render block entities through the submit pipeline.
  - Decide how world light reaches a moving ship, e.g. sample at the ship's bounds each second and re-light without re-meshing, via the lightmap UV.

## Open points

- The **26.4 snapshot with Vulkan** wasn't tried. It needs a second dev setup on a snapshot.
- Many different ships, rather than copies of one mesh, will add buffer binds; one big ship will add vertices. Both are linear in the numbers above.
- The E1 missing-chunk metric still checks only chunk data under Sodium. A Sodium-aware check would need Sodium's API.

## API notes (26.3)

- **The GPU API is its own package:** `com.mojang.renderpearl.api` (`GpuDevice`, `GpuBuffer`, `RenderPass`, `RenderPipeline`, `CommandEncoder`, `GpuQueryPool`…). `com.mojang.blaze3d.systems.RenderSystem` remains the façade (`getDevice`, `getDynamicUniforms`, `getSequentialBuffer`, `bindDefaultUniforms`, `getCompiledPipeline`).
- **Pipelines take bind-group layouts:** `RenderPipelines.BLOCK_SNIPPET` = `PROJECTION`, `DYNAMIC_TRANSFORMS`, `FOG`, `SAMPLER0`, `SAMPLER2`, `GLOBALS`.
  - `SOLID_BLOCK` / `CUTOUT_BLOCK` / `TRANSLUCENT_BLOCK` are the moving-block pipelines and use the terrain vertex format.
  - Terrain pipelines use `CHUNK_SECTION` / `TERRAIN_INFO` and can't rotate.
- **Timestamps:**
  - `RenderPass.writeTimestamp(GpuQueryPool, index)` works inside a pass.
  - `GpuDevice.createTimestampQueryPool(n)`.
  - Values × `DeviceInfo.timestampPeriod()` = ns.
- **Moved and renamed classes:**
  - `BlockAndTintGetter` moved to `net.minecraft.client.renderer.block` and gained `cardinalLighting()`.
  - Lighting reads go through `BlockAndLightGetter.getBrightness`, which a custom level can override instead of supplying a light engine.
- **Coloured blocks** are `ColorCollection<Block>`s: `Blocks.STAINED_GLASS.pick(DyeColor.LIGHT_BLUE)`, not `LIGHT_BLUE_STAINED_GLASS`.
- **Fabric** `LevelRenderEvents` (formerly `WorldRenderEvents`) has `AFTER_OPAQUE_TERRAIN`, `COLLECT_SUBMITS`, `AFTER_SOLID_FEATURES` and more. Contexts give a `SubmitNodeCollector` and `PoseStack`, not the `RenderPass`.
- **Gamerule names changed:** `gamerule doDaylightCycle` is rejected in 26.3.
