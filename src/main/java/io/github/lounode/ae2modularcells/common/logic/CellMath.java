package io.github.lounode.ae2modularcells.common.logic;

import io.github.lounode.ae2modularcells.common.ComponentGain;
import io.github.lounode.ae2modularcells.common.EnergyCellTier;
import io.github.lounode.ae2modularcells.common.ModularCellConstants;
import io.github.lounode.ae2modularcells.common.config.ModularCellConfig;

/**
 * 容量与速率的计算口径——两条公式只在这里实现一次。
 *
 * <pre>
 * 单模块容量   D    = (A + Σ S_i  × m_i  × k)  × (1 + b  × C_cap)
 * 总容量       T    = Σ D_j          （每块按自己那一档的 A）
 * 单模块速率   D_v  = (V + Σ Sv_i × mv_i × kv) × (1 + bv × C_tr)
 * 总速率       T_v  = min(Σ D_vj, Σ V_max_j)      （上限也是逐块之和）
 * 总输入速率   T_in = min(T_v × f_in, Σ V_in_max_j)
 * </pre>
 *
 * <p>符号：{@code S_i} 与 {@code Sv_i} 都取自组件槽里同一个 {@link ComponentGain}——一个组件可以同时贡献两个
 * 维度；{@code m_i} / {@code mv_i} 是这个组件自己的修正倍率（同档组件靠它分强弱，默认 1.0）；{@code C} 是该
 * 模块实际插了几个组件。系数分两类：{@code k} 与 {@code kv} 乘的是组件自己带来的值，属于组件常量（不进制
 * 配置）；{@code b} / {@code bv} / {@code f_in} 描述结构规模与输入口径，可配（默认值在
 * {@link ModularCellConstants}，运行时取 {@code ModularCellConfig}）。</p>
 */
public final class CellMath {

    private CellMath() {
    }

    /** 一块模块的产出：容量与速率一起算出来（两者都要逐槽扫一遍组件，只是各自数各自的）。 */
    public record ModuleGains(long capacity, long transfer) {
    }

    /**
     * 一块模块的容量与速率：{@code D = (A + Σ S_i × m_i × k) × (1 + b × C_cap)}、
     * {@code D_v = (V + Σ Sv_i × mv_i × kv) × (1 + bv × C_tr)}。
     *
     * <p><b>数量加成按维分侧计数</b>：{@code C_cap} 只数「这一块插了几个真的会提供容量的组件」，{@code C_tr}
     * 只数会提供速率的。所以插一个只给容量的组件，速率那一侧不会跟着涨——两侧互不干扰。双贡献的组件在两侧
     * 各计一次。</p>
     *
     * <p>分侧计数的代价是「插了不提供这一维的组件时，这一维不再吃数量加成」。这一点在改前算过：纯容量或纯
     * 速率的结构里 {@code C_cap} / {@code C_tr} 恰好等于总组件数，数值与分侧之前<b>逐字一致</b>，所以原有平衡
     * 不受影响；只有混插会变弱，而那正是分侧的目的。</p>
     *
     * @param slots 该模块的组件槽，下标即槽位；{@code null}、以及两个维度都为 {@code 0} 的贡献都算空槽。多于
     *              槽位数的部分忽略——存档被改坏时不至于算出一台超级电池
     */
    public static ModuleGains moduleGains(EnergyCellTier tier, ComponentGain[] slots) {
        int limit = slots == null ? 0 : Math.min(slots.length, tier.componentSlots());
        int capacityCount = 0;
        int transferCount = 0;
        double capacitySum = 0.0D;
        double transferSum = 0.0D;
        for (int i = 0; i < limit; i++) {
            var gain = slots[i];
            if (gain == null || gain.isEmpty()) {
                continue;
            }
            capacitySum += gain.capacity() * gain.capacityMultiplier();
            transferSum += gain.transfer() * gain.transferMultiplier();
            // 两个计数各数各的：倍率为 0 等于这一维被关掉，不算在内（hasXxx 已把这个口径包含进去了）。
            if (gain.hasCapacity()) {
                capacityCount++;
            }
            if (gain.hasTransfer()) {
                transferCount++;
            }
        }
        double capacity = (tier.baseCapacity() + capacitySum * ModularCellConfig.componentDiscount())
                * (1.0D + ModularCellConfig.capacityCountBonus() * capacityCount);
        double transfer = (tier.baseTransferRate() + transferSum * ModularCellConfig.transferDiscount())
                * (1.0D + ModularCellConfig.transferCountBonus() * transferCount);
        return new ModuleGains(Math.round(capacity), Math.round(transfer));
    }

    /** 总传输速率：各模块速率之和，再被「各块上限之和」收口：{@code T_v = min(Σ D_vj, Σ V_max_j)}。 */
    public static long totalTransferRate(long summedModuleRates, long summedMaxTransferRates) {
        return Math.min(Math.max(0L, summedModuleRates), Math.max(0L, summedMaxTransferRates));
    }

    /** 总输入速率：{@code T_in = min(T_v × f_in, Σ V_in_max_j)}，充能方向比放电方向更紧。 */
    public static long totalInputRate(long totalTransferRate, long summedMaxInputRates) {
        long input = Math.round(totalTransferRate * ModularCellConfig.inputRatio());
        return Math.min(input, Math.max(0L, summedMaxInputRates));
    }
}
