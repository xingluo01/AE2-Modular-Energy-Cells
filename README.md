# AE2 Modular Energy Units

An add-on for [Applied Energistics 2](https://github.com/AppliedEnergistics/Applied-Energistics-2) that adds **modular energy cell blocks**. Snap them together into a solid cube and the whole cube joins the ME network as a single energy storage unit - up to 16×16×16, or start at 3×3×3 and keep expanding.

> **Status: in development, playable.** The blocks, block entities, structure detection, the module management terminal and the storage-component slots are implemented, together with the FE input face and in-game tooltips. Remaining work and the implementation order live in [`docs/TODO.md`](docs/TODO.md).

- **Loader / MC**: NeoForge 1.21.1
- **NeoForge**: 21.1.241 up to 21.2.0
- **AE2**: 19 or newer (built against 19.2.18)
- **Java**: 21

## Features

The two cap columns are **per module**: the whole structure's cap is the sum over its modules (`T_v = min(Σ D_vj, Σ V_max_j)`, `T_in = min(T_v × f_in, Σ V_in_max_j)`), so expanding the cube or upgrading tiers really does raise the throughput. Input is capped per tick, and FE can not bypass it.

### Three module tiers

| Module | Base capacity A | Component slots | Base rate V | Rate cap per module | Input cap per module |
| --- | ---: | ---: | ---: | ---: | ---: |
| Modular Basic Energy Unit | 200 kAE | 1 | 1 kAE/t | 16 kAE/t | 8 kAE/t |
| Modular Dense Energy Unit | 1.6 MAE | 2 | 8 kAE/t | 128 kAE/t | 64 kAE/t |
| Modular Superdense Energy Unit | 12.8 MAE | 3 | 64 kAE/t | 1024 kAE/t | 512 kAE/t |

### One cube, one battery

- A solid cube of modules (**tiers can be mixed**), from 3×3×3 up to 16×16×16.
- Once formed, the cube is a single energy storage on the ME network: one node, one shared energy pool, cables from any face.
- Capacity is summed per block: `D = (A + Σ S_i × k) × (1 + b × C)`, so the cube holds `T = Σ D`.
- Transfer rate is summed per block too: `D_v = (V + Σ Sv_i × kv) × (1 + bv × C)`, and the cap is summed as well: `T_v = min(Σ D_v, Σ V_max_j)` — expanding the cube really does raise throughput. Charging is halved and capped by the summed input cap: `T_in = min(T_v × f_in, Σ V_in_max_j)`. `T_in` is enforced on the FE / external path (per tick); the AE grid injects through AE2's own `final` `injectAEPower`, which a subtype cannot throttle.
- Storage components do both jobs at different discounts: `k = 0.005` for capacity, `kv = 0.0001` for rate; filling slots multiplies further (`b = 0.5` capacity, `bv = 0.1` rate).
- Break any block and the structure comes apart: the GUI closes, that block's components drop, and the remaining blocks fall back to standalone modules.
- Unformed (or standing alone), a single block still works as an energy cell at its own capacity.

### Component slots

Every block carries its own component slots - 1, 2 or 3 depending on the tier. Slots take regular storage components: AE2's `1k` through `256k` storage components, MEGA Cells' `cell_component_1m` through `cell_component_256m`, or AppFlux's `core_1k` through `core_256m` ME Energy Storage Components - whichever of those mods are installed. Right-click any block to open **that block's** GUI: it manages that block's slots and, at the same time, shows the whole structure's totals (capacity, stored energy, transfer rate, tier and size).

## Building

```bash
./gradlew compileJava compileClientJava   # compile main + client after edits
./gradlew build                           # full build
./gradlew runClient                       # dev client (JEI by default)
./gradlew runServer                       # dev server
./gradlew runData                         # data generation into src/generated/resources
```

On Windows the same commands ship as `run_client.bat`, `run_client_emi.bat`, `run_server.bat` and `run_data.bat` in the repo root; each adds `--no-configuration-cache` so a stale cache cannot launch the game with an old mod classpath.

## License and credits

- Licensed under **LGPL-3.0** - see [`LICENSE`](LICENSE). The license text is bundled inside the jar (`META-INF/LICENSE`).
- Built on top of [Applied Energistics 2](https://github.com/AppliedEnergistics/Applied-Energistics-2) (LGPL-3.0) and [GuideME](https://github.com/AppliedEnergistics/GuideME); neither is bundled here.
- Code: the connected-border rendering in `client/render/ConnectedModuleBakedModel.java` is adapted from AE2's `appeng.client.render.crafting.CraftingCubeBakedModel` (LGPL-3.0) so that stacked modules edge exactly like AE2's crafting cubes.
- Artwork: any placeholder texture borrowed from AE2 or another mod is listed below with its source before release. Final textures are made for this project.
  - (no borrowed artwork yet)

## Documentation

| File | What it covers |
| --- | --- |
| [`AGENTS.md`](AGENTS.md) | Entry point for AI agents and newcomers: documentation map and hard rules |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | How the code is organized, where things go, what to touch |
| [`docs/TODO.md`](docs/TODO.md) | Implementation order, balance table and open questions |
| [`CHANGELOG.md`](CHANGELOG.md) | Player-visible changes (writing style: `docs/CHANGELOG_STYLE.md`) |
