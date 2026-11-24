# World Engine

A Minecraft (Java Edition 26.3, Fabric) mod that turns the game into a physically simulated universe: planets you can walk all the way around, spacecraft built from blocks that fly real orbits, and worlds that keep evolving without you. A Rust simulation core runs alongside Minecraft. The mod calls it through Java's Foreign Function & Memory API.

**North Star:** build a spacecraft, launch from a procedurally generated planet, reach orbit, fly to its moon, land, and come home, all in one continuous simulation with no loading screens.

**Status:** M0 (foundations) and all of M1, the spatial engine (M1a, M1c, M1d, M1e-1, M1e-2 and M1f), are built and green in CI on Linux, Windows and macOS, including GameTests in a headless server and, on Linux, a client GameTest under a virtual display. The M1b frame-handoff spike ran, and its design record, [ADR 0001](docs/adr/0001-frames-and-handoff.md), is accepted. Creating a world with World Type **World Engine** gives a universe level with the home planet and its moon, and you can walk or fly across its seams onto the neighbouring cube faces. The terrain carries on across each seam in an overlap band, a turned copy of the neighbouring face, and blocks placed or broken there are mirrored onto that face. The sun rises and sets with the planet's spin: local time follows longitude, each planet keeps its own day, and the sky is turned to your latitude. The manual in-game checks of M0 and M1 passed on 2026-11-24. M2, physics and ships, is planned in five phases; the first, M2a (the physics core), is next.

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
| [docs/adr/0001-frames-and-handoff.md](docs/adr/0001-frames-and-handoff.md) | ADR 0001 (accepted): frames and the surface ↔ ship handoff, from the M1b spike |
| [docs/superpowers/plans/2026-11-22-m1e1-seam-crossing.md](docs/superpowers/plans/2026-11-22-m1e1-seam-crossing.md) | Step-by-step plan for M1e-1: carrying entities across a planet's seams, done |
| [docs/superpowers/plans/2026-11-22-m1e2-overlap-bands.md](docs/superpowers/plans/2026-11-22-m1e2-overlap-bands.md) | Step-by-step plan for M1e-2: overlap bands at the seams and edit mirroring, done |
| [docs/superpowers/plans/2026-11-23-m1f-time-of-day.md](docs/superpowers/plans/2026-11-23-m1f-time-of-day.md) | Step-by-step plan for M1f: per-planet time of day and a sky turned to your latitude, done |
| [docs/superpowers/plans/2026-11-24-m2-overview.md](docs/superpowers/plans/2026-11-24-m2-overview.md) | Overview of M2, physics and ships: phases M2a–M2e, and the design decisions a Rapier probe measured |
| [docs/superpowers/plans/2026-11-24-m2a-physics-core.md](docs/superpowers/plans/2026-11-24-m2a-physics-core.md) | Step-by-step plan for M2a: the physics core (`we-physics`, its C ABI, the Java bridge and the physics thread) |

## Building

Prerequisites: Rust stable ≥ 1.85, JDK 25.

```bash
cd core && cargo test --workspace     # Rust core
cd mod  && ./gradlew build            # builds the core for this machine, runs all tests and GameTests, makes the jar
cd mod  && ./gradlew runClient        # dev client using the freshly built core
```

`build/libs/world-engine-<version>.jar` bundles the core for the machine that built it. CI produces a jar that bundles all five platforms (Linux x64/arm64, Windows x64, macOS arm64/x64).
