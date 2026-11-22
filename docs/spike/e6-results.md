# E6: two players aboard one ship (Q5)

Spike branch only. Input for ADR 0001. Run on 2026-11-22.

**Q5: the handoff itself is consistent for two players, but vanilla's sync of the other player isn't.** Three vanilla behaviours have to be replaced in M2:
- Draw other players aboard from their ship-relative position.
- Track players aboard by ship membership.
- Give the server the ship's collision on the planet side.

With the spike's peer fix, both clients agree exactly.

- **The handoff:** in 8 clean switches (4 round trips), both players switched on the same server tick, each at their exact spot aboard, with 0 server corrections.
- **Where each client draws the other player** (clean run, both standing still):

  | | resting | moving, planet side | ship frame | 2 s after the climb switch | 2 s after the hand-back |
  |---|---|---|---|---|---|
  | **vanilla** (the server's copy of the player) | 0 | **1.0** blocks (max 1.5) | 0 | 0 | **up to 8.5** blocks |
  | **peer fix** (their own ship-relative report) | 0 | 0 | 0 | 0 | 0 |

- **The other player disappears after every switch, with or without the fix:**
  - for exactly 20 ticks (1 s) after the climb;
  - for 2–4 ticks (once 17) after the hand-back.

  Vanilla's entity tracking does this (see "Problems found").
- **Bytes per player, ship idle:**
  - **Ship frame:** 57 B/s.
  - **Planet side:** 2.9–3.2 KB/s:
    - 1.8 KB/s is a locator-bar flood that the ship causes, because the server sees players aboard floating.
    - 1.0–1.2 KB/s is vanilla mob movement, the same as on plain ground.
  - **The ship's own sync sends nothing while it's idle.** With the flood switched off (locator bar off), idle aboard is 1.03–1.04 KB/s.
- **While the ship moves:** its pose costs 71.4 bytes per tick per player, about 1.4 KB/s.

| Q5 bar | Result |
|---|---|
| Positions the same within 1 block after the handoff | **Peer fix: met** (0.000 everywhere). Vanilla: met 2 s after the climb (0), not right after the hand-back (up to 8.5; 1.0 while the ship keeps descending) |
| ≤ 2 KB/s per player for an idle ship | **Ship frame: met** (57 B/s). Planet side: **not met as is** (3.0 KB/s). Met once the locator-bar flood is fixed (1.0 KB/s measured with it off) |

## Setup

- **World and flight:** as in E5 (`spike-e5` universe level, all four E5 fixes on), except:
  - the hover at the top lasts 20 s, to measure idle in the ship frame;
  - the leaves inside the ship's resting volume were cleared (see "Problems found").

  The ship rests at (256.5, 92.5, 256.5) on the home planet, climbs at 10 blocks/s and hands off at 160 up / 128 down.
- **Two clients:**
  - SpikeA (`runSpikeClient`, directory `run`) and SpikeB (new `runSpikeClientB`, own directory `run-b`) on the local dedicated server.
  - Both are 60 fps and render distance 16, and run on one machine (localhost, so no real latency).
- **Aboard:** SpikeA stands at ship cells (4.5, 1, 4.5), as in E5. SpikeB stands at (2.5, 1, 3.5), 2.2 blocks away, in SpikeA's view.
- **Clean protocol** (one command script):
  1. `HOLD`: each client ignores movement keys, so stray input can't disturb the run.
  2. 30 s settle, then 30 s idle.
  3. 2 round trips with vanilla drawing the other player.
  4. 10 s idle, then 2 round trips with the peer fix.
- **Logging:**
  - **Every client tick, each client logs:**
    - its own spot relative to the ship as it draws it;
    - where it draws the other player relative to that same ship;
    - the wire bytes received (vanilla's bandwidth logger).
  - **Once a second:** decoded bytes by packet type, from a new hook on vanilla's packet decoder.
  - **Report:** `tools/spike/e6_report.py` lines the two logs up on the server tick (pose ticks while the ship moves). The error is the distance between where the viewer draws the other player and where that player stands by their own client, at the same server tick.

## What the spike added

- **Relay** (server): each player's ship-relative report (`Rel`, from E5) goes to every other player who has the ship, as `PeerRel(entityId, …)`. It's sent only when it changes, so it costs nothing while players stand still.
- **Peer fix** (`FIX_PEERS`, client):
  - Another player aboard is drawn at this client's ship, as drawn, plus that player's own report. That works in either frame.
  - Vanilla's interpolation for that player is cancelled.
  - The fix runs after every client tick, and again at the start of each frame (`GameRenderer.update`), because a teleport packet handled at frame start can move the entity back.
- **Measurement:**
  - `TRACK`: the per-tick log and the per-type bytes.
  - `HOLD`: swaps `LocalPlayer.input` for an empty `ClientInput`.
  - New `/we spike e6 start <players>`, `hover <s>`, `label <text>`. Flags now go up to 511.
- **Spike bug fixed on the way:**
  - When `/we spike e6 start` re-placed a player at the same spot their client had last reported, the client didn't report again.
  - The server had just forgotten the old report, so that player was never handed off.
  - The client now reports again after every teleport.

## Results: clean run, both players standing

4 round trips: 2 with vanilla drawing the other player, 2 with the peer fix. The error columns give, in blocks, how far the drawn player is from where that player's own client puts them.

| Drawing | Phase | Ticks (both viewers) | Median | p95 | Max | Missing (ticks) |
|---|---|---|---|---|---|---|
| vanilla | planet side, resting | 1579 | 0.000 | 0.000 | 1.500 | 0 |
| vanilla | planet side, moving | 2148 | **1.000** | 1.500 | 1.500 | 0 |
| vanilla | 40 ticks after the climb switch | 160 | 0.000 | 0.000 | 0.000 | **80** |
| vanilla | ship frame | 2337 | 0.000 | 0.000 | 0.000 | 0 |
| vanilla | 40 ticks after the hand-back | 160 | **2.500** | 7.000 | **8.500** | 10 |
| peer fix | planet side, resting | 1705 | 0.000 | 0.000 | 0.000 | 0 |
| peer fix | planet side, moving | 2150 | 0.000 | 0.000 | 0.000 | 0 |
| peer fix | 40 ticks after the climb switch | 160 | 0.000 | 0.000 | 0.000 | **80** |
| peer fix | ship frame | 2336 | 0.000 | 0.000 | 0.000 | 0 |
| peer fix | 40 ticks after the hand-back | 160 | 0.000 | 0.000 | 0.000 | 41 |

- **40 ticks after each switch:**
  - Vanilla: 0 after the climb, 1.0 after the hand-back.
  - Peer fix: 0 after both.

  The 1.0 is the same lag as the "moving" row, because the ship is still descending.
- **The 1.5 maximum while resting** comes from the ticks when the ship starts or stops: vanilla's copy is still catching up.
- **Handoffs (server log):** both players switched on the same tick at every switch. Positions before and after were (4.5, 1, 4.5) and (2.5, 1, 3.5) every time. No "moved too quickly" or "moved wrongly" in any session.

### The other player disappears after each switch

Ticks during which the viewer's client has no entity for the other player:

| Switch | vanilla drawing | peer fix |
|---|---|---|
| climb (into the ship frame) | 20, 20 (both viewers, every time: ticks +1 to +20) | 20, 20 |
| hand-back (to the planet) | 2–3 | 3–4, and 17 once on each viewer |

**Cause (vanilla tracking):**
- When a player is teleported into chunks their viewer hasn't received yet, the server stops tracking them for that viewer. `isChunkTracked` is false while the chunk is pending.
- Tracking is re-checked only when the tracked player changes section or the viewer's move packet is handled (`ChunkMap.move`).
- A player standing still sends a move packet only as the once-a-second position reminder (`LocalPlayer`, every 20 ticks).
- So the other player comes back exactly 20 ticks later. A walking viewer would see them sooner.

The peer fix can't help, because there's no entity to place.

## Bytes

Wire bytes received per player, mean per second (both clients within 1% of each other):

| Phase | B/s | Biggest parts (decoded B/s) |
|---|---|---|
| planet side, ship resting, idle | 2,884–3,198 | waypoint 1,800 · mob movement about 1,000–1,200 |
| the same, locator bar off | 1,030–1,044 | mob movement only |
| control: both players on plain ground, off the ship | 1,331–1,359 | mob movement only; **no waypoint packets** |
| ship frame, hovering (idle) | **57** | time, keep-alive |
| climb (planet side, moving) | 5,040 | waypoint 2,020 · **ship pose 1,428** (71.4 B × 20) · mob movement |
| 2 s after the climb switch | 67 KB in total | the plot's empty chunks |
| 2 s after the hand-back | 3.78–3.79 MB in total | the surface's chunks, sent again |

- **The ship's own traffic:**
  - **Idle:** 0 (no pose while it doesn't move).
  - **Moving:** 71.4 bytes per pose packet decoded, 20 per second, for each player. About 21 of those bytes are the Fabric payload id string `worldengine:ship_pose`.
  - **Peer relays:** nothing while players stand still.
- **The waypoint flood is caused by the ship.**
  - Aboard on the planet side, each client gets the other player's locator-bar waypoint 40 times a second, as `TRACK` and `UPDATE` packets. On plain ground there are none, and in the plot there are none.
  - Vanilla sends those only when that player's block position changes.
  - What causes it is in "Problems found" (1).

## A person playing SpikeB (session 1, not scripted)

In the first session, SpikeB was being played by hand. They:
- walked loops around the deck;
- bumped SpikeA (vanilla player pushing, about 0.3 blocks);
- climbed onto the roof and rode two full round trips standing on it, at cells y = 5 (both handoffs exact);
- jumped off the roof;
- once stood in the bow doorway and was left behind when the ship lifted off.

| Drawing | Phase | Ticks | Median | p95 | Max |
|---|---|---|---|---|---|
| vanilla | resting, other player walking | 136 | 0.114 | 0.332 | 0.724 |
| vanilla | moving, other player walking | 459 | 1.046 | 1.505 | 1.617 |
| vanilla | ship frame, other player walking | 331 | 0.254 | 0.652 | 1.035 |
| vanilla | 40 ticks after the hand-back, walking | 36 | 3.598 | 6.877 | 7.353 |
| peer fix | resting, other player walking | 126 | 0.000 | 0.250 | 0.420 |

- For a walking player in a static frame, vanilla shows its usual remote-player lag: 0.25 median, about 1 block at most.
- The peer fix lags by the relay instead: one report per tick, one tick late.
- There's too little peer-fix walking data while moving (11 ticks) to say more.

## Problems found

1. **On the planet side the server doesn't know the ship exists.** Its blocks are only in the plot, so for the server a player aboard floats in the air above real terrain. Three effects:
   - **Silent corrections.**
     - The ship rested with a tree's leaves inside its roof layer: at (255, 94, 255), 32 leaf blocks in all.
     - When the climb lifted SpikeB's head into that leaf block, the server's "colliding with something new" check teleported them back to their last good position. It logged nothing, even in creative.
     - It did this three times. On their own client that put SpikeB inside the climbing ship's floor, and they fell through.
     - SpikeA's column was clear. In session 1 the person playing SpikeB happened not to be under the leaves when the climb started.
     - For the clean run, the leaves were cleared.
   - **Locator-bar flood.**
     - Every tick, `ServerGamePacketListenerImpl.tick()` runs `player.doTick()` and then snaps the player back to their last client position (`absSnapTo(firstGood…)`).
     - For a player the server sees floating, `doTick` moves them across the block boundary under their feet and the snap moves them back.
     - That's two block changes per tick, so two waypoint packets per tick (`TRACK`/`UPDATE`) to every other player in range: 1.8 KB/s per pair.
   - **"Standing on air":** the server force-sends the blocks below every 10 s, as in E5.

   **Fix:** server-side collision with the ship for players aboard on the planet side. It is also needed for survival mode, so it isn't extra work. Another option to evaluate in M2 is making players aboard ride a ship entity on the server: vanilla skips physics and movement checks for passengers.
2. **Vanilla syncs other players in world coordinates, so on a moving ship they lag behind it.**
   - At 10 blocks/s they're drawn 1 block low, about 2 ticks of lag (player updates every 2 ticks, plus interpolation).
   - After the hand-back, the client receives about 165 KB per tick of surface chunks being sent again. Updates for the other player arrive in bursts about 14 ticks apart, so they appear to rise out of the descending ship, up to 8.5 blocks.

   **Fix:** sync players aboard ship-relative: relay each player's report, and draw others against your own drawn ship. That's what the peer fix does: 0.000 error.
3. **Vanilla drops the other player for up to a second after each switch.** See "The other player disappears after each switch".

   **Fix:** players aboard the same ship always track each other. For example, a mixin on `ChunkMap.TrackedEntity.updatePlayer` / `isChunkTracked`, or re-checking tracking when the chunk is sent.
4. **Pose cost grows with ships × players:** 1.4 KB/s per player per moving ship. Fine for a test ship. For M2:
   - send a short numeric payload id instead of the id string;
   - quantise the pose;
   - consider a lower rate with client extrapolation.

## Decision input for the ADR

- **The E5 handoff mechanism works for two players unchanged:** same tick, exact spots, no corrections. Each player's own report drives their handoff, so the players don't affect each other.
- **Players aboard need their own sync, ship-relative,** for drawing and later for interaction. Vanilla's world-relative entity sync is off by a block at 10 blocks/s, and much worse after a switch.
- **Players aboard need their own tracking rule:** by ship membership, not chunk distance and pending chunks.
- **The server needs the ship's collision on the planet side** for players aboard. Without it:
  - real terrain inside the ship's volume causes silent corrections and fall-throughs;
  - vanilla's per-tick player update floods the locator bar;
  - survival can't work.
- **Bytes:**
  - An idle ship costs nothing: 57 B/s in the ship frame, and 1.0 KB/s of unrelated vanilla mob traffic on the planet side once the flood is fixed.
  - A moving ship costs about 1.4 KB/s per player at 20 poses per second (shrinkable).
  - A hand-back costs about 3.8 MB per player, the surface chunks (E5).
- **Mixins used by E6,** in addition to E5's, all client-side:

  | Target | Purpose |
  |---|---|
  | `GameRenderer.update` HEAD | re-place other players aboard after packets at frame start (peer fix) |
  | `PacketDecoder.decode` (`JvmProfiler.onPacketReceived`) | bytes by packet type (measurement only) |

  Server side, E6 needed no mixins. The three fixes above would need server mixins: tracking and collision.

## Not measured

- **Walking with the peer fix, controlled:** only about 140 ticks of hand-played data. Expect about one tick of relay latency: at walking speed (0.22 blocks per tick) that's 0.2–0.4 blocks, like vanilla's own lag.
- **More than two players, or players in different ships.** Also someone outside the ship watching it: the peer fix only covers players aboard.
- **Real network latency:** everything ran on localhost.
- **The three fixes themselves** (server ship collision, membership tracking, a cheaper pose). They're designed, not built.
- **Sodium,** as in E5.

## API notes (26.3)

- **Offline-mode whitelist:**
  - `whitelist add <name>` for a player who has never joined stores a random UUID, and the player is still rejected.
  - The offline UUID is `UUID.nameUUIDFromBytes("OfflinePlayer:" + name)`. Write it to `whitelist.json` and run `whitelist reload`.
  - Ops bypass the whitelist, which is why SpikeA always got in.
- **Server tick of a player:** `ServerGamePacketListenerImpl.tick()` runs `resetPosition()`, then `player.doTick()`, then `player.absSnapTo(firstGoodX, firstGoodY, firstGoodZ, …)`, every tick.
- **Movement validation:** `handleMovePlayer` teleports the player back without a log line when the move makes them collide with blocks they weren't touching (`isEntityCollidingWithAnythingNew`). Creative mode doesn't skip this, only "moved wrongly".
- **Entity tracking:**
  - `ChunkMap.TrackedEntity.updatePlayer(viewer)` runs from `ChunkMap.tick()` when the tracked entity changes section, and from `ChunkMap.move(viewer)` when the server handles that viewer's move packet.
  - `isChunkTracked` needs the chunk to be sent already (not pending in `PlayerChunkSender`).
  - A still `LocalPlayer` sends a position reminder every 20 ticks.
- **Locator bar** (`ServerWaypointManager`, `WaypointTransmitter`):
  - Every position change of a player calls `updateWaypoint` (from `Entity.setPosRaw`).
  - A nearby player's waypoint is a block connection: `UPDATE` when their block position changes, and a fresh `TRACK` when the connection breaks (moved more than 1 block).
  - The game rule is `locator_bar`.
- **Other players on the client:**
  - `EntityRenderer` draws at `lerp(partialTick, xOld, getX())`.
  - `commonTick()` saves the old position, then runs `getInterpolation().interpolate()`; `InterpolationHandler.cancel()` stops it.
  - `Minecraft.runTick` handles queued packets, then runs ticks, then renders. On most frames no tick runs, so packet effects reach the screen before the next tick.
- **Bytes:**
  - Vanilla's bandwidth logger (`Varint21FrameDecoder` → `BandwidthDebugMonitor`) counts wire frames, compressed.
  - `PacketDecoder.decode` reports each packet's decoded size to `JvmProfiler.onPacketReceived(protocol, type, address, bytes)`. That's a clean hook for a per-type breakdown.
  - Fabric custom payloads carry their id as a string in every packet.
- **Input:** `LocalPlayer.input` is a public `ClientInput` field. A plain `ClientInput` presses nothing; `KeyboardInput` is the real one.
