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

Two display-only panels. They take part in forming but add nothing to capacity or transfer rate, so placing one neither breaks a formation nor changes any reading.

Monitors hold no structural account, so a structure needs at least one module: a cube of monitors alone never forms. The core is a module, at any position — the first one in layout order.

## Energy Storage Monitor

Shows the structure's stored energy as a percentage, on one centered line.

## Energy Flow Monitor

An FE icon occupies the middle of the face, with the readout along the bottom edge: the structure's average throughput over the last second, in AE/t. The icon comes from Applied Flux's "FE" sprite when that mod is installed, and from this mod otherwise.

The instantaneous input rate is not shown: it changes every tick, and two lines do not fit legibly on the face. For the live value, open the terminal.

## Before and after forming

<Row>
  <BlockImage id="energy_monitor" scale="4" p:formed="true" />
  <BlockImage id="flow_monitor" scale="4" p:formed="true" />
</Row>

Unformed, it is a standalone panel: the face shows its own panel texture and the other five sides are plain, without edge rings. There is no structure to report on.

Formed, the face switches to the base panel, colored with the three shades of AE2's default paint and independent of the charge level. The five sides switch to another base and gain edge rings according to their neighbours: a side pressed against a block gets none, an exposed side gets one, and where two rings meet the corner texture is used.

## Charge level

Five levels (0% / 25% / 50% / 75% / 100%), recorded by the core and without effect on the face. A switch requires two consecutive samples in the new level; otherwise a ratio hovering on a boundary would refresh repeatedly, and each refresh resets the client's mining progress.

## Where the numbers come from

The core computes the charge ratio and the input average every 20 ticks, writes them into each monitor's block entity, which syncs them to the client. Monitors have no ticker and no grid node.

Monitors store no energy; an FE connection attached to one is forwarded to the core.
