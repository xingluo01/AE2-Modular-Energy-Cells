package io.github.lounode.ae2modularcells.common.logic;

import io.github.lounode.ae2modularcells.common.EnergyCellTier;

/**
 * 一段结构（或一块独立模块）的能量账目快照。
 *
 * <p>只装结果，不算公式——四个数全部由 {@link CellMath} 算好后传进来。菜单展示、后续的能量池接入都从
 * 这里取数，免得同一个结构在两处各算一遍、算出两个值。</p>
 *
 * <p><b>混等级后「等级」是两个不同的东西</b>：{@link #tier} 是这段里最高的那一档，只用来取两个上限
 * （{@code V_max} / {@code V_in_max}）；{@link #tierMask} 记录出现了哪几档，用于界面区分「一段同等级」
 * 与「混拼」。每块自己的容量与速率按它自己的档算，不经过这里的 tier——那两个数在传进来之前就已经求完和了。</p>
 *
 * @param tier              整段的代表等级 = 出现过的最高档（决定 {@code V_max} / {@code V_in_max}）
 * @param tierMask          出现过的等级位掩码，第 {@code ordinal()} 位为 1 即出现过
 * @param side              边长；未成型的单块按展示口径记 1
 * @param totalCapacity     总容量 T = Σ D_j（每块按自己的档）
 * @param totalTransferRate 总速率 T_v = min(Σ D_vj, V_max)
 * @param totalInputRate    总输入速率 T_in = min(T_v × f_in, V_in_max)
 *
 * <p><b>这个记录必须保持「结构级稳定」</b>：终端把行表（{@code rowLayout}）当缓存键、只在它变化时重填；
 * 谁要是在这里加一个随存量/实时速率变动的字段，行表就会逐 tick 变化，重填与重同步（{@code setRowOffset}）
 * 随之退化并每帧发包。实时量走另外的同步字段（如输入速率）。</p>
 */
public record StructureStats(EnergyCellTier tier, int tierMask, int side, long totalCapacity,
        long totalTransferRate, long totalInputRate) {

    /** 结构内的模块数（边长³）。 */
    public int moduleCount() {
        return side * side * side;
    }

    /** 这一段里出现过几种等级；1 表示整段同等级，>1 表示混拼。 */
    public int tierCount() {
        return Integer.bitCount(tierMask);
    }

    /** 整段是不是混拼了多档模块。 */
    public boolean isMixedTiers() {
        return tierCount() > 1;
    }

    /** 按容量与速率两项收口总输入速率，避免每个调用方各写一次 min。 */
    public static StructureStats of(EnergyCellTier tier, int tierMask, int side, long totalCapacity,
            long totalTransferRate) {
        return new StructureStats(tier, tierMask, side, totalCapacity, totalTransferRate,
                CellMath.totalInputRate(tier, totalTransferRate));
    }

    /**
     * 单块（未成型的独立元件）的账目：边长记 1，容量与速率按这一块自己的组件算。
     *
     * @param componentCapacities 该块槽里已插入组件的容量值，见 {@link CellMath}
     */
    public static StructureStats single(EnergyCellTier tier, long[] storageValues, long[] energyValues) {
        return of(tier, tierBit(tier), 1,
                CellMath.moduleCapacity(tier, storageValues),
                CellMath.moduleTransferRate(tier, energyValues));
    }

    /** 某一档对应的位掩码位。 */
    public static int tierBit(EnergyCellTier tier) {
        return 1 << tier.ordinal();
    }
}
