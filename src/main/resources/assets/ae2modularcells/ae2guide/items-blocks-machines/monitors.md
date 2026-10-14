---
navigation:
  parent: index.md
  title: Monitors
  icon: flow_monitor
  position: 2
item_ids:
- ae2modularcells:energy_monitor
- ae2modularcells:flow_monitor
categories:
- devices
---

# Monitors

<Row>
  <BlockImage id="energy_monitor" scale="4" />
  <BlockImage id="flow_monitor" scale="4" />
</Row>

Two display-only panels. They take part in forming a structure but add neither capacity nor transfer rate, so you can place one without weakening the structure or inflating its readings.

Monitors hold no account of their own, so every structure needs at least one module: a cube of monitors alone never forms. The core is any module — the first one in layout order.

## Energy Storage Monitor

Shows the structure's stored energy as a percentage, on one centered line.

## Energy Flow Monitor

An FE icon in the middle of the face, the readout along the bottom edge: the structure's average throughput over the last second, in AE/t. With Applied Flux installed the icon is that mod's "FE" sprite; otherwise it is the one shipped here.

The face does not show the instantaneous input rate — it jumps constantly and does not fit legibly next to the average. Open the terminal if you need the live value.

## Before and after forming

<Row>
  <BlockImage id="energy_monitor" scale="4" p:formed="true" />
  <BlockImage id="flow_monitor" scale="4" p:formed="true" />
</Row>

Unformed, it is a standalone panel: the face shows its own panel and the other five sides are plain, with no edge rings.

Once formed the face switches to the base panel, colored with the three shades of AE2's default paint, and the color does not follow the charge level. The five sides switch to another base and gain edge rings from their neighbours: a side pressed against a block gets none, an exposed side gets one, and corners where two rings meet use the corner texture.

## Charge level

Five levels: 0% / 25% / 50% / 75% / 100%. The core records the level, but the face does not change with it.

A level change can reset the mining progress on the face, so the level only switches after two matching samples.

## Where the numbers come from

They come from the core, and update once a second.

A monitor is not an ME device: run ME cables to a module block, since attaching them to a monitor gets you no connection. The monitor itself stores no energy — an FE connection attached to it feeds the core instead.
