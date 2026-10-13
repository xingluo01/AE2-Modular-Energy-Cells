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

Modular energy units store ME energy. Three tiers exist: **Basic** (200 kAE and one component slot),
**Dense** (1.6 MAE and two slots) and **Superdense** (12.8 MAE and three slots).

## Forming a structure

Place units next to each other to build a solid cube, between 3×3×3 and 16×16×16. Tiers can be
mixed — every block contributes its own tier's capacity and transfer rate, and the rate and input caps
are summed per block as well (not taken from whichever tier is highest). The whole cube then works as one
energy storage: a single account and a single channel, connectable to your ME network on any face.

A single unit, or a pile that does not form a cube, is not a structure — it still stores AE, but
that energy belongs to it alone: FE can charge it, while the ME network can neither connect to it nor
draw from it. The moment a cube forms, every unit's stored energy flows into one pool shared by the
structure. See the [Gain Algorithm](../gain-algorithm.md) for how capacity and rate are computed.

## Component slots

Every unit carries its own component slots. Insert **storage components** (AE2's 1k to 256k, MEGA
Cells' 1m to 256m, AppFlux's core_1k to 256m) to raise capacity and transfer rate. Components are
counted per block and the totals are summed over the whole structure; filling every slot also adds a
count bonus. Components are saved with the unit they sit in.

## Energy module terminal

Right-click a **formed** structure to open the terminal. It lists the units of the structure and
their component slots, and shows the structure's stored energy and input rate. Units are grouped by
tier, each group with its own title row (tier icon and unit count) — right-click a title icon to
select that group, after which quick-move only puts components into that group. The buttons on the
left open this guide, switch the terminal style (small / medium / tall / full, which decides how many
rows the panel shows), and cycle the number display between letter (`12.3M`), scientific (`1.23E7`)
and engineering (`12.3E6`) notation.
