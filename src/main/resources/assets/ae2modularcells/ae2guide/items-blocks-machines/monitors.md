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

Two display-only panels. Placed inside a cube they count as structure parts: they take part in forming the multiblock but contribute nothing to capacity or transfer rate. Adding one therefore neither breaks a structure nor inflates its totals.

## Energy Monitor

Shows the whole structure's stored energy as a percentage, on one centered line.

## Energy Flow Monitor

An FE icon sits in the middle of the face with the readout along the bottom edge: the structure's average throughput over the last second, in AE/t. With Applied Flux installed the icon is that mod's own "FE" sprite; without it, the icon shipped here is used.

Instantaneous input rate stays off the face. It changes every tick and the face is small — two lines crowd each other. Use the terminal if you need the live number.

## Before and after forming

While unformed it is a monitor standing on its own: the face shows the monitor's own panel, and the other five sides
stay plain — no edge rings, the same choice modules make before they form. There is no structure to report on yet.

Once formed the face switches to the base panel plus three layered light textures. Those three are complementary
pieces of one panel (centre fill, four corners, outer frame) and only read as a whole panel when drawn together;
their colour comes from the three shades of AE2's default paint and **does not follow the charge level**. The five
sides switch to a different base and gain edge rings according to their neighbours: a side touching another block
gets no ring, an exposed side gets one, and corners where two rings meet get the corner texture — the same rule the
modules use once formed.

## Charge level

The charge level (0–4, i.e. 0% / 25% / 50% / 75% / 100%) is written to the block state by the core as a record only;
it does not affect the face. A step change is accepted only after two consecutive samples agree, otherwise a ratio
hovering on a boundary keeps rewriting the state — and every state write **resets the client's mining progress**.
That is what the debounce is for.

## Where the numbers come from

The core computes the charge ratio and the input average every 20 ticks and writes them into each monitor's block entity, which syncs them to the client. Monitors have no ticker and no grid node: the structure's account lives on the core alone.

A monitor stores nothing, but players will attach cables to it, so its FE capability forwards to the core. The conversion ratio and the insert-only rule live in the core alone; they are not duplicated here.
