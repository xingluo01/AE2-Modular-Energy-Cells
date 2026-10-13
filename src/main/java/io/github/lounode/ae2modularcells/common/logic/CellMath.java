package io.github.lounode.ae2modularcells.common.logic;

import io.github.lounode.ae2modularcells.common.ComponentGain;
import io.github.lounode.ae2modularcells.common.EnergyCellTier;
import io.github.lounode.ae2modularcells.common.ModularCellConstants;
import io.github.lounode.ae2modularcells.common.config.ModularCellConfig;

/**
 * 容量与速率的计算口径——两条公式只在这里实现一次。
 *
 * <pre>
 * 单模块容量   D    = (A + Σ S_i  × m_i  × k)  × (1 + b  × C)
 * 总容量       T    = Σ D_j          （每块按自己那一档的 A）
 * 单模块速率   D_v  = (V + Σ Sv_i × mv_i × kv) × (1 + bv × C)
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

    /** 一块模块的产出：容量与速率一起算出来，因为两者共用同一个 {@code C}。 */
    public record ModuleGains(long capacity, long transfer) {
    }

    /**
     * 一块模块的容量与速率：{@code D = (A + Σ S_i × m_i × k) × (1 + b × C)}、
     * {@code D_v = (V + Σ Sv_i × mv_i × kv) × (1 + bv × C)}。
     *
     * <p><b>{@code C} 数的是「这一块实际插了几个组件」</b>，不是「有几个给了容量 / 有几个给了速率」：一个组件
     * 可以同时贡献两个维度，而只给一维的组件同样占着一个槽。早先两侧各自按「数组里非零的个数」数一次，那时容量
     * 组件与能量存储组件互斥、两种数法恰好等价；组件能双贡献之后，那样数会漏掉「只给速率」的组件。</p>
     *
     * @param slots 该模块的组件槽，下标即槽位；{@code null}、以及两个维度都为 {@code 0} 的贡献都算空槽。多于
     *              槽位数的部分忽略——存档被改坏时不至于算出一台超级电池
     */
    public static ModuleGains moduleGains(EnergyCellTier tier, ComponentGain[] slots) {
        int limit = slots == null ? 0 : Math.min(slots.length, tier.componentSlots());
        int count = 0;
        double capacitySum = 0.0D;
        double transferSum = 0.0D;
        for (int i = 0; i < limit; i++) {
            var gain = slots[i];
            if (gain == null || gain.isEmpty()) {
                continue;
            }
            capacitySum += gain.capacity() * gain.capacityMultiplier();
            transferSum += gain.transfer() * gain.transferMultiplier();
            count++;
        }
        double capacity = (tier.baseCapacity() + capacitySum * ModularCellConfig.componentDiscount())
                * (1.0D + ModularCellConfig.capacityCountBonus() * count);
        double transfer = (tier.baseTransferRate() + transferSum * ModularCellConfig.transferDiscount())
                * (1.0D + ModularCellConfig.transferCountBonus() * count);
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
