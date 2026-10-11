package io.github.lounode.ae2modularcells.common.logic;

import io.github.lounode.ae2modularcells.common.EnergyCellTier;
import io.github.lounode.ae2modularcells.common.ModularCellConstants;

/**
 * 容量与速率的计算口径——两条公式只在这里实现一次。
 *
 * <pre>
 * 单模块容量   D    = (A + Σ S_i × k)  × (1 + b  × C)
 * 总容量       T    = Σ D_j          （每块按自己那一档的 A）
 * 单模块速率   D_v  = (V + Σ Sv_i × kv) × (1 + bv × C)
 * 总速率       T_v  = min(Σ D_vj, V_max)
 * 总输入速率   T_in = min(T_v × f_in, V_in_max)
 * </pre>
 *
 * <p>符号：{@code A} / {@code V} 是等级的基础容量与基础速率（{@link EnergyCellTier}），{@code S_i} 是
 * 第 i 个组件的容量值（{@link io.github.lounode.ae2modularcells.common.StorageComponentTier}），
 * {@code C} 是该模块<b>实际插了</b>几个组件，{@code k} / {@code kv} / {@code b} / {@code bv} /
 * {@code f_in} 在 {@link ModularCellConstants}，{@code V_max} / {@code V_in_max} 按等级取。</p>
 *
 * <p><b>入参契约</b>：{@code componentCapacities} 只放<b>已插入组件</b>的容量值，空槽不要补 0——不过为了
 * 不把「空槽补 0」这种写法变成陷阱，这里对 {@code ≤ 0} 的值一律按「没有组件」处理：它既不计入 C，也不
 * 计入加成。一个把空槽写成 0 的定长数组因此不会白拿数量加成。</p>
 *
 * <p>为什么总额是<b>逐块求和</b>而不是 {@code D × N}：需求要求每块独立管理组件槽，N 块里每一块的 C 与
 * Σ S_i 都可能不同。配置相同时逐块求和与 {@code D × N} 的结果一模一样，所以这里不引入第二种算法。</p>
 *
 * <p>这个类不碰世界、不碰菜单、不分配与方块数同规模的临时对象——结构汇总与菜单都直接调它。</p>
 */
public final class CellMath {

    private CellMath() {
    }

    /**
     * 单模块容量：{@code D = (A + Σ S_i × k) × (1 + b × C)}。
     *
     * @param tier                模块等级
     * @param componentCapacities 该模块槽里<b>已插入组件</b>的容量值，顺序无关；{@code ≤ 0} 的项按空槽
     *                            处理，多于槽位数的部分忽略（存档被改坏时不至于算出一台超级电池）
     */
    public static long moduleCapacity(EnergyCellTier tier, long[] componentCapacities) {
        var totals = sumComponents(tier, componentCapacities, ModularCellConstants.COMPONENT_DISCOUNT);
        double capacity = (tier.baseCapacity() + totals.bonus())
                * (1.0D + ModularCellConstants.CAPACITY_COUNT_BONUS * totals.count());
        return Math.round(capacity);
    }

    /**
     * 单模块传输速率：{@code D_v = (V + Σ Sv_i × kv) × (1 + bv × C)}。
     *
     * <p>入参是**能量存储组件**的值（见 {@code StorageComponents#energyOf}），不是存储组件的值：两类组件各管
     * 一条公式，容量侧看存储组件、速率侧看能量存储组件。</p>
     *
     * @param energyValues 该模块槽里能量存储组件（AppFlux core_*）的值，顺序无关；{@code ≤ 0} 按空槽处理
     */
    public static long moduleTransferRate(EnergyCellTier tier, long[] componentCapacities) {
        var totals = sumComponents(tier, componentCapacities, ModularCellConstants.TRANSFER_DISCOUNT);
        double rate = (tier.baseTransferRate() + totals.bonus())
                * (1.0D + ModularCellConstants.TRANSFER_COUNT_BONUS * totals.count());
        return Math.round(rate);
    }

    /**
     * 总传输速率：各模块速率之和，再被等级的 {@code V_max} 夹住。
     *
     * <p>「边累加边收口」版：逐块求和时不必先攒一个与方块数同规模的数组。公式只在本类里写一次。</p>
     *
     * <p>混等级时段里的每块按自己的档算过自己的 {@code D_v}，求和后只能拿一个上限来夹——取「出现过的最高档」
     * 的 {@code V_max}，理由与口径见 {@link StructureStats#tier}。</p>
     */
    public static long totalTransferRate(EnergyCellTier tier, long summedModuleRates) {
        return Math.min(Math.max(0L, summedModuleRates), tier.maxTransferRate());
    }

    /** 总输入速率：{@code min(T_v × f_in, V_in_max)}，充能方向比放电方向更紧。 */
    public static long totalInputRate(EnergyCellTier tier, long totalTransferRate) {
        long input = Math.round(totalTransferRate * ModularCellConstants.INPUT_RATIO);
        return Math.min(input, tier.maxInputRate());
    }

    /**
     * 把一组组件折算成「算几个 + 折扣后加多少」。
     *
     * <p>{@code ≤ 0} 的项直接跳过：空槽（0）与被改坏的负值都不该拿到 {@code (1 + b × C)} 里的那个 C——
     * 否则一个「按槽位数建定长数组、空槽补 0」的调用方会让空方块也吃满数量加成。</p>
     */
    private static ComponentTotals sumComponents(EnergyCellTier tier, long[] componentCapacities, double discount) {
        if (componentCapacities == null) {
            return new ComponentTotals(0, 0.0D);
        }
        int limit = Math.min(componentCapacities.length, tier.componentSlots());
        int count = 0;
        double bonus = 0.0D;
        for (int i = 0; i < limit; i++) {
            long value = componentCapacities[i];
            if (value <= 0L) {
                continue;
            }
            bonus += value * discount;
            count++;
        }
        return new ComponentTotals(count, bonus);
    }

    /** {@link #sumComponents} 的结果：参与计算的组件数与折扣后的加成总和。 */
    private record ComponentTotals(int count, double bonus) {
    }
}
