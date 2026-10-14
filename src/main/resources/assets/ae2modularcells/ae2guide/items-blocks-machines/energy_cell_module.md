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

Modules that store ME energy. Three tiers, increasing in both capacity and slot count:

* **Basic**: 200 kAE, 1 component slot
* **Dense**: 1.6 MAE, 2 slots
* **Superdense**: 12.8 MAE, 3 slots

## Forming a structure

<GameScene zoom="3" background="transparent">
  <ImportStructure src="../assets/assemblies/module_cube_3x3x3.snbt" />
  <IsometricCamera yaw="195" pitch="30" />
</GameScene>

Placed solid into a cube the structure forms, from 3×3×3 up to 16×16×16, with tiers mixed freely. Capacity, transfer rate and input cap are each computed per block and summed: every block contributes the values of its tier.

Formed, the structure serves as a single energy storage, connectable on any face.

An unformed block or cluster is not a structure: it stores AE, but only through FE, and the ME network can neither connect to it nor draw from it. On forming, energy already held by the blocks joins the structure. See the [Gain Algorithm](../gain-algorithm.md) for the formulas.

## Component slots

Each block carries its own slots, so a structure's total slot count is the sum over its blocks. Insert **storage components** to raise capacity and transfer rate:

* AE2's 1k to 256k
* MEGA Cells' 1m to 256m
* AppFlux's core_1k to 256m

The terminal groups by tier: one row of 12 slots per tier, **one component type per slot**, with the same type counted within its slot and a full row spilling into the next. The slot chosen makes no difference; new components are distributed across the free slots of that tier's blocks. Capacity is counted per block and summed over the structure, and a full slot set adds a count bonus. Components are saved with the block they sit in.

## Energy module terminal

Right-click a **formed** structure to open the terminal.

It groups by tier: each group has a title row (tier icon, module count, `Components installed: a/b`) followed by that tier's component rows. Right-click a title icon to select the group; quick-move then only fills that group.

The three buttons on the left:

* open this guide
* switch terminal style (small / medium / tall / full; how many rows the panel shows)
* cycle the number display: letter (`12.3M`), scientific (`1.23E7`), engineering (`12.3E6`)
