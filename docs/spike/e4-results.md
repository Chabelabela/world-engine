# E4: getting ship blocks to the client (Q3)

Spike branch only. Input for ADR 0001. Run on 2026-11-21.

**Recommendation: option B (snapshot payload + deltas).** It works: 13 of 13 packets passed the hash check in the edit test, with no mismatches. It's about 50× cheaper than A for the first send, and it needs one optional mixin instead of six or more deep ones.

## Setup

- Same dedicated server and auto-joining client as E1. The test ship is 7×5×9 (315 cells, 217 blocks, one block entity) at the plot origin, about 28.3 million blocks from the player.
- The ship's **pose** is (256.5, -50, 256.5) in the surface frame. Every player within 128 blocks of it gets a copy, wherever the plot is.
- `/we spike e4 bytes` encodes both options with the real 26.3 codecs. "Deflated" is zlib, which is what network compression sends.
- `/we spike e4 test SpikeA` stands the player at (256.5, -60, 256.5), next to the pose and far from the plot, then makes the 12 steps below, 2 s apart. After every packet the client compares its copy's hash with the server's.

## Option B as built

- **Server** (`ShipSync`):
  - On first sight, sends `ship_snapshot`: a palette of global block-state ids, the cells packed with `SimpleBitStorage` (like a chunk section), and each block entity's `getUpdateTag`.
  - Edits are collected from `ServerLevel.sendBlockUpdated` (mixin `ServerLevelSpikeMixin`, injected at HEAD) and sent once per tick as `ship_delta` (cell, state id, optional block-entity tag, expected hash).
  - Leaving range sends `ship_forget`.
  - While the ship is active, a `spike_ship` ticket (LOADING | SIMULATION) keeps the plot block-ticking. `Level.setBlock` only calls `sendBlockUpdated` in block-ticking chunks, and scheduled ticks (the lamp turning off) need it too.
- **Client** (`ClientShipStore`):
  - Keeps its own copy (cell → state, cell → block-entity tag), separate from vanilla's chunk storage.
  - E3 will mesh from this copy.

## Results

### Bytes for the test ship

| | Option B (snapshot / delta) | Option A (vanilla packets) |
|---|---|---|
| First sight, raw | 199 B | 17,529 B (one chunk column, light included) |
| First sight, deflated | 116 B | 375 B |
| On the wire, approx. | ≈ 225 B: 199 is under the 256 B compression threshold, so it goes uncompressed, plus about 25 B of channel id | ≈ 380 B (compressed) |
| One-block edit, raw | 11 B + 23 B channel id | 10 B (`ClientboundBlockUpdatePacket`) |
| 7-block edit (one tick) | 21 B + channel id | about 30 B (`SectionBlocksUpdate`, estimated) |
| Idle ship | 0 B/s | 0 B/s |

- Option A's first-sight cost is almost all chunk-column overhead: light data and every empty section of the column. It grows with the number of plot chunks a ship touches, not with its blocks.
- Option B grows with the ship's box.
- In the universe level (80 sections tall) A's per-column overhead will be larger still.

### Correctness: edit test (client far from the plot)

| Step | Server sent | Client hash |
|---|---|---|
| (join range) | snapshot, 315 cells, 1 BE, 199 B | ok |
| 1 lever on | delta: 2 cells (lever, lamp lit) | ok |
| 2 lever off | delta: lever, then 4 ticks later delta: lamp (scheduled tick) | ok, ok |
| 3 break glass | delta: 1 cell | ok |
| 4 place glass | delta: 1 cell | ok |
| 5 remove chest | delta: 1 cell, block entity removed | ok |
| 6 place chest | delta: 1 cell + block-entity tag | ok |
| 7 floor row → stone | delta: 7 cells in one packet | ok |
| 8 floor row → planks | delta: 7 cells | ok |
| 9 leave range | forget | (copy dropped) |
| 10 edit while away | nothing sent | — |
| 11 return | fresh snapshot, including the away edit | ok |
| 12 lever off | 2 deltas | ok |

13 hash checks, 0 mismatches. In the HUD, `ship copy: 7x5x9 at 20000000, 64, 20000000, 1 BE | snapshots 2, deltas 11 | hash ok`.

## Option A sketch (from the 26.3 code, not built)

The client would keep the plot's real chunks in vanilla's chunk storage although the player is far away. What that takes:

**Server**
1. `ChunkMap.updateChunkTracking` / `applyChunkTrackingView`.
   - A player's `ChunkTrackingView` is a square around their own chunk (`ChunkTrackingView.of(center, viewDistance)`).
   - Plot chunks must be added to it for players near the ship's pose, and removed again. The diff then calls `markChunkPendingToSend` / `dropChunk`.
2. `ChunkMap.getPlayers(ChunkPos, boolean)`.
   - `ChunkHolder.broadcastChanges` uses it to choose who receives block, light and block-entity updates.
   - Ship viewers must be included, or edits never reach them.
3. `ChunkMap.isChunkTracked` (entity visibility) must agree with the two above.

**Client**
4. `ClientChunkCache` stores chunks in a fixed (2r+1)² ring buffer around the player (`Storage.inRange`).
   - `replaceWithPacketData` drops anything outside it ("Ignoring chunk since it's not in the view range").
   - `getChunk` and `drop` ignore it too.
   - All three need a second map for plot chunks.
   - `updateViewCenter` must not evict them.
5. The light engine and the sections' dirty tracking must accept chunks outside the view.
6. The vanilla renderer would then also mesh the plot chunks at their real position, 20 million blocks away. That's wasted work unless suppressed by yet another hook.

So A means about 6 mixins in the busiest classes of chunk loading. Those are the classes that performance and chunk mods also patch: Sodium on the client, C2ME and similar on the server. **Unverified:** which of these methods Sodium's own mixins touch. Check its sources before ever choosing A.

What A would give for free: vanilla light data, block-entity sync and section updates. B has to rebuild these: client-side lighting for the ship mesh and block-entity instances for rendering (E3), and its own delta channel (done here).

## Decision input for the ADR

- **Q3 → option B.** It's built and correct in this test, cheaper on first sight, 0 B/s idle, and needs one small, optional mixin (`ServerLevel.sendBlockUpdated` at HEAD). The other way is to call `ShipSync` from wherever ships are edited, which needs no mixin at all.
- **For E1/Q1b:** B can deliver the ship to the client **before** the handoff, because the snapshot goes out on first sight while the player is still in the surface frame. After the handoff, the ship interior needs no chunk loading. Only the outside backdrop and nearby plot terrain do.
- **For Q5:** idle cost is 0 B/s per ship (bar: ≤ 2 KB/s).

## Open points

- Entities aboard (item frames, mobs, dropped items) aren't covered by either option yet. Ship-relative entity sync is a separate design question for M2.
- Block-entity data that changes without a block change (a sign's text, a chest's contents) needs `BlockEntity.setChanged` hooks, or polling of the update tag.
- Big ships: compute the hash incrementally instead of over the whole box per delta, and split snapshots above Fabric's payload limit (`registerLarge`).
- Interaction: players in the surface frame editing ship blocks through the ship's transform (server-side raycast into the plot) is E5/M2 work, and the same for both options.

## API notes (26.3)

- Fabric networking: `PayloadTypeRegistry.clientboundPlay()` (formerly `playS2C()`). Payloads use `CustomPacketPayload.codec(writer, reader)` with `RegistryFriendlyByteBuf`.
- `Level.setBlock` notifies clients (`sendBlockUpdated`) only if the chunk is at least `FullChunkStatus.BLOCK_TICKING`.
- `ClientboundLevelChunkWithLightPacket(LevelChunk, LevelLightEngine, BitSet, BitSet)` and `ClientboundBlockUpdatePacket(BlockGetter, BlockPos)` can be encoded off-line with their `STREAM_CODEC`s to measure size.
- The client's chunk storage refuses chunks outside its view square, with the warning "Ignoring chunk since it's not in the view range".
