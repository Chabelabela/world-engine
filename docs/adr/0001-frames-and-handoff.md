# ADR 0001: Frames and the surface ↔ ship handoff

- Status: proposed
- Date: 2026-11-22
- Deciders: Chabelabela (owner)
- Evidence: the M1b spike, experiments E1–E6, run 2026-11-21/22 on Minecraft 26.3 with Fabric, on the [`spike/m1-handoff`](https://github.com/Chabelabela/world-engine/tree/spike/m1-handoff) branch. The links below need that branch on GitHub. The spike code stays there and doesn't merge.

## Context

[ROADMAP §4.3](../ROADMAP.md) reconciles Minecraft's flat world with spherical planets and orbits through three frames:
- **Surface frame:** a planet's cube-sphere faces, laid out as flat regions of one universe level, with vanilla −Y gravity.
- **Ship frame:** the ship's plot in a shipyard far from all planets, where its blocks always live, axis-aligned.
- **Universe frame:** the f64 simulation that is the truth for all bodies.

A ship is drawn and collided at its simulated transform. Players aboard stay in the surface frame while it flies low. When it climbs past about 1.5 km (back below about 1.2 km), they are handed into the ship frame (and back), with no loading screen and no visual pop.

The handoff is the riskiest part of M1. Vanilla has no notion of standing inside something that moves. Teleports, chunk loading, rendering, entity sync and the server's movement checks all assume a static world, and a wrong choice here shapes M1e, M1f, M2, M3 and M4. The [M1b spike](../superpowers/plans/2026-11-20-m1b-handoff-spike.md) tested it with a 7×5×9 test ship before any of that is built.

## Options considered

| Area | Options | Chosen | Why |
|---|---|---|---|
| **Where frames live** | (a) One universe level: planet faces as regions, shipyard plots at x ≈ 20,000,000. (b) Separate levels per planet (and for the shipyard), with cross-level transfers. | **(a)** | Same-level teleports over 28 million blocks never showed a loading screen (E1). M1d already built (a). (b) wasn't built: vanilla's level change resets the client level, which (a) avoids entirely. |
| **Ship blocks to the client** | (A) Vanilla chunk tracking, extended to keep far plot chunks on clients near the ship. (B) A ship snapshot payload on first sight, then block deltas, into a client-side ship copy. | **(B)** | Built and correct: 13/13 hash checks (E4). About 225 B on the wire for the test ship against about 380 B, and it grows with the ship's box, not with chunk columns. One optional mixin against about six deep ones in chunk loading, which Sodium and C2ME also patch. The copy is on the client before any handoff. |
| **Ship rendering** | (a) Vanilla meshes the plot's real blocks: possible only in the ship frame. (b) World Engine meshes the client copy with vanilla's block models and draws it at the transform with vanilla's moving-block pipelines, in both frames. | **(b)** | 0.011 ms per ship, vanilla and Sodium (E3). Using two renderers is itself the biggest pop: the ship vanishes for 13–14 frames at the switch (E5). |
| **Placing players at the switch** | (a) From the server's copy of the player. (b) From the client's own report of where it stands relative to the ship, plus a pose-tick snap on the way back. (c) Either, hidden by a fade mask (E7). | **(b)** | The client is always 1–2 ticks behind the server's pose, so (a) is 0.5–1 block off. (b) was exact in all 36 handoffs (E5, E6). (c) isn't needed: E5 met its bar without it. |
| **Other players aboard** | (a) Vanilla entity sync, in world coordinates. (b) Each player's ship-relative report, relayed by the server, drawn against the viewer's own drawn ship. | **(b)** | (a) draws them 1 block behind a ship moving at 10 blocks/s, and up to 8.5 blocks off after the hand-back. (b): 0.000 in every phase (E6). |

## Decision

- **One universe level.** Planet faces and the shipyard share it, and every frame change is a server-initiated, same-level teleport.
- **Ship blocks:** clients get each ship's blocks as a snapshot payload on first sight, then deltas. They keep their own copy, independent of vanilla's chunks.
- **Rendering:** World Engine draws every ship itself from that copy, in both frames. Vanilla's mesher never draws a ship's plot.
- **In the surface frame**, players aboard are carried by their own client:
  - one ship pose per tick, applied at the start of the player's tick;
  - collision with the ship's block shapes;
  - a report of their ship-relative position whenever it changes.
- **The switch:**
  - Going up, the server places each player at their own reported spot.
  - Coming down, the server sends the pose tick it used, and the client snaps its ship to that pose.
- **Players aboard:**
  - They see each other through relayed ship-relative reports.
  - They always track each other while on the same ship.
  - On the planet side, the server collides them with the ship.
- **Frame-aware visuals:** in the ship frame, the planet (M4), sky, clouds and other visual attributes are computed from the ship's surface-equivalent position, not the camera's level position.
- **Thresholds and masking:** the 1.5 km / 1.2 km thresholds stay. No fade mask.

## Evidence

Every measurement against its bar. "Details" links go to the results on the spike branch.

| Question | Bar | Measured | Verdict | Details |
|---|---|---|---|---|
| **Q1** No loading screen on a same-level teleport | never | 0 loading screens: 120 teleports over 28.3 million blocks (E1), 85 seam crossings (E2), 26 handoffs (E5) and 39 player handoffs (E6) | **Met** | [E1](https://github.com/Chabelabela/world-engine/blob/spike/m1-handoff/docs/spike/e1-results.md), [E2](https://github.com/Chabelabela/world-engine/blob/spike/m1-handoff/docs/spike/e2-results.md), [E5](https://github.com/Chabelabela/world-engine/blob/spike/m1-handoff/docs/spike/e5-results.md), [E6](https://github.com/Chabelabela/world-engine/blob/spike/m1-handoff/docs/spike/e6-results.md) |
| **Q1b** Frames with missing chunks after arrival, with pre-loading | ≤ 2 frames | RD 16: median 30.5 frames without pre-loading, 11.5 with 20 or 60 ticks (best: 9, about 150 ms). RD 8: 20–21 with pre-loading. A server ticket can't help with what the client still has to receive and mesh. | **Not met as asked; designed around** (see below) | [E1](https://github.com/Chabelabela/world-engine/blob/spike/m1-handoff/docs/spike/e1-results.md) |
| **Q2** Ship drawn at any transform through the 26.3 GPU API, with Sodium | ≤ 1 ms, with and without Sodium | 0.011 ms frame time (23–25 µs CPU, 9–10 µs GPU), vanilla and Sodium 0.9.2. Scales linearly: about 400 test ships per GPU millisecond. | **Met** | [E3](https://github.com/Chabelabela/world-engine/blob/spike/m1-handoff/docs/spike/e3-results.md) |
| **Q3** Vanilla chunk tracking or snapshot payload | pick one, with numbers | Snapshot: 199 B raw / 116 B deflated on first sight, against 17,529 / 375 for one vanilla chunk column. One-block edit 11 B + channel id. 0 B/s idle. 13/13 hash checks through edits, scheduled ticks, block entities, leaving and returning. | **Option B** | [E4](https://github.com/Chabelabela/world-engine/blob/spike/m1-handoff/docs/spike/e4-results.md) |
| **Q4** Visible pops per surface → ship → surface handoff | ≤ 1 per handoff, or a mask that hides it in ≤ 300 ms | 20 handoffs with the fixes: camera jump relative to the ship 0.0000 blocks (20/20), sky change 0, corrections 0. One residual pop: the first-person hand is relit. Plus one long frame (median 35–37 ms). Without the fixes: a 73-luminance whole-frame pop, and the ship invisible for 13–14 frames. | **Met** (E7 not needed) | [E5](https://github.com/Chabelabela/world-engine/blob/spike/m1-handoff/docs/spike/e5-results.md) |
| **Q5a** Two clients agree on positions through a handoff | same within 1 block | 16 handoffs, both players on the same tick at their exact spots, 0 corrections. Peer fix: 0.000 blocks in every phase. Vanilla sync: 1.0 block (max 1.5) while the ship moves, up to 8.5 after the hand-back. Either way, vanilla tracking drops the other player for 20 ticks after each climb and 2–17 after each hand-back. | **Met with ship-relative sync**; the tracking gap needs membership tracking | [E6](https://github.com/Chabelabela/world-engine/blob/spike/m1-handoff/docs/spike/e6-results.md) |
| **Q5b** Bandwidth for an idle ship | ≤ 2 KB/s per player | Ship frame 57 B/s. Planet side 2.9–3.2 KB/s: 1.8 KB/s locator-bar flood (the server sees players aboard floating) plus 1.0–1.2 KB/s of vanilla mob movement. The ship's own idle sync is 0 B/s. With the flood off: 1.03 KB/s. | **Met in the ship frame; met on the planet side once the server knows the ship** | [E6](https://github.com/Chabelabela/world-engine/blob/spike/m1-handoff/docs/spike/e6-results.md) |
| E2 (for M1e) Momentum and look across a seam | fold error 0, no corrections | 84 launched crossings of all 14 directed seams, plus 1 keyboard sprint: grid velocity and yaw exact, 0 corrections. Lands exactly on `fold` once a one-tick overshoot is compensated. Destination chunks pop in for 60–260 ms. | **Met** | [E2](https://github.com/Chabelabela/world-engine/blob/spike/m1-handoff/docs/spike/e2-results.md) |
| E7 Fade mask | only if E5 fails | not run | — | — |

- **Q1b, how the design gets around it:** the ship interior doesn't depend on chunks at all, because its copy reaches the client on first sight, long before the switch.
  - **The outside:** the switch happens at 1.2–1.5 km, where vanilla terrain is out of render range. M4 must draw the planet in both frames (E5's stand-in sphere did, and the climb showed no measurable exterior change).
  - **What's left:** terrain fades back in for about 1.4 s after a hand-back, and it's sent again, about 3.8 MB per player. That's an open risk below.
- **Video:** instead of recordings, every frame around each switch was read back and compared. The frame strips ([E5 strips](https://github.com/Chabelabela/world-engine/tree/spike/m1-handoff/docs/spike/e5), [E3 screenshots](https://github.com/Chabelabela/world-engine/tree/spike/m1-handoff/docs/spike/e3)) serve as the video.
- **Test conditions:** one PC (RTX 3070), localhost, 60 fps for frame counts. E1–E4 ran on superflat; E5 and E6 on the real universe level. The thresholds were scaled to 160 / 128 blocks to fit the level's height.

## Consequences

### Mixins required

From the spike, plus the two the design needs that the spike didn't build. "Risk" is about conflicts with Sodium, Iris and other mods, and about churn between versions.

| Side | Target | Why | Risk |
|---|---|---|---|
| client | `LevelRenderer.executeSolid` TAIL, capturing the `RenderPass` | Draw ships inside the main pass. Fabric's `LevelRenderEvents.AFTER_OPAQUE_TERRAIN` doesn't expose the pass; ask Fabric. | Low. Sodium 0.9.2 coexists. Iris shaderpacks untested. |
| client | `LevelRenderer.executeSolid` HEAD | The planet behind terrain in both frames (E5's stand-in; M4's renderer takes the hook) | Low |
| client | `RenderSectionRegion.getBlockState` (return value) | Hide ship plots from vanilla's mesher, so the ship has one renderer. Runs on mesher worker threads. | **Medium:** Sodium has its own mesher, so it needs a Sodium-specific hook (not built) |
| client | `LocalPlayer.tick` HEAD and TAIL | One pose per tick and the carry, after the old position is saved; the ship-relative report | Low–medium (movement mods) |
| client | `Entity.collide`, the `getEntityCollisions` call | The local player collides with the drawn ship | **Medium:** collision is patched by performance mods such as Lithium |
| client | `ClientPacketListener.handleMovePlayer` TAIL | Snap the ship to the server's pose tick when the hand-back lands | Low |
| client | `LevelRenderer.prepareTranslucents`, `CloudRenderer.prepare` argument | Clouds from the ship's surface-equivalent position (until M4 replaces clouds) | Low |
| client | `EnvironmentAttributeProbe.tick` (+ the interpolator clear and the Gaussian sample) | Sky, fog and biome blend from the surface-equivalent position | Medium: a new 26.x class, likely to change |
| client | `GameRenderer.update` HEAD | Put other players aboard back where the ship-relative sync says, after packets at frame start | Low. M2 may move this into entity render-state extraction. |
| server | `ServerLevel.sendBlockUpdated` HEAD | Collect ship edits for deltas. Optional: ship code can call the sync directly instead. | Low |
| server | **Not built:** `ChunkMap.TrackedEntity.updatePlayer` / `isChunkTracked` | Players aboard the same ship always track each other, regardless of pending chunks | Medium (entity-tracking and chunk mods, e.g. C2ME) |
| server | **Not built:** server-side ship collision for players aboard on the planet side (`getEntityCollisions` for players, or riding a ship entity) | Movement checks, vanilla's per-tick player update, survival. See M2. | Medium (collision mods) |

Measurement-only hooks (not for shipping): `GameRenderer.tryTakeScreenshotIfNeeded`, `PacketDecoder.decode`.

### Changes to ROADMAP §4.3 (after acceptance)

- **Ships:** add that clients receive ship blocks as a snapshot payload plus deltas and keep their own copy, and that World Engine renders every ship itself in both frames. Vanilla never meshes a ship's plot.
- **Handoff:** add how it works:
  - a server-initiated same-level teleport, placed from the client's ship-relative report;
  - a pose-tick snap on the way back;
  - players aboard synced ship-relative.

  Add what "no visual pop" depends on: the planet drawn in both frames (M4), frame-aware sky and clouds, and the ship's light on things aboard (M2).
- **Thresholds:** keep 1.5 km / 1.2 km. Add why it works: at that altitude vanilla terrain is out of render range, so the switch can't show terrain churn.
- **§4.2 Key decisions:** add rows for "ship sync: snapshot payload + deltas" and "ship rendering: World Engine's own renderer, both frames".

The frames themselves, the universe level and the seam design are unchanged.

### What M1d, M1e, M1f and M2 must now do

- **M1d (done):** no change. The off-planet void that M1d generates is what the shipyard needs (E5: the climb switch costs 74 KB of empty void chunks).
- **M1e (seams):**
  - Teleport at `fold`'s point with `QuarterTurns`-rotated grid velocity and yaw. That's exact, as measured in E2.
  - **Compensate the one-tick overshoot:** land at `fold(p) − rotate(v)`.
  - **Hysteresis:** teleport mid-band, about 64 blocks from either edge, or use a cooldown.
  - **Pre-load the twin region**, and get its chunks to the client before the crossing. A server ticket alone leaves a 60–180 ms pop, because only data already on the client avoids it.
  - **Corners:** `fold` returns `None` there; make them no-man's land.
  - **Other players:** after a seam teleport, they can vanish for up to 1 s (the E6 tracking gap). M1e should trigger a tracking re-check for nearby viewers when the destination chunk is sent.
- **M1f (time of day):**
  - Visual attributes are sampled at the camera's level position, so in the ship frame that position means nothing.
  - Sky angle, sun direction and lighting must come from the frame system: the planet and surface-equivalent position of the ship, not the plot's coordinates. It's the same pattern as E5's sky fix.
- **M2 (physics and ships):**
  - **Ship sync:** productionise option B: an incremental hash, splitting big snapshots, block-entity change hooks, entities aboard.
  - **Renderer:**
    - mesh per 16³ section off the render thread;
    - frustum culling;
    - sorted translucency, plus OIT pipelines;
    - block entities through the submit pipeline;
    - world light reaching a moving ship without re-meshing;
    - the Sodium mesher hook.
  - **Riding:** generalise the carry and collision to rotated ships. The spike assumed an unrotated ship.
  - **Server-side ship collision for players aboard on the planet side.** Without it:
    - real terrain inside the ship's volume causes silent corrections and fall-throughs (E6);
    - vanilla's per-tick player update floods the locator bar (1.8 KB/s per pair);
    - the server logs "standing on air";
    - survival can't work.

    Evaluate making players aboard ride a ship entity on the server. Vanilla exempts passengers from physics and movement checks.
  - **Players aboard:** ship-relative sync (relay plus drawing against the viewer's ship) and membership tracking.
  - **Light on things aboard** from the ship's light in both frames: the hand, held items, entities. That's the one pop left.
  - **Ship-aware block picking** in the surface frame: the crosshair outline and interaction.
  - **A cheaper pose:** 71.4 B per tick per player per moving ship today. Use a numeric payload id, quantisation, and maybe a lower rate with client extrapolation.
- **M3 and M4:**
  - **M3:** the real trigger (altitude and speed with hysteresis) and the `we-frames` carry of position and velocity through planet rotation. The spike climbed straight up with no planet rotation.
  - **M4:** draw the planet, atmosphere and clouds identically in both frames.

## API notes (26.3)

Surprises found while building the spike. The details are in each experiment's results.

- **Frames and rendering:**
  - **Frame phases:** a frame is `GameRenderer.update`, then `extract`, then `render`. HUD callbacks (`HudElementRegistry`) run in `extract`, while the main target still holds the previous frame with its GUI. Read frames back at `GameRenderer.tryTakeScreenshotIfNeeded`.
  - **Packet timing:** `Minecraft.runTick` handles queued packets, then runs ticks, then renders. On most frames no tick runs.
  - **The GPU API is its own package**, `com.mojang.renderpearl.api` (`GpuDevice`, `GpuBuffer`, `RenderPass`, `RenderPipeline`, `GpuQueryPool`). `RenderSystem` is the façade.
  - **Pipelines:** they take bind-group layouts. Only the moving-block pipelines (`SOLID_BLOCK`, `CUTOUT_BLOCK`, `TRANSLUCENT_BLOCK`) take a model-view transform; terrain pipelines can't rotate.
  - **GPU timestamps:** `RenderPass.writeTimestamp(pool, i)` × `DeviceInfo.timestampPeriod()`.
  - **Moved and renamed classes:**
    - `BlockAndTintGetter` moved to `net.minecraft.client.renderer.block` and gained `cardinalLighting()`; lighting goes through `BlockAndLightGetter.getBrightness`.
    - Coloured blocks are `ColorCollection`s (`Blocks.STAINED_GLASS.pick(DyeColor.…)`).
    - Fabric's `WorldRenderEvents` is now `LevelRenderEvents`. Its contexts give a `SubmitNodeCollector`, not the `RenderPass`.
  - **Visual attributes and clouds:**
    - Sky, fog and cloud colours are biome-blended at the camera by `EnvironmentAttributeProbe`, once per tick, exposed as `Camera.attributeProbe()`.
    - `CloudRenderer.prepare(color, status, height, range, cameraPos, gameTime, partialTick)` places clouds from the camera position it's given.
  - **Meshing state:**
    - Vanilla compiles only the sections inside the view frustum.
    - `LevelRenderer.visibleSections()` is public. `CompiledSectionMesh.UNCOMPILED` marks an unbuilt section mesh.
  - **Frame read-back:** `Screenshot.takeScreenshot(target, downscale, callback)` reads back asynchronously. The callback owns the image.
- **Entities and teleports:**
  - **Old position:** entities save their old position in `commonTick()`, then interpolate (`getInterpolation()`, which can be `cancel()`led), then `tick()`. `EntityRenderer` draws at `lerp(partialTick, xOld, getX())`. Players update every 2 ticks (`updateInterval(2)`).
  - **Teleport packets:** an absolute `ClientboundPlayerPositionPacket` resets the old position. Relative components shift it.
  - **`TeleportTransition`:** `TeleportTransition(level, pos, speed, yRot, xRot, relatives, post)` with no relatives sets everything absolutely.
  - **Player velocity:** `ServerPlayer.getKnownMovement()` is the client's last reported movement. To push velocity: `setDeltaMovement` plus `ClientboundSetEntityMotionPacket`; `hurtMarked` is gone.
  - **Extra colliders:** `Entity.collide` gathers `level.getEntityCollisions(...)` before block collisions, which is a clean place to add ship shapes.
  - **The server's per-tick player update:** `ServerGamePacketListenerImpl.tick()` runs `player.doTick()` and then `absSnapTo(firstGood…)` every tick.
  - **Movement checks:**
    - at most one position packet per tick;
    - "moved too quickly" past 10 blocks from the tick's start;
    - unconfirmed teleports are resent after 20 ticks;
    - creative skips "moved wrongly", but not the silent teleport-back when a move collides with something new.
  - **Entity tracking:**
    - Tracking is re-checked only when the tracked entity changes section (`ChunkMap.tick`) or the viewer's move packet is handled (`ChunkMap.move`).
    - `isChunkTracked` needs the chunk already sent.
    - A still `LocalPlayer` sends a position reminder every 20 ticks.
  - **Locator bar:** waypoints (`ServerWaypointManager`) update on every player position change; a nearby player is a block connection (`UPDATE`/`TRACK`). The game rule is `locator_bar`.
  - **Input:** `LocalPlayer.input` is a public `ClientInput`; a plain `ClientInput` presses nothing.
- **Chunks and networking:**
  - **The client's chunk storage** is a fixed ring around the player. It refuses chunks outside it ("Ignoring chunk since it's not in the view range").
  - **Block updates:** `Level.setBlock` notifies clients only in chunks at `BLOCK_TICKING` or above.
  - **Tickets:** `TicketType.FORCED` is persistent; register a non-persistent ticket type for pre-loading.
  - **Fabric payloads:** `PayloadTypeRegistry.clientboundPlay()` (formerly `playS2C()`), `CustomPacketPayload.codec(writer, reader)`. Custom payloads carry their id as a string in every packet.
  - **Measuring bytes:**
    - The bandwidth logger counts compressed wire frames, and only on a real connection: singleplayer has no monitor.
    - `PacketDecoder.decode` reports each packet's decoded size to `JvmProfiler.onPacketReceived`.
- **Setup:**
  - `graphicsPreset` in `options.txt` overrides `renderDistance` unless it's `"custom"`.
  - Old gamerule names are rejected (e.g. `doDaylightCycle`); the new names are snake_case (`locator_bar`).
  - **Offline-mode whitelist:** `whitelist add` for a player who never joined stores a random UUID that won't match. Write `nameUUIDFromBytes("OfflinePlayer:" + name)` to `whitelist.json` and reload.

## Open risks

| Risk | What we know | Follow-up |
|---|---|---|
| **Terrain after a hand-back:** about 1.4 s fade-in and about 3.8 MB per player | Measured at 128 blocks, where terrain is in range. At 1.2 km it isn't, but the burst stays. | M4 (planet in both frames). M1e/M2: send surface chunks before the hand-back. |
| **Sodium:** E1 and E5 ran on vanilla only, and the plot-hiding hook is vanilla's mesher only | E3 ran with Sodium 0.9.2 with no conflict | M2: the Sodium mesher hook; repeat E5 with Sodium |
| **Vulkan / 26.4** | Not tried. The renderer uses only the GPU abstraction. | M2, on the first 26.4 snapshot setup |
| **A lost teleport confirmation on a moving ship** | One "moved too quickly" in E5's first trip, before instrumentation. The timing matches the 20-tick resend. None in the next 15 trips or in E6. | M2: handle resends against the current pose |
| **Rotated ships** | Carry and collision were built for axis-aligned ships only | M2 |
| **Velocity and planet rotation through the handoff** | The spike climbed straight up with no horizontal velocity or rotation. `we-frames` has the transforms (round trip about 10⁻¹² m). | M3 |
| **Server-side ship collision and membership tracking** | Designed from E6's findings, not prototyped | M2: first tasks |
| **Mod conflicts** | Lithium (collision), C2ME (chunks, tracking) and Iris shaderpacks (ship draw) untested | M2 compatibility pass |
| **Real network latency** | Localhost only. Reports for standing players stay exact; walking players lag by the relay. | M2 multiplayer tests over a real link |
| **Stutter at each switch** (median 35–37 ms) | Not profiled; suspected dropping about 800 client chunks at once | M2/M3 profiling |
| **Terrain inside a parked ship** | Leaves inside the resting ship's volume caused silent corrections (E6) | M2: ships collide with terrain; server ignores terrain inside a ship's volume for players aboard |
| **Scale** | Rendering is linear (about 400 test ships per GPU millisecond). Pose sync is linear in ships × players. | M2 budget: twenty idle ships under 2 ms per tick |
| **Unexplained E1 result:** RD 8 is worse than RD 16 with pre-loading (20 vs 12 frames) | Guess: vanilla's adaptive chunk batch size | M1e, when tuning seam pre-loading |
