---
navigation:
  parent: index.md
  title: Gain Algorithm
  icon: superdense_energy_cell_module
  position: 3
item_ids:
- ae2modularcells:basic_energy_cell_module
- ae2modularcells:dense_energy_cell_module
- ae2modularcells:superdense_energy_cell_module
categories:
- devices
---

# Gain Algorithm

How much a module stores and how fast it moves energy are both worked out from two formulas. Whatever sits in the component slots is fed straight into them, so nothing goes stale: swap a component, tear a structure down or mix tiers, and the numbers follow immediately.

## The two formulas

```
module capacity   D    = (A + Σ S_i × k)  × (1 + b  × C)
module rate       D_v  = (V + Σ Sv_i × kv) × (1 + bv × C)

structure total   T    = Σ D_j
structure rate    T_v  = min(Σ D_vj, Σ V_max_j)
structure input   T_in = min(T_v × f_in, Σ V_in_max_j)
```

`A` and `V` are the base capacity and base rate of that module's own tier. `S_i` is the capacity value of the i-th storage component, `Sv_i` the value of the i-th energy storage component (Applied Flux `core_*`): capacity reads the first, rate reads the second, and the two component families drive one formula each. `C` is how many components are **actually inserted**; empty slots do not count.

Structure totals are summed **per module**, not "one module times the block count": each module carries its own components. `V_max` and `V_in_max` are summed the same way, so expanding a structure really does make it faster instead of hitting a fixed ceiling the moment it forms.

## Parameters

| Parameter | Value | What it does |
|---|---|---|
| k | 0.2 | Storage component discount: one 256m component contributes 53 687 091 AE |
| b | 2.0 | Count bonus: a full module multiplies capacity by `1 + 2C` |
| kv | 0.0001 | Energy storage component rate discount |
| bv | 0.1 | Count bonus on the rate side |
| f_in | 0.5 | Charging is halved: discharge runs at full speed, input at half |

The count bonus is what makes filling every slot worth it: basic / dense / superdense reach ×3 / ×5 / ×7 when full.

## Tier base values

| Tier | A base capacity | V base rate | Slots | V_max per block | V_in_max per block |
|---|---|---|---|---|---|
| Basic | 200 kAE | 1 000 AE/t | 1 | 16 000 | 8 000 |
| Dense | 1.6 MAE | 8 000 AE/t | 2 | 128 000 | 64 000 |
| Superdense | 12.8 MAE | 64 000 AE/t | 3 | 1 024 000 | 512 000 |

## Filled to the brim

The reference build is "16×16×16, every slot holding a 256m storage component", which is also the physical ceiling of one structure: superdense holds 4096 × 3 = 12288 components, dense holds 4096 × 2 = 8192.

| Build | Component value | Per module | Structure total | Versus components |
|---|---|---|---|---|
| Dense 16³, 2 × 256m each | 2.20 TAE | 544.9 MAE | 2.23 TAE | 1.01× |
| Superdense 16³, 3 × 256m each | 3.30 TAE | 1.217 GAE | 4.98 TAE | 1.51× |

The dense row is "what you stack in is what you get"; superdense runs above the raw component value because of its third slot and the larger count bonus.

On the same superdense build, transfer is 769.6 MAE/t and charging 384.8 MAE/t after the halving: filling 4.98 TAE takes roughly 11 minutes. Discharge is not affected by that halving.

## One thing to remember

**Unformed modules are not on the network.** A lone block, or a pile that never forms a cube, still stores AE, but that energy belongs to it alone: FE can charge it, while the ME network can neither connect to it nor pull from it. The moment a cube forms, every module's stored energy flows into a single pool, and from then on the structure shares one account.
