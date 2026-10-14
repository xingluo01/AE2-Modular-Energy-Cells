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

Modules that store ME energy. Three tiers, each with more capacity and more component slots than the last:

* **Basic**: 200 kAE, 1 component slot
* **Dense**: 1.6 MAE, 2 slots
* **Superdense**: 12.8 MAE, 3 slots

## Forming a structure

<GameScene zoom="3" background="transparent">
  <ImportStructure src="../assets/assemblies/module_cube_3x3x3.snbt" />
  <IsometricCamera yaw="195" pitch="30" />
</GameScene>

Place modules against each other to build a solid cube, from 3×3×3 up to 16×16×16. You can mix tiers. Every block contributes the capacity, transfer rate and input cap of its own tier, and the structure sums them.

Once formed, the cube is one energy storage. Connect it to your ME network on any face of a module block.

A lone module, or a cluster that never forms a cube, is not a structure. It still stores AE, but only through FE — your ME network can neither connect to it nor draw from it. When the cube forms, whatever the blocks already hold joins the structure. See the [Gain Algorithm](../gain-algorithm.md) for the formulas.

## Component slots

Each module carries its own slots, so the structure's total is the sum over its blocks. Insert **storage components** to raise capacity and transfer rate:

* AE2's 1k to 256k
* MEGA Cells' 1m to 256m
* AppFlux's core_1k to 256m

The terminal groups them by tier: 12 slots per tier, one component type per slot. The same type stacks in its slot and shows a count, and a full row spills into the next one. Which slot you use makes no difference — new components go to the free slots of that tier. Capacity is counted per block and summed, and filling every slot adds a count bonus. Components you insert stay with the block they sit in.

## Energy module terminal

Right-click a **formed** structure to open the terminal.

The terminal groups by tier. Each group has a title row with the tier icon, the module count and `Components installed: a/b`, followed by that tier's component rows. Right-click a title icon to select the group — quick-move then only fills that group.

The three buttons on the left:

* open this guide
* switch the terminal style (small / medium / tall / full)
* cycle the number display: letter (`12.3M`), scientific (`1.23E7`), engineering (`12.3E6`)
