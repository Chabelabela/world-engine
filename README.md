# World Engine

A Minecraft (Java Edition 26.3, Fabric) mod that turns the game into a physically simulated universe: planets you can walk all the way around, spacecraft built from blocks that fly real orbits, and worlds that keep evolving without you. A Rust simulation core runs alongside Minecraft. The mod calls it through Java's Foreign Function & Memory API.

**North Star:** build a spacecraft, launch from a procedurally generated planet, reach orbit, fly to its moon, land, and come home, all in one continuous simulation with no loading screens.

**Status:** milestone 0 (foundations) is built and green in CI on Linux, Windows and macOS. The remaining M0 check is a manual in-game smoke test (`/worldengine bodies`). M1 (spatial engine) is next.

| Document | What it is |
|---|---|
| [docs/VISION.md](docs/VISION.md) | The original concept |
| [docs/ROADMAP.md](docs/ROADMAP.md) | The master plan: architecture, the spatial model, milestones M0–M10, risks, open decisions |
| [docs/superpowers/plans/2026-11-20-m0-foundations.md](docs/superpowers/plans/2026-11-20-m0-foundations.md) | Step-by-step implementation plan for milestone 0 (Rust core + Fabric mod + CI) |

## Building

Prerequisites: Rust stable ≥ 1.85, JDK 25.

```bash
cd core && cargo test --workspace     # Rust core
cd mod  && ./gradlew build            # builds the core for this machine, runs all tests, makes the jar
cd mod  && ./gradlew runClient        # dev client using the freshly built core
```

`build/libs/world-engine-<version>.jar` bundles the core for the machine that built it. CI produces a jar that bundles all five platforms (Linux x64/arm64, Windows x64, macOS arm64/x64).
