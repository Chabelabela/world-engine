# M1b Frame-Handoff Spike Plan

> **For agentic workers:** this is an **experiment plan**, not a build plan. Its product is evidence plus a decision record, `docs/adr/0001-frames-and-handoff.md`. The spike code is throwaway and stays on its branch. Run the experiments in order, record every number asked for, and stop at the time-box even if questions remain open. "Undecided, here's why" is a valid ADR outcome.

**Goal:** decide, with measurements, how a player moves between a planet's surface frame and a ship's frame without a loading screen, and how ships are rendered and synced to clients. Then write those decisions down so M1d–M1f (and M2) can be planned from facts.

**Time-box:** 10 working days. **Branch:** `spike/m1-handoff`, cut from `main` after M1a merges. **Only the ADR merges** (plus any no-regret utility that comes with a test). Everything else stays on the spike branch for reference.

**Inputs:**
- ROADMAP §4.3 (the spatial model)
- [M1 overview](2026-11-20-m1-overview.md) Q1–Q5
- `we-frames` from M1a: `PlanetFrame` for the handoff transforms, `CubeSphere::fold` and `QuarterTurns` for seams, `PlanetLayout` for level coordinates

---

## The questions, and what "good" looks like

| # | Question | Measure | Good enough |
|---|---|---|---|
| Q1 | Does a same-level teleport between a planet-face region and a shipyard plot far away (e.g. x ≈ 20,000,000) show any loading screen? | Screen recording plus a client log hook on screen changes | Never |
| Q1b | How long until the destination is visible, with and without pre-loading? | Frames with missing chunks after arrival, with the destination chunks forced (ticketed) 0, 20 and 60 ticks before the jump | ≤ 2 frames with pre-loading |
| Q2 | Can blocks stored in a shipyard plot be drawn at an arbitrary position and rotation near the player through Minecraft 26.3's GPU abstraction, with Sodium installed? | Frame-time cost of a 300-block test ship (F3 frame graph, or Sodium's debug overlay) | ≤ 1 ms on the reference GPU; works with and without Sodium |
| Q3 | Should the client get ship blocks through vanilla chunk tracking (mixin to keep far plot chunks loaded on the client), or through a custom ship-snapshot payload (blocks plus block entities, then deltas)? | Implementation effort, correctness when the plot is far from the player, and bytes for the test ship | Pick one, with numbers |
| Q4 | Can a player inside a moving ship go surface frame → ship frame → surface frame with at most one visible pop? | Count of visible discontinuities on video, frame by frame, over 10 handoffs | ≤ 1 pop per handoff, or a mask (E7) that hides it in ≤ 300 ms |
| Q5 | Do two clients aboard one ship stay consistent through a handoff? | Positions agree on both clients after the handoff; bytes per second | Same within 1 block; ≤ 2 KB/s per player for an idle ship |

## Ground rules

- **26.3 APIs move fast.** Before using any Minecraft or Fabric API (teleport, chunk tickets, payload registration, rendering), check its current signature in the [Fabric docs](https://docs.fabricmc.net/) for 26.3 and in the decompiled sources (Loom's `genSources`). Write down any API surprise in the ADR's "API notes".
- **Render only through Minecraft's GPU abstraction,** never raw OpenGL or Vulkan (ROADMAP §2). If feasible, repeat E3 on one current 26.4 snapshot with Vulkan enabled.
- **Prior art (study only):** Valkyrien Skies 2 for shipyard-based ship rendering and players on ships, and Immersive Portals for cross-region rendering. Check their licences before copying any code. Prefer copying ideas.
- **The math is not part of the experiment:** use `we-frames` for every frame transform and fold. For the spike only, a throwaway spike FFI function or a Java port is fine.

## Setup (day 1)

1. Branch `spike/m1-handoff`. Add a dev-only command root `/we spike …`, registered only when `-Dworldengine.spike=true`.
2. In a superflat creative test world:
   - Mark a 512×512 "face region" at the origin.
   - Reserve a "shipyard plot" at (20,000,000, 64, 20,000,000).
   - `/we spike ship` copies a 7×5×9 test structure (glass, stairs, a door, a chest, redstone lamp and lever) into the plot. The structure has facing blocks and a block entity on purpose.
3. Add a client-side HUD counter for the M1 measurements: frames since the last teleport that had missing chunks within 32 blocks of the camera, plus bytes received per second.

## Experiments

### E1 — Same-level teleport baseline (Q1, Q1b) · day 2
- `/we spike jump <x> <y> <z> [preloadTicks]` teleports the player within the same level, keeping velocity and look direction. If `preloadTicks` is given, add a forced ticket for the destination chunks that many ticks earlier, and remove it 5 s after arrival.
- Run each variant 10 times: preload 0, 20 and 60 ticks; render distance 8 and 16.
- Record: any loading screen (yes/no), frames with missing chunks, and network bytes in the 2 s after arrival.

### E2 — Momentum and look across a seam (supports M1e) · day 2
- `/we spike seam` walks the player off a face edge (both face regions are laid out with `PlanetLayout` in the test world). It teleports them to `CubeSphere::fold`'s point, with the grid velocity (Minecraft's block-space motion) rotated by `QuarterTurns::apply` and yaw by `QuarterTurns::rotate_yaw`. `QuarterTurns` is exact only for grid quantities: grid lines kink at seams, so a physical velocity would have to cross through the body frame (see `Folded::turns`).
- Record: whether a running jump crosses smoothly (video); position error compared with `fold` (should be 0); and any server-side movement-correction ("moved wrongly" / rubber-banding) messages.

### E3 — Ship rendering at a transform (Q2) · days 3–4
- Client: build a mesh of the plot's blocks and draw it at an arbitrary pose (position plus quaternion) that `/we spike pose` or a slow automatic spin changes, using Minecraft's block-model rendering into a vertex buffer through the 26.3 GPU API.
- Run with and without Sodium. Record frame-time cost, any visual errors (lighting, block entities, translucency), and the API path used.

### E4 — Getting ship blocks to the client (Q3) · days 5–6
- **Option A, vanilla tracking:** a mixin makes the server keep sending the plot's chunks to players near the ship's *pose*, and the client keep them in its chunk storage even when the plot is far from the player.
- **Option B, snapshot payload:** on first sight, the server sends a ship snapshot (palette plus block states plus block-entity NBT); block changes follow as small deltas. The client keeps its own ship storage, independent of vanilla chunks.
- Implement whichever is cheaper first. Measure bytes for the test ship and correctness after an edit (place or break a block inside the ship). Then sketch the other one far enough to compare effort.

### E5 — The handoff itself (Q4) · days 7–8
- The player stands inside the rendered ship over the face region (surface frame). The ship climbs past a threshold (`/we spike climb` scripts a simple vertical move).
- **Handoff:** move the player into the plot at the same position relative to the ship (ship frame). Keep drawing the ship interior from E3/E4. Draw the outside as a stand-in backdrop: a large textured sphere for the planet below, plus the sky.
- Then hand back on descent.
- Do 10 round trips. Record video, count pops frame by frame, and list what causes each (chunk churn, sky reset, entity interpolation, camera jump).

### E6 — Two players (Q5) · day 9
- Run a dedicated server with two clients aboard the ship, and do E5 once. Record the positions on both clients after the handoff, the bytes per second idle and during the handoff, and any desync.

### E7 — Fallback mask (only if E5 fails its bar) · day 9
- A 200–300 ms "atmospheric transition" overlay (cloud or plasma fade) that hides the handoff frame. Record whether it hides every pop seen in E5.

## Writing the ADR (day 10)

Create `docs/adr/0001-frames-and-handoff.md` with these sections:

```markdown
# ADR 0001: Frames and the surface ↔ ship handoff

- Status: proposed | accepted
- Date:
- Deciders:

## Context
(One paragraph: the three frames from ROADMAP §4.3 and why the handoff is the riskiest part of M1.)

## Options considered
(At least: one universe level + shipyard plot; separate levels per planet with cross-level transfer; ship blocks in vanilla chunks vs a snapshot payload.)

## Decision
(The chosen combination, in a few sentences.)

## Evidence
(A table with every measurement from E1–E7 against its "good enough" bar, and links to the videos.)

## Consequences
- Mixins required (target class and method, why, risk of conflict with Sodium or other mods)
- Changes to ROADMAP §4.3, if any
- What M1d, M1e, M1f and M2 must now do

## API notes (26.3)
(Every API signature that differed from expectations.)

## Open risks
```

Then:
1. Open a PR containing only the ADR. Its status stays "proposed" until the owner accepts it.
2. Once it's accepted, write the M1d, M1e and M1f plans from it (writing-plans skill), and update the [M1 overview](2026-11-20-m1-overview.md).

## Exit

The ADR is accepted, and every question Q1–Q5 has either a measured answer or an explicit "undecided, because…" with a follow-up assigned. If day 10 arrives with E5 unfinished, write the ADR with what exists. Recommend either a second, shorter spike or the E7 fallback.
