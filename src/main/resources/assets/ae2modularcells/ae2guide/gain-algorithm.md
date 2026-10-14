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

Capacity and transfer rate come from two formulas. Slot contents are counted live, not cached: swapping a component, dismantling a structure or mixing tiers changes the figures immediately.

## One module, and the whole structure

A module's capacity is `D = (A + Σ S_i × m_i × k) × (1 + b × C_cap)`. `A` is the tier's base capacity; the `Σ` term sums the storage components' contributions — each component's capacity value `S_i` times its modifier `m_i`, scaled after summation by a shared discount `k`; `(1 + b × C_cap)` is the capacity-side count bonus, where `C_cap` is the number of capacity-providing components **actually inserted**, empty slots excluded.

Rate follows the same form: `D_v = (V + Σ Sv_i × mv_i × kv) × (1 + bv × C_tr)`. `V` is the base rate, `Sv_i` / `mv_i` are the rate value and modifier of each energy component (the Applied Flux `core_*` family, AE2 OMNI Cells and others), `kv` is the rate-side discount, and `C_tr` is the number of rate-providing components.

**The two sides are counted separately**: `C_cap` and `C_tr` are independent. A capacity-only component does not raise the rate-side count, and a component providing both counts once on each side. A dimension that receives no component loses its count bonus.

A structure total is summed **per block**, not a single value multiplied by the block count:

| Structure total | Expression | Meaning |
|---|---|---|
| Capacity `T` | `Σ D_j` | Sum of the modules' capacities |
| Rate `T_v` | `min(Σ D_vj, Σ V_max_j)` | Sum of the modules' rates, clamped by the sum of the per-block caps |
| Input `T_in` | `min(T_v × f_in, Σ V_in_max_j)` | Charging is tighter than discharging |

With mixed tiers, `V_max` and `V_in_max` are also summed per block: expanding a structure raises the ceiling rather than converging on a fixed value at formation.

## Parameters

| Parameter | Value | Effect |
|---|---|---|
| k | 0.2 | Storage component discount: one 256m component contributes about 53.7 MAE |
| b | 2.0 | Count bonus on the capacity side: a full module multiplies capacity by `1 + 2 × C_cap` |
| kv | 0.0001 | Energy component rate discount |
| bv | 0.1 | Count bonus on the rate side |
| f_in | 0.5 | Charging is halved: discharge runs at full speed, input at half |

Capacity multipliers when full: basic / dense / superdense reach ×3 / ×5 / ×7. `k` and `kv` are component constants and not configurable; `b` / `bv` / `f_in` are, in the config file.

## Tier base values

| Tier | A base capacity | V base rate | Slots | V_max per block | V_in_max per block |
|---|---|---|---|---|---|
| Basic | 200 kAE | 1 000 AE/t | 1 | 16 000 | 8 000 |
| Dense | 1.6 MAE | 8 000 AE/t | 2 | 128 000 | 64 000 |
| Superdense | 12.8 MAE | 64 000 AE/t | 3 | 1 024 000 | 512 000 |

## Filled to capacity

The reference build is a 16×16×16 structure with every slot holding a 256m storage component, the physical ceiling of one structure: superdense takes 4096 × 3 = 12288 components, dense takes 4096 × 2 = 8192.

| Build | Component value | Per module | Structure total | Versus components |
|---|---|---|---|---|
| Dense 16³, 2 × 256m each | 2.20 TAE | 544.9 MAE | 2.23 TAE | 1.01× |
| Superdense 16³, 3 × 256m each | 3.30 TAE | 1.217 GAE | 4.98 TAE | 1.51× |

The dense row totals about the raw component value; superdense exceeds it, having a third slot and a larger count bonus.

On the same superdense build, transfer is 769.6 MAE/t and charging 384.8 MAE/t after the halving. Discharge is unaffected.
