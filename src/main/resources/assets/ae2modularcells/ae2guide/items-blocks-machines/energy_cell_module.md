---
navigation:
  parent: index.md
  title: Modular Energy Unit
  icon: basic_energy_cell_module
  position: 1
item_ids:
- ae2modularcells:basic_energy_cell_module
- ae2modularcells:dense_energy_cell_module
- ae2modularcells:superdense_energy_cell_module
categories:
- devices
---

# Modular Energy Unit

<Row gap="20">
  <ItemImage id="basic_energy_cell_module" scale="4" />
  <ItemImage id="dense_energy_cell_module" scale="4" />
  <ItemImage id="superdense_energy_cell_module" scale="4" />
</Row>

Modular energy units store ME energy. There are three tiers:

* **Basic**: 200 kAE, 1 component slot
* **Dense**: 1.6 MAE, 2 slots
* **Superdense**: 12.8 MAE, 3 slots

## Forming a structure

Place modules next to each other to build a solid cube, from 3×3×3 up to 16×16×16. Tiers can be mixed: every block contributes its own tier's capacity and transfer rate, and the rate and input caps are summed per block as well, not taken from a single tier.

Once formed, the cube works as one energy storage: one account, one channel, connectable on any face.

A single module, or a pile that never forms a cube, is not a structure. It still stores AE, but that energy belongs to it alone: FE can charge it, while the ME network can neither connect to it nor draw from it. The moment a cube forms, every module's stored energy flows into one pool. See the [Gain Algorithm](../gain-algorithm.md) for the numbers.

## Component slots

Every module carries its own component slots, so a structure's total slot count is the sum over its blocks. Insert **storage components** to raise capacity and transfer rate:

* AE2's 1k to 256k
* MEGA Cells' 1m to 256m
* AppFlux's core_1k to 256m

The terminal does not show them per block: each tier shares one row of 12 slots, **one component type per slot** (the same type stacks, and the slot shows the count; a full row spills into another row below). Which slot you drop it into does not matter - the system spreads new components across the blocks of that tier itself. Components are counted per block and summed over the whole structure, and filling every slot adds a count bonus. They are saved with the module they sit in.

## Energy module terminal

Right-click a **formed** structure to open the terminal.

It groups by tier: each group has a title row (tier icon, module count, and `components installed: a/b`) followed by that tier's component rows. Right-click a title icon to select that group, after which quick-move only puts components there.

The three buttons on the left:

* open this guide
* switch the terminal style (small / medium / tall / full — how many rows the panel shows)
* cycle the number display between letter (`12.3M`), scientific (`1.23E7`) and engineering (`12.3E6`)
