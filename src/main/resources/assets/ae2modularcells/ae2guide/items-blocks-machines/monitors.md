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

Two display-only panels. Placed inside a cube they count as structure parts: they take part in forming the structure but add nothing to capacity or transfer rate, so adding one breaks no structure and inflates no reading.

A monitor is a structure part and nothing else, so it holds no account of its own. A structure therefore needs at least one module: a cube of nothing but monitors never forms. Any module can be the core, which is simply the first one in layout order, so you never have to reserve a corner for it.

## Energy Storage Monitor

Shows the whole structure's stored energy as a percentage, on one centered line.

## Energy Flow Monitor

An FE icon sits in the middle of the face, with the readout along the bottom edge: the structure's average throughput over the last second, in AE/t. With Applied Flux installed the icon is that mod's own "FE" sprite; without it, the icon shipped here is used.

Instantaneous input rate stays off the face. It changes every tick and the face is small, so a second line would only crowd it. For the live number, open the terminal.

## Before and after forming

While unformed it is a monitor standing on its own: the face shows its own panel, and the other five sides stay plain, with no edge rings. There is no structure to report on yet.

Once formed the face switches to the base panel, colored with the three shades of AE2's default paint. It does not follow the charge level. The five sides switch to a different base and gain edge rings according to their neighbours: a side touching another block gets no ring, an exposed side gets one, and corners where two rings meet get the corner texture.

## Charge level

The charge level runs in five steps (0% / 25% / 50% / 75% / 100%). The core records it, but it does not affect the face. A step change is accepted only after two consecutive samples agree, otherwise a ratio hovering on a boundary would keep refreshing, and every refresh resets the client's mining progress.

## Where the numbers come from

The core computes the charge ratio and the input average every 20 ticks and writes them into each monitor's block entity, which syncs them to the client. Monitors have no ticker and no grid node.

A monitor stores nothing either, but players will attach cables to it, so its FE capability forwards to the core.
