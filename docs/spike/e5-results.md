# E5: the surface ↔ ship handoff (Q4)

Spike branch only. Input for ADR 0001. Run on 2026-11-22.

**Q4: met, with one known residual pop.** A player standing inside a moving ship goes surface frame → ship frame → surface frame with no loading screen and no camera jump relative to the ship. Ship interior, sky and clouds stay continuous. Over 10 round trips (20 handoffs) with all fixes on:
- **Camera jump relative to the ship:** 0 of 20.
- **Sky colour change:** 0 of 20.
- **Server movement corrections:** 0. Exactly 2 teleports per trip.
- **Player's position aboard:** exact in both directions: (4.5, 1.0, 4.5) ship cells before and after every handoff.

What's still visible:
1. **One pop per handoff: the first-person hand is relit.** In the plot the player really stands under the ship's roof. On the surface the drawn ship doesn't shade anything, so the world's sky light reaches them. Fix: light things aboard from the ship's own light in both frames (M2).
2. **One long frame at each switch:** median 35–37 ms; the first climb after the client starts took 182 ms.
3. **After the hand-back, the terrain streams back in** over about 1.4 s (80–85 frames). This is gradual, not a pop: sections fade in over the stand-in planet. At the roadmap's real thresholds (1.2–1.5 km) this terrain is out of render range anyway.

Without the fixes the same handoff is a large pop: the ship vanishes for 13–14 frames (about a quarter of a second), the planet disappears and the sky colour jumps (see "Without the fixes"). **E7 (the fade mask) isn't needed.**

## Setup

- **World:** a fresh copy of the real **universe level** (M1d world type, `level-name=spike-e5`), not superflat.
  - The ship rests over real terrain on the home planet's face E1, near the face centre, at (256.5, 92.5, 256.5).
  - The shipyard plot at (20,000,000, 64, 20,000,000) is off-planet, so it's the void biome and empty chunks, as in the real game.
- **Client:** dedicated server plus auto-joining client, render distance 16, chunk fade-in 0.75 s (vanilla default), noon, clear weather, capped at **60 fps** so frames can be compared one by one.
- **Flight** (`Handoff`, server, `/we spike e5 start|trips|flags`):
  - The ship rests, climbs straight up at 10 blocks/s, hovers at 200 blocks for 2 s, comes back down and rests 3 s.
  - **Thresholds:** up at 160 blocks above rest, back down at 128. That's ROADMAP's 1.5 km / 1.2 km scaled to fit the level; the 32-block gap is the hysteresis.
  - While the ship moves, its pose goes to the client every tick, tagged with the server tick.
- **The player:** stands still aboard at cell (4.5, 1, 4.5), looking west through the glass port wall, 20° down, in creative mode (not flying).

## How the handoff works (what the spike built)

**Surface frame: riding the drawn ship** (client):
- **Poses, one per tick:** poses are queued and applied one per client tick, at the HEAD of `LocalPlayer.tick`. That's after `commonTick` has saved the player's old position.
- **Carry:** a player standing in the ship's box is moved by the same delta as the ship. Because it happens after the old position is saved, the camera interpolates the carry like any movement. The ship is drawn at `lerp(prev, cur, partialTick)`, exactly like an entity. Result: camera and ship move together on every frame.
- **Collision:** the player collides with the ship's block shapes at the current pose. They join the "entity" colliders in `Entity.collide`, the way boats and shulkers do.
- **Report:** at TAIL of the tick, the client sends its position relative to the ship (`ship_rel`), only when it changes. Standing still, it sends nothing.

**The switches** (server):
- **Up, past 160:**
  - The server sends `frame_switch(ship)`.
  - It teleports the player to plot origin plus the client's reported relative position. The teleport keeps velocity and look (`Relative.DELTA | ROTATION`).
  - **Why the client's report:** at the climb switch, the client was always 1–2 ticks behind the server's pose (`pending 1–2`). Placing the player from the server's own copy of them would have been 0.5–1 block off. The client's report made all 20 handoffs exact.
- **Down, past 128:**
  - The server sends `frame_switch(surface, poseTick)`.
  - It teleports the player to the ship's current pose plus their position in the plot.
  - When the teleport lands (`ClientPacketListener.handleMovePlayer` TAIL), the client snaps its ship to exactly that pose tick. Older queued poses are dropped and there's no interpolation that frame. The log shows `(snapped)` on all 10 hand-backs.
- **Pre-load:** the surface around the ship is ticketed 60 ticks before the hand-back.

**Ship frame: the four fixes** (client, each a bit in `/we spike e5 flags`):

| Fix | What it does | Why |
|---|---|---|
| `FIX_RENDERER` (1) | Keep drawing the ship with the spike's renderer, at the plot. A mixin on `RenderSectionRegion.getBlockState` makes vanilla's mesher see the plot's ship blocks as air. | Vanilla can only draw the real blocks once the plot chunk arrives and is meshed (13–14 frames here). Its lighting also differs from the spike mesh's. |
| `FIX_CLOUDS` (2) | Clouds are prepared with the ship's surface-equivalent camera position (`CloudRenderer.prepare` argument). | Cloud pattern and height depend on the camera's level position. In the plot they're different clouds, and 125 blocks above instead of below. |
| `FIX_SKY` (4) | The camera's `EnvironmentAttributeProbe` keeps the last surface biome blend. This holds in the ship frame, and after a hand-back until the 3×3 chunks around the camera have arrived. | Sky and fog colours are biome-blended at the camera. The shipyard is the void biome (sky #7ba4ff against #6eb1ff here). Right after a hand-back, missing chunks blend in the client's default for about 10 frames. |
| `FIX_BACKDROP` (8) | A stand-in planet: a sphere of the home planet's radius (10,430) under the camera, top at sea level, with a coarse land/sea pattern hazing into the fog colour. Drawn in **both** frames at the start of the main pass (HEAD of `executeSolid`), without depth, so terrain covers it. | Stands in for M4's planet rendering. Drawn in both frames, it fills everything past render distance the same way on both sides of the switch. |

**Measuring** (`PopLogger`, client):
- Every frame is read back right after the level is drawn, at the half-size point where vanilla takes screenshots (`GameRenderer.tryTakeScreenshotIfNeeded`), so the GUI isn't in it.
- 30 frames before and 120 after each switch are logged with:
  - the mean luminance change from the previous frame, whole frame and per 3×3 grid cell;
  - the camera's position relative to the ship as drawn, and how far that moved (camera jump);
  - chunks missing within render distance;
  - visible sections not yet meshed;
  - sky and fog colour.
- `tools/spike/e5_report.py` turns that into the tables below. `/we spike e5 flags 63` also saves the frames (`run/spike-e5/`).

## Results: all fixes (flags 31), 10 round trips

| | Climb (surface → ship) | Hand-back (ship → surface) |
|---|---|---|
| Switches | 10 | 10 |
| Player's position aboard before → after | (4.5, 1.0, 4.5) → (4.5, 1.0, 4.5), all 10 | same, all 10 (ship snapped to the server's pose tick) |
| Camera jump relative to the ship (max per switch) | 0.0000 blocks, all 10 | 0.0000 blocks, all 10 |
| Frames with a change above the drift threshold | 0 | 0 |
| Sky colour changed | 0 | 0 |
| Hand relit (bottom-right cell, change 13.5) | 10: at frame 1–4 on trips 1–4, at about frame 21 on trips 5–10, when the plot chunk's light arrived | 10, at frame 0 |
| Crosshair outline on a ship block | appears with the plot chunk (frame 3–4 when it arrived early) | disappears at frame 0 |
| Longest frame within frames −1…+6 | median 37 ms, max 182 ms (first trip) | median 35 ms, max 48 ms |
| Terrain incomplete after the switch | not visible (empty void chunks) | 80–85 frames ≈ 1.4 s: up to 797 chunks missing, up to 124 visible sections unmeshed |
| Bytes received in the 2 s after | 74 KB (void plot chunks) | 3.8 MB (planet terrain) |
| Server corrections / extra teleports | 0 / 0 | 0 / 0 |

- **Drift threshold:** the larger of 4 × and +6 above the median change of frames −30…−3. The view drifts slowly as the ship climbs, about 0.05–0.12 luminance per frame.
- **Frame strips:** `e5/1-climb-all-fixes.jpg` and `e5/2-handback-all-fixes.jpg` (one trip, frames −1, 0 and later).
- **On the climb, frame 0 changes by 0.05–0.08:** the same as the drift between any two frames before it.

## Without the fixes (flags 16), 3 round trips

| | Climb | Hand-back |
|---|---|---|
| Whole-frame change at the switch | **73 luminance** (drift 0.03). The ship disappears entirely for 13–14 frames (215–262 ms): the player floats in empty sky until the plot chunk arrives and vanilla meshes it. | 3–4 (the hand, and the view outside) |
| Exterior in the ship frame | void below (no planet), clouds overhead instead of below | the planet and real clouds come back |
| Sky colour | wrong for the whole ship-frame stay (#6eb1ff → void biome #7ba4ff) | back to the planet's colour |
| Camera jump, hand, terrain, frame time | same as with the fixes | same as with the fixes |

Strip: `e5/3-climb-no-fixes.jpg`.

## Pops, and what causes each

| Cause (plan's list) | Seen? | With all fixes | Fix for the real thing |
|---|---|---|---|
| Camera jump | no | 0 of 20. Needs the client-reported relative position going up, and the pose-tick snap coming down. | Keep both. |
| Ship interior swaps renderer | yes, without `FIX_RENDERER` (13–14 frames invisible, then vanilla's lighting) | none | One renderer for the ship in both frames. Vanilla's mesher must skip plot blocks (mixin; Sodium needs its own hook). |
| Sky reset | yes, without `FIX_SKY`; also about 10 frames after every hand-back until biomes arrive | none | Visual attributes in the ship frame come from the ship's surface position, or from M4's atmosphere. |
| Clouds | yes, without `FIX_CLOUDS` | none | Draw clouds from the surface-equivalent position (M4 replaces vanilla clouds at altitude anyway). |
| Chunk churn (the planet outside) | yes, without the stand-in planet. With it, the climb shows no measurable exterior change at 160 blocks: real terrain is mostly fogged there, and what you see is the stand-in in both frames. | Hand-back: terrain fades back in over about 1.4 s above the stand-in. | M4 must draw the planet in both frames. At 1.2–1.5 km the terrain is out of render range at the switch. Keeping or pre-sending the surface chunks (E1/E4 lesson) would remove the rest. |
| Entity interpolation | not tested here (one player) | – | E6. |
| **Light on things aboard** (not on the plan's list) | yes, every switch | the hand is relit: 1 pop | Light the player, held items and entities aboard from the ship's light in both frames (M2). |
| **Crosshair outline** (not on the plan's list) | yes, when aiming at a ship block | appears or disappears | Ship-aware block picking in the surface frame. M2 needs it anyway to interact with ship blocks there. |
| **One long frame** (not on the plan's list) | yes | about 35 ms per switch | Not profiled. Likely suspects: re-centring the client chunk cache and dropping about 800 chunks, and repositioning the section render area. |

## Things learned along the way

- **26.3 frames are split into extract, then render.**
  - HUD callbacks (`HudElementRegistry`) run in the extract phase, before this frame's level is drawn. The main target still holds the previous frame, GUI included.
  - The spike's first capture read back that previous frame. That paired the new camera with the old ship draw, and counted the HUD text changing as a pop.
  - Reading back at `tryTakeScreenshotIfNeeded` fixed it. Anyone measuring frames in 26.3 needs the same.
- **The very first trip had one server correction:** "SpikeA moved too quickly! 0.0,-10.5,0.0", 1 s after the hand-back. The player was put back about 10 blocks up and landed on the roof.
  - That was before teleport logging existed. The timing matches vanilla's 20-tick resend of an unconfirmed teleport.
  - It didn't happen again in the next 15 instrumented trips (exactly 2 teleports each).
  - It stays an open risk: a moving ship can't wait for a lost teleport confirmation the way a standing player can.
- **"Player … standing on air - force-sending blocks below"** is logged by the server every 10 s while a player rides the drawn ship. The client says it's on the ground; the server sees air, because the ship's blocks are in the plot. Harmless in creative mode. Survival would need server-side ship collision (no fly kick, no fall damage), which is M2.
- **The climb switch bursts 74 KB** (empty void chunks around the plot). **The hand-back bursts 3.8 MB in 2 s** (all terrain within render distance). For E6 and Q5: the ship's pose costs about 75 bytes per tick per player while moving (about 1.5 KB/s, computed from the payload layout) and nothing at rest.

## Decision input for the ADR

- **The handoff mechanism works:** same level, a server-initiated teleport, and these three pieces:
  1. the client reports its position relative to the ship;
  2. the hand-back carries the pose tick it was placed against, and the client snaps to it;
  3. the client advances the ship one pose per tick and carries the player in the same tick.
- **Required for "no pop", in order of size:**
  1. **One renderer for the ship in both frames.** The vanilla-drawn interior alone is a 13–14-frame pop.
  2. **The planet drawn in both frames (M4),** so the outside doesn't change at the switch.
  3. **Frame-aware sky and clouds:** sample them at the ship's surface position, or let M4's atmosphere own them.
  4. **Light things aboard from the ship's own light (M2).** This is the one pop left.
- **The 1.5 km / 1.2 km thresholds are a good place for the switch.** Vanilla terrain is out of render range there, so terrain churn can't be seen; only M4's rendering is on screen.
- **Mixins used by E5,** all client-side:

  | Target | Purpose |
  |---|---|
  | `LocalPlayer.tick` HEAD/TAIL | carry, report |
  | `Entity.collide` | ship collision |
  | `ClientPacketListener.handleMovePlayer` TAIL | snap |
  | `LevelRenderer.executeSolid` HEAD | stand-in planet |
  | `LevelRenderer.prepareTranslucents` | cloud camera |
  | `RenderSectionRegion.getBlockState` | hide plot blocks from vanilla |
  | `EnvironmentAttributeProbe.tick` | biome blend |
  | `GameRenderer.tryTakeScreenshotIfNeeded` | measurement only |

  Server side, E5 needed no mixins. A real implementation also needs server-side collision with ships in the surface frame.

## Not measured

- **Sodium:** `FIX_RENDERER`'s mixin is vanilla's mesher only.
- **A real person at the keyboard:** a walking or jumping player during the switch. The relative position is reported each tick, so expect only the usual one-tick latency.
- **A rotated ship:** E5's carry and collision assume an unrotated ship, as in E3.
- **Two players (E6).**
- **Video:** the plan asked for video; instead every frame was read back and diffed. The PNG frames serve as the video.

## API notes (26.3)

- **Frame phases:** `GameRenderer.update` → `extract` (camera, level, GUI including HUD callbacks) → `render` (clear, level, `tryTakeScreenshotIfNeeded`, post, GUI).
- **Old position:** entities save their old position in `commonTick()`, just before `tick()`. Moving the local player at HEAD of `LocalPlayer.tick` is interpolated by the camera.
- **Teleport packets:** `ClientboundPlayerPositionPacket` with an absolute position also resets the old position (no streak across the jump). Relative X/Y/Z components shift the old position too, so interpolation survives.
- **Entity colliders:** `Entity.collide` gathers `level.getEntityCollisions(entity, box)` (the expected type is `Level`) before block collisions. That's a clean place to add extra shapes.
- **Visual attributes:** `EnvironmentAttributeProbe` samples biome-blended visual attributes (sky, fog and cloud colour…) once per tick at the camera position (Gaussian over quarts) and lerps between ticks. `Camera.attributeProbe()` exposes it.
- **Clouds:** `CloudRenderer.prepare(color, status, height, range, cameraPos, gameTime, partialTick)` places clouds from the camera position passed in.
- **Visible sections:** `LevelRenderer.visibleSections()` is public. A section's mesh is `CompiledSectionMesh.UNCOMPILED` until it's built. `isSectionCompiledAndVisible` also waits for chunk fade-in (visibility ≥ 0.3).
- **Frame read-back:** `Screenshot.takeScreenshot(target, downscale, callback)` reads back asynchronously. The callback owns the `NativeImage`, and the size must be divisible by the downscale factor.
- **Server movement checks** (`ServerGamePacketListenerImpl`):
  - at most one position packet per tick (a second one disconnects);
  - "moved too quickly" when a packet moves more than 10 blocks from the tick's start;
  - unconfirmed teleports are resent after 20 ticks;
  - creative players skip "moved wrongly".
