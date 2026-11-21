# E1: same-level teleport baseline (Q1, Q1b)

Spike branch only. Input for ADR 0001. Run on 2026-11-21.

## Setup

- **Server:** local dedicated server (`./gradlew runServer`, directory `mod/run-server`), superflat, creative, view distance 16.
- **Client:** `./gradlew runSpikeClient`, which auto-joins as `SpikeA`.
  - Vanilla renderer, no Sodium.
  - Windowed at the default size, vsync on, about 60 fps.
  - RTX 3070.
- **Driver:** `/we spike e1 SpikeA 10`, sent over RCON (`tools/spike/rcon.py`).
  - Each round is one jump per pre-load variant (0, 20, 60 ticks), interleaved.
  - Jumps alternate ground (256.5, -60, 256.5) ↔ ship (20,000,003.5, 65, 20,000,003.5), about 28.3 million blocks apart.
  - 200 ticks separate one arrival from the next start.
  - The pre-load ticket covers the destination chunk with a radius of 16 chunks (the server view distance) and is removed 5 s after arrival.
- **Measurement** (client HUD, `[spike] E1 …` log lines, `tools/spike/e1_report.py`):
  - A frame counts as missing if, within 32 blocks of the camera, either the client lacks a chunk, or a non-empty section **inside the view frustum** has no compiled mesh.
  - rx is the bytes the client received in the 2 s after arrival (vanilla bandwidth logger).
- **Render distance:** set in the client's `options.txt`, with `graphicsPreset:"custom"`. Otherwise the preset resets it at startup; see the API notes.

## Results

| RD | pre-load (ticks) | n | loading screens | missing frames: median | p90 | max | ≤ 2 frames (bar) | rx in 2 s, median |
|---|---|---|---|---|---|---|---|---|
| 16 | 0 | 30 | 0 | 30.5 | 37 | 41 | 0/30 | 151 KB |
| 16 | 20 | 30 | 0 | 11.5 | 14 | 29 | 0/30 | 151 KB |
| 16 | 60 | 30 | 0 | 11.5 | 14 | 17 | 0/30 | 151 KB |
| 8 | 0 | 10 | 0 | 29 | 31 | 33 | 0/10 | 46 KB |
| 8 | 20 | 10 | 0 | 20 | 22 | 26 | 0/10 | 46 KB |
| 8 | 60 | 10 | 0 | 21 | 22 | 23 | 0/10 | 46 KB |

RD 16 has three batches (90 jumps). The second was meant to be RD 8, but the graphics preset forced it back to 16. Each row's RD comes from the client's own log line, so no row is mislabelled.

By direction (median missing frames):

| RD | pre-load | → ship | → ground |
|---|---|---|---|
| 16 | 0 | 31 | 26 |
| 16 | 20 | 9 | 13 |
| 16 | 60 | 9 | 13 |
| 8 | 0 | 25 | 30 |
| 8 | 20 | 18 | 22 |
| 8 | 60 | 18 | 22 |

## Findings

1. **Q1, no loading screen: met.** None of the 120 jumps opened a loading screen (`LevelLoadingScreen` or `ProgressScreen`). A same-level teleport over 28 million blocks is seamless as far as screens go.
2. **Q1b, at most 2 missing frames with pre-loading: not met, and server-side pre-loading cannot meet it.**
   - The best case was a median of 9 frames (about 150 ms at 60 fps), to the ship at RD 16 with pre-loading.
   - Pre-loading cut RD 16 from a median of about 30 to about 12 frames.
   - 60 ticks of pre-loading did no better than 20.
   - A ticket only loads chunks on the **server**. After arrival the client still has to receive the chunks (about 150 KB in 2 s at RD 16) and mesh them, and nothing sent before the jump can be shown at the destination.
   - So reaching ≤ 2 frames needs the destination's blocks on the client **before** the handoff. Two ways to get there:
     - a ship snapshot payload (E4 option B);
     - keeping the plot's chunks on the client (E4 option A).
   - Failing that, the E7 mask. This carries straight into Q3 and Q4.
3. **Unexplained: RD 8 is worse than RD 16 once pre-loaded** (20 vs 12 frames), although it receives less data. A guess, not checked: vanilla adapts chunks-per-tick to client feedback (the chunk batch size), so a smaller view starts sending more slowly. Follow-up: log `ClientboundChunkBatchStart/Finished` timings per jump.
4. Every missing frame came from an uncompiled section in view, not from a missing chunk. Chunk data reaches the client before the mesh is ready.

## Caveats

- Superflat terrain is cheap to send and mesh; universe-level terrain will be slower.
- Both destinations had been visited before, so their chunks were already generated.
- One player on a local server, so network latency is near zero.
- No Sodium yet; Sodium changes meshing entirely, so E1 must be repeated with it.
- One PC, windowed. Missing frames scale with fps; 9 frames is 150 ms at 60 fps.

## API notes (26.3)

- `graphicsPreset` in `options.txt` (fast, fancy, fabulous, custom) re-applies its render distance when the client loads, so `renderDistance` only sticks with `"custom"`.
- Singleplayer uses an in-memory connection with no bandwidth monitor, so rx is measurable only against a dedicated server.
- Vanilla compiles only the sections inside the view frustum. After a teleport, sections behind the camera stay uncompiled until you look at them.
- `TicketType.FORCED` is persistent (saved with the world). The spike registers its own non-persistent ticket type.
- `level-type=minecraft:flat` with empty `generator-settings` logs "No key layers in MapLike[{}]" but still generates the default flat world.

## Raw data

| RD | pre-load | to | loading screen | missing frames | frames in 2 s | rx bytes in 2 s | last missing |
|---|---|---|---|---|---|---|---|
| 16 | 0 | ship | false | 41 | 119 | 50596 | uncompiled section 1250000,4,1250000 |
| 16 | 20 | ground | false | 29 | 119 | 160807 | uncompiled section 14,-4,18 |
| 16 | 60 | ship | false | 9 | 119 | 145345 | uncompiled section 1250000,4,1250000 |
| 16 | 0 | ground | false | 18 | 120 | 161501 | uncompiled section 14,-4,18 |
| 16 | 20 | ship | false | 10 | 119 | 145345 | uncompiled section 1250000,4,1250000 |
| 16 | 60 | ground | false | 17 | 119 | 160921 | uncompiled section 14,-4,18 |
| 16 | 0 | ship | false | 34 | 119 | 145443 | uncompiled section 1250000,4,1250000 |
| 16 | 20 | ground | false | 14 | 119 | 161051 | uncompiled section 14,-4,18 |
| 16 | 60 | ship | false | 9 | 119 | 145345 | uncompiled section 1250000,4,1250000 |
| 16 | 0 | ground | false | 35 | 119 | 160945 | uncompiled section 14,-4,18 |
| 16 | 20 | ship | false | 8 | 118 | 147403 | uncompiled section 1250000,4,1250000 |
| 16 | 60 | ground | false | 13 | 119 | 161502 | uncompiled section 14,-4,18 |
| 16 | 0 | ship | false | 31 | 119 | 147670 | uncompiled section 1250000,4,1250000 |
| 16 | 20 | ground | false | 13 | 119 | 160712 | uncompiled section 14,-4,18 |
| 16 | 60 | ship | false | 9 | 119 | 147416 | uncompiled section 1250000,4,1250000 |
| 16 | 0 | ground | false | 20 | 119 | 160891 | uncompiled section 14,-4,18 |
| 16 | 20 | ship | false | 11 | 119 | 147865 | uncompiled section 1250000,4,1250000 |
| 16 | 60 | ground | false | 13 | 119 | 160741 | uncompiled section 14,-4,18 |
| 16 | 0 | ship | false | 34 | 119 | 147127 | uncompiled section 1250000,4,1250000 |
| 16 | 20 | ground | false | 13 | 119 | 160897 | uncompiled section 14,-4,18 |
| 16 | 60 | ship | false | 9 | 119 | 147640 | uncompiled section 1250000,4,1250000 |
| 16 | 0 | ground | false | 34 | 119 | 160748 | uncompiled section 14,-4,18 |
| 16 | 20 | ship | false | 9 | 119 | 147660 | uncompiled section 1250000,4,1250000 |
| 16 | 60 | ground | false | 14 | 119 | 161378 | uncompiled section 14,-4,18 |
| 16 | 0 | ship | false | 30 | 119 | 147228 | uncompiled section 1250000,4,1250000 |
| 16 | 20 | ground | false | 13 | 119 | 160385 | uncompiled section 14,-4,18 |
| 16 | 60 | ship | false | 10 | 119 | 147608 | uncompiled section 1250000,4,1250000 |
| 16 | 0 | ground | false | 37 | 119 | 160421 | uncompiled section 14,-4,18 |
| 16 | 20 | ship | false | 11 | 119 | 147559 | uncompiled section 1250000,4,1250000 |
| 16 | 60 | ground | false | 13 | 119 | 161268 | uncompiled section 14,-4,18 |
| 16 | 0 | ship | false | 31 | 117 | 147773 | uncompiled section 1250000,4,1250000 |
| 16 | 20 | ground | false | 13 | 118 | 160659 | uncompiled section 14,-4,18 |
| 16 | 60 | ship | false | 9 | 118 | 147769 | uncompiled section 1250000,4,1250000 |
| 16 | 0 | ground | false | 36 | 119 | 160557 | uncompiled section 14,-4,18 |
| 16 | 20 | ship | false | 9 | 118 | 147344 | uncompiled section 1250000,4,1250000 |
| 16 | 60 | ground | false | 16 | 118 | 160857 | uncompiled section 14,-4,18 |
| 16 | 0 | ship | false | 15 | 119 | 147957 | uncompiled section 1250000,4,1250000 |
| 16 | 20 | ground | false | 14 | 119 | 160949 | uncompiled section 14,-4,18 |
| 16 | 60 | ship | false | 9 | 118 | 147948 | uncompiled section 1250000,4,1250000 |
| 16 | 0 | ground | false | 37 | 119 | 164329 | uncompiled section 14,-4,18 |
| 16 | 20 | ship | false | 9 | 118 | 147758 | uncompiled section 1250000,4,1250000 |
| 16 | 60 | ground | false | 14 | 119 | 164235 | uncompiled section 14,-4,18 |
| 16 | 0 | ship | false | 31 | 119 | 148097 | uncompiled section 1250000,4,1250000 |
| 16 | 20 | ground | false | 14 | 119 | 163009 | uncompiled section 14,-4,18 |
| 16 | 60 | ship | false | 10 | 119 | 148632 | uncompiled section 1250000,4,1250000 |
| 16 | 0 | ground | false | 24 | 119 | 163736 | uncompiled section 14,-4,18 |
| 16 | 20 | ship | false | 9 | 118 | 147909 | uncompiled section 1250000,4,1250000 |
| 16 | 60 | ground | false | 14 | 119 | 162517 | uncompiled section 14,-4,18 |
| 16 | 0 | ship | false | 15 | 119 | 147784 | uncompiled section 1250000,4,1250000 |
| 16 | 20 | ground | false | 14 | 119 | 161894 | uncompiled section 14,-4,18 |
| 16 | 60 | ship | false | 10 | 119 | 148940 | uncompiled section 1250000,4,1250000 |
| 16 | 0 | ground | false | 38 | 118 | 163659 | uncompiled section 14,-4,18 |
| 16 | 20 | ship | false | 8 | 120 | 148137 | uncompiled section 1250000,4,1250000 |
| 16 | 60 | ground | false | 13 | 118 | 162667 | uncompiled section 14,-4,18 |
| 16 | 0 | ship | false | 35 | 118 | 148385 | uncompiled section 1250000,4,1250000 |
| 16 | 20 | ground | false | 14 | 118 | 163354 | uncompiled section 14,-4,18 |
| 16 | 60 | ship | false | 10 | 118 | 147847 | uncompiled section 1250000,4,1250000 |
| 16 | 0 | ground | false | 37 | 118 | 164179 | uncompiled section 14,-4,18 |
| 16 | 20 | ship | false | 10 | 118 | 148005 | uncompiled section 1250000,4,1250000 |
| 16 | 60 | ground | false | 14 | 118 | 162298 | uncompiled section 14,-4,18 |
| 16 | 0 | ship | false | 30 | 117 | 147948 | uncompiled section 1250000,4,1250000 |
| 16 | 20 | ground | false | 12 | 118 | 162593 | uncompiled section 14,-4,18 |
| 16 | 60 | ship | false | 10 | 119 | 148174 | uncompiled section 1250000,4,1250000 |
| 16 | 0 | ground | false | 21 | 119 | 163043 | uncompiled section 14,-4,18 |
| 16 | 20 | ship | false | 10 | 119 | 147809 | uncompiled section 1250000,4,1250000 |
| 16 | 60 | ground | false | 13 | 119 | 162407 | uncompiled section 14,-4,18 |
| 16 | 0 | ship | false | 35 | 119 | 147804 | uncompiled section 1250000,4,1250000 |
| 16 | 20 | ground | false | 13 | 119 | 161987 | uncompiled section 14,-4,18 |
| 16 | 60 | ship | false | 9 | 119 | 148511 | uncompiled section 1250000,4,1250000 |
| 16 | 0 | ground | false | 21 | 119 | 161237 | uncompiled section 14,-4,18 |
| 16 | 20 | ship | false | 9 | 119 | 146942 | uncompiled section 1250000,4,1250000 |
| 16 | 60 | ground | false | 13 | 119 | 161039 | uncompiled section 14,-4,18 |
| 16 | 0 | ship | false | 16 | 119 | 147883 | no chunk 1249998,1249998 |
| 16 | 20 | ground | false | 13 | 119 | 160648 | uncompiled section 14,-4,18 |
| 16 | 60 | ship | false | 9 | 119 | 147355 | uncompiled section 1250000,4,1250000 |
| 16 | 0 | ground | false | 21 | 119 | 161003 | uncompiled section 14,-4,18 |
| 16 | 20 | ship | false | 8 | 118 | 147165 | uncompiled section 1250000,4,1250000 |
| 16 | 60 | ground | false | 13 | 119 | 161163 | uncompiled section 14,-4,18 |
| 16 | 0 | ship | false | 14 | 119 | 147736 | uncompiled section 1250000,4,1250000 |
| 16 | 20 | ground | false | 14 | 119 | 160630 | uncompiled section 14,-4,18 |
| 16 | 60 | ship | false | 9 | 119 | 147535 | uncompiled section 1250000,4,1250000 |
| 16 | 0 | ground | false | 24 | 119 | 160464 | uncompiled section 14,-4,18 |
| 16 | 20 | ship | false | 9 | 119 | 147094 | uncompiled section 1250000,4,1250000 |
| 16 | 60 | ground | false | 13 | 119 | 160781 | uncompiled section 14,-4,18 |
| 16 | 0 | ship | false | 16 | 119 | 147092 | uncompiled section 1250000,4,1250000 |
| 16 | 20 | ground | false | 13 | 119 | 161206 | uncompiled section 14,-4,18 |
| 16 | 60 | ship | false | 9 | 119 | 147871 | uncompiled section 1250000,4,1250000 |
| 16 | 0 | ground | false | 26 | 119 | 161435 | uncompiled section 14,-4,18 |
| 16 | 20 | ship | false | 9 | 119 | 146898 | uncompiled section 1250000,4,1250000 |
| 16 | 60 | ground | false | 13 | 119 | 161252 | uncompiled section 14,-4,18 |
| 8 | 0 | ship | false | 28 | 118 | 47718 | uncompiled section 1250000,4,1250000 |
| 8 | 20 | ground | false | 26 | 118 | 46425 | uncompiled section 14,-4,18 |
| 8 | 60 | ship | false | 20 | 118 | 47511 | uncompiled section 1250000,4,1250000 |
| 8 | 0 | ground | false | 30 | 118 | 46836 | uncompiled section 14,-4,18 |
| 8 | 20 | ship | false | 19 | 118 | 47362 | uncompiled section 1250000,4,1250000 |
| 8 | 60 | ground | false | 22 | 120 | 46918 | uncompiled section 14,-4,18 |
| 8 | 0 | ship | false | 24 | 118 | 49726 | uncompiled section 1250000,4,1250000 |
| 8 | 20 | ground | false | 21 | 120 | 46389 | uncompiled section 14,-4,18 |
| 8 | 60 | ship | false | 18 | 118 | 51806 | uncompiled section 1250000,4,1250000 |
| 8 | 0 | ground | false | 30 | 118 | 46696 | uncompiled section 14,-4,18 |
| 8 | 20 | ship | false | 18 | 118 | 49872 | uncompiled section 1250000,4,1250000 |
| 8 | 60 | ground | false | 23 | 118 | 46071 | uncompiled section 14,-4,18 |
| 8 | 0 | ship | false | 25 | 118 | 50104 | uncompiled section 1250000,4,1250000 |
| 8 | 20 | ground | false | 22 | 118 | 46430 | uncompiled section 14,-4,18 |
| 8 | 60 | ship | false | 18 | 118 | 49698 | uncompiled section 1250000,4,1250000 |
| 8 | 0 | ground | false | 33 | 118 | 47074 | uncompiled section 14,-4,18 |
| 8 | 20 | ship | false | 18 | 118 | 49046 | uncompiled section 1250000,4,1250000 |
| 8 | 60 | ground | false | 22 | 118 | 46696 | uncompiled section 14,-4,18 |
| 8 | 0 | ship | false | 24 | 118 | 49568 | uncompiled section 1250000,4,1250000 |
| 8 | 20 | ground | false | 22 | 118 | 46460 | uncompiled section 14,-4,18 |
| 8 | 60 | ship | false | 18 | 118 | 50483 | uncompiled section 1250000,4,1250000 |
| 8 | 0 | ground | false | 30 | 118 | 46613 | uncompiled section 18,-4,18 |
| 8 | 20 | ship | false | 19 | 118 | 49033 | uncompiled section 1250000,4,1250000 |
| 8 | 60 | ground | false | 22 | 118 | 46850 | uncompiled section 14,-4,18 |
| 8 | 0 | ship | false | 27 | 118 | 49862 | uncompiled section 1250000,4,1250000 |
| 8 | 20 | ground | false | 22 | 118 | 46443 | uncompiled section 14,-4,18 |
| 8 | 60 | ship | false | 18 | 118 | 49785 | uncompiled section 1250000,4,1250000 |
| 8 | 0 | ground | false | 31 | 121 | 46819 | uncompiled section 14,-4,18 |
| 8 | 20 | ship | false | 17 | 119 | 49097 | uncompiled section 1250000,4,1250000 |
| 8 | 60 | ground | false | 22 | 120 | 46564 | uncompiled section 14,-4,18 |

