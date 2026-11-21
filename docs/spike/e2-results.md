# E2: momentum and look across a seam (supports M1e)

Spike branch only. Input for ADR 0001 and the M1e plan. Run on 2026-11-21.

**Result:**
- A player crossing any of the home planet's 7 seams, in either direction, arrives on the neighbouring face at `CubeSphere::fold`'s point, keeping momentum and look direction.
- There were 0 loading screens and 0 server movement corrections in 85 crossings (84 launched jumps and 1 keyboard sprint).
- The teleport lands exactly on fold's point. A systematic **one-tick overshoot** (the client moves one step right after the teleport) is fully explained and compensated, giving error 0.000.
- The visible cost is the destination's chunks arriving: about 60–180 ms of holes with pre-loading, about 260 ms without.

## Setup

- Same dedicated server (superflat) and auto-joining client as E1. Vsync off, about 1,000 fps, render distance 16.
- The home planet's faces (16,384 cells) are laid out in the level with `PlanetLayout`, exactly as in the universe world. In superflat the ground is flat on both sides, so only the movement is tested.
- **Math:** `we_spike_cross`, a spike-only FFI function in `core/crates/we-ffi/src/spike.rs`. It calls `CubeSphere::fold`, then `QuarterTurns::apply` on the grid velocity and `QuarterTurns::rotate_yaw` on the yaw. Java calls it through FFM (`SeamFold`), so there's no Java port of the math. Rust unit tests cover the equator wrap and bad input.
- **Detection** (`Seams.tick`, every server tick):
  - A player whose position is on no face, after last being on face F, has crossed one of F's seam edges. Natural adjacencies lead onto another face, so they never trigger this.
  - Their grid position relative to F is folded, and they're teleported to the folded point with `TeleportTransition`: absolute position, the rotated velocity, the rotated yaw, the pitch kept.
  - The velocity is the server's `getKnownMovement()`, the client's last reported movement.
- **Test** (`/we spike e2 test SpikeA 3`, over RCON):
  - All 14 directed seams of the home planet: 7 edges × 2 directions, found automatically as face edges whose far side is no face.
  - For each one, first a **control** jump 30 blocks inside the edge, to measure how far the launch carries on flat ground: 4.969 blocks, the same every time.
  - Then 3 **runs**: two at the edge's midpoint, one 200 blocks from a corner, where grid lines kink most.
  - Each run starts 2.5 blocks inside the edge, looking outward. The server applies a running-jump impulse (0.45 blocks/tick out, 0.42 up), and the destination is pre-loaded 60 ticks earlier.
  - The landing is compared with `fold(start + outward × control distance)`.
- **Keyboard run:** the client held Ctrl+W (sprint) across North's west edge.

## Results

### Run 1, plain teleport to fold's point (42 runs)

- **Faces and turns:** all 42 landed on the expected face with the expected quarter-turns, covering all four classes:
  - 0: E3 ↔ E0, the equator wrap;
  - 1 and 3: N/S ↔ E0 and E2;
  - 2: N/S ↔ E3.
- **Direction:** sideways error **0.000** in all runs. The velocity rotation is exact: before (0, −0.233), after (0.233, 0).
- **Look:** yaw error **0.00°** in all runs. The client kept exactly the rotated yaw.
- **Teleport:** the server position after the teleport equalled fold's point exactly (error 0).
- **Distance:** every run landed **0.233 blocks further** than the control. That's exactly the speed at the crossing (0.233 blocks/tick), identical in all 42 runs and independent of seam, rotation or position along the edge.

### Why the overshoot, and run 2 with compensation (42 runs)

- **Cause:** the server teleports the player to `fold(p)`, where `p` already includes the tick's movement, and gives them velocity `v`. The client applies the teleport and then advances one more step with `v` in the same tick, so one tick of travel is counted twice.
- **Compensation:** `/we spike e2 compensate on` lands the player at `fold(p) − rotate(v)`.
  - **First attempt:** it exposed a **ping-pong**. When the crossing happened less than one tick's travel past the edge (0.149 blocks, against 0.233 blocks/tick), the compensated point was itself beyond the new face's edge, so the next tick folded the player straight back.
  - **Fix:** a 3-tick cooldown after each crossing.
- **With compensation and cooldown:** **42 of 42 landed exactly on the prediction**. Error 0.000 blocks, yaw error 0.00°, exactly 42 crossings (no ping-pong), 0 corrections.

### Movement corrections, loading screens, chunks

| | Run 1 | Run 2 (compensated) | Keyboard sprint |
|---|---|---|---|
| Crossings | 42 | 42 | 1 |
| Server "moved wrongly / too quickly" | 0 | 0 | 0 |
| Loading screens | 0 | 0 | 0 |
| Missing-chunk time after the crossing | median 85 frames ≈ 83 ms | median 109 ms (57–178) | 272 frames ≈ 262 ms (no pre-load) |
| Bytes received in 2 s | median 138 KB | median 143 KB | 146 KB |

- **Why the holes:** each crossing moves 16,000–65,000 blocks in level coordinates, because the twin edge sits elsewhere in the net. So the client has to fetch and mesh the destination's chunks, exactly as in E1. Pre-loading on the server helps (≈ 260 → ≈ 110 ms) but can't remove it.
- **Keyboard sprint:** North's west edge to E0, quarter-turn 3. Sprint speed 0.281 blocks/tick rotated from west to south, yaw 90° → 0°, so holding W kept running straight on. Smooth apart from the chunk pop.

## Not measured

- **Video of a sprint-jump with the keyboard.** The automation can hold only one key at a time, so the keyboard run is a sprint without jumping; mid-air crossings are covered by the 84 launched jumps. The owner should look at one by hand (`/we spike e2 on`, then sprint-jump off a seam edge), mainly to judge the chunk pop.
- **Crossings at a corner, beyond two edges.** `fold` returns `None` there, and the spike leaves the player alone. M1e defines it (no-man's land).
- **Real terrain.** In the universe world, terrain beyond the seam continues on the sphere. Without M1e's overlap band the player arrives facing terrain that hasn't loaded, which is the same pop, but more visible on hilly terrain.

## Decision input for M1e and the ADR

- **The rotation scheme works as designed.** Grid velocity and yaw cross every seam exactly through `QuarterTurns`, with no rubber-banding and no anti-cheat complaints, because the teleport is server-initiated.
- **Teleport compensation is required:** land at `fold(p) − rotate(v)`, or equivalently set the client one tick back, or the player gains one tick of travel per crossing.
- **Hysteresis is required.** M1e's overlap band gives it naturally: teleporting at the middle of a 128-block band puts the player 64 blocks from any edge. Any scheme that teleports at the edge itself needs a cooldown.
- **Pre-load the twin region.** When a player comes within about 64 blocks of a seam, ticket the far side, and ideally send its chunks to the client before the crossing. M1e's band, mirrored and loaded around the player, does that by construction. The E1/E4 lesson applies: only data already on the client avoids the pop.
- **Mixins:** none were needed for E2. Detection is a server tick listener and the teleport is vanilla's `TeleportTransition`.

## API notes (26.3)

- `ServerPlayer.getKnownMovement()` is the client's last reported movement per tick: a usable grid velocity for players, whose server-side `getDeltaMovement()` isn't simulated.
- `TeleportTransition(level, pos, speed, yRot, xRot, relatives, post)` with an empty relatives set sets the position, velocity and rotation absolutely. Same-level `ServerPlayer.teleport` sends one position packet.
- To push velocity to a player's client: `setDeltaMovement`, then `connection.send(new ClientboundSetEntityMotionPacket(player))`. That's what the mace does; `hurtMarked` is gone.
- The server logs corrections as `{name} moved wrongly!` and `{name} moved too quickly! dx,dy,dz` (`ServerGamePacketListenerImpl`).
