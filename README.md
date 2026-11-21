# World Engine

A Minecraft (Java Edition 26.3, Fabric) mod that turns the game into a physically simulated universe: planets you can walk all the way around, spacecraft built from blocks that fly real orbits, and worlds that keep evolving without you. A Rust simulation core runs alongside Minecraft. The mod calls it through Java's Foreign Function & Memory API.

**North Star:** build a spacecraft, launch from a procedurally generated planet, reach orbit, fly to its moon, land, and come home, all in one continuous simulation with no loading screens.

**Status:** M0 (foundations) and M1a, M1c and M1d of the spatial engine are built and green in CI on Linux, Windows and macOS, including GameTests in a headless server. Creating a world with World Type **World Engine** gives a universe level with the home planet and its moon. Next is the M1b frame-handoff spike, which needs a desktop with Minecraft. Manual in-game checks are still open for M0 (`/worldengine bodies`) and M1d (see its plan, Task 11).

| Document | What it is |
|---|---|
| [docs/VISION.md](docs/VISION.md) | The original concept |
| [docs/ROADMAP.md](docs/ROADMAP.md) | The master plan: architecture, the spatial model, milestones M0–M10, risks, open decisions |
| [docs/superpowers/plans/2026-11-20-m0-foundations.md](docs/superpowers/plans/2026-11-20-m0-foundations.md) | Step-by-step implementation plan for milestone 0 (Rust core + Fabric mod + CI), done |
| [docs/superpowers/plans/2026-11-20-m1-overview.md](docs/superpowers/plans/2026-11-20-m1-overview.md) | M1 (spatial engine) overview: phases M1a–M1f, design decisions, spike questions |
| [docs/superpowers/plans/2026-11-20-m1a-foundations-and-frames.md](docs/superpowers/plans/2026-11-20-m1a-foundations-and-frames.md) | Step-by-step plan for M1a: M0 review carry-overs and the `we-frames` cube-sphere math |
| [docs/superpowers/plans/2026-11-20-m1b-handoff-spike.md](docs/superpowers/plans/2026-11-20-m1b-handoff-spike.md) | Experiment plan for the frame-handoff spike, ending in ADR 0001 |
| [docs/superpowers/plans/2026-11-20-m1c-planet-terrain.md](docs/superpowers/plans/2026-11-20-m1c-planet-terrain.md) | Step-by-step plan for M1c: deterministic planet terrain behind a thread-safe batched FFI call, done |
| [docs/superpowers/plans/2026-11-20-m1d-universe-level.md](docs/superpowers/plans/2026-11-20-m1d-universe-level.md) | Step-by-step plan for M1d: the World Engine world type, universe chunk generator, planet layout and GameTests, done |

## Building

Prerequisites: Rust stable ≥ 1.85, JDK 25.

```bash
cd core && cargo test --workspace     # Rust core
cd mod  && ./gradlew build            # builds the core for this machine, runs all tests and GameTests, makes the jar
cd mod  && ./gradlew runClient        # dev client using the freshly built core
```

`build/libs/world-engine-<version>.jar` bundles the core for the machine that built it. CI produces a jar that bundles all five platforms (Linux x64/arm64, Windows x64, macOS arm64/x64).
