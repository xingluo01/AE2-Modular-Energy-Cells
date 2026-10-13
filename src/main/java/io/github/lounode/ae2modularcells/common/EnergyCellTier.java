package io.github.lounode.ae2modularcells.common;

import org.jetbrains.annotations.Nullable;

/**
 * 模块方块的三个等级。
 *
 * <p>公式要用的五个数值（单模块基础容量、基础传输速率、组件槽位数、每块速率上限、每块输入速率上限）全部集中
 * 在这里，平衡调整只动本文件；公式在 {@link io.github.lounode.ae2modularcells.common.logic.CellMath}，
 * 折扣与数量加成系数在 {@link ModularCellConstants}。</p>
 *
 * <p>单位：容量与速率的数值都是 AE 与 AE/t（AE2 的能量单位），不是「千」——{@code 200_000} 就是 200 kAE。</p>
 */
public enum EnergyCellTier {

    /** 模块化基础能量单元：200 kAE / 块，1 个组件槽位。 */
    BASIC("basic", 200_000L, 1_000L, 1, 16_000L, 8_000L),

    /** 模块化致密能量单元：1.6 MAE / 块，2 个组件槽位。 */
    DENSE("dense", 1_600_000L, 8_000L, 2, 128_000L, 64_000L),

    /** 模块化超密能量单元：12.8 MAE / 块，3 个组件槽位。 */
    SUPERDENSE("superdense", 12_800_000L, 64_000L, 3, 1_024_000L, 512_000L);

    private final String id;
    private final long baseCapacity;
    private final long baseTransferRate;
    private final int componentSlots;
    private final long maxTransferRate;
    private final long maxInputRate;

    EnergyCellTier(String id, long baseCapacity, long baseTransferRate, int componentSlots, long maxTransferRate,
            long maxInputRate) {
        this.id = id;
        this.baseCapacity = baseCapacity;
        this.baseTransferRate = baseTransferRate;
        this.componentSlots = componentSlots;
        this.maxTransferRate = maxTransferRate;
        this.maxInputRate = maxInputRate;
    }

    /** 注册名与配置名用的短 id（{@code basic} / {@code dense} / {@code superdense}）。 */
    public String id() {
        return id;
    }

    /** 单模块基础容量 A（AE）：只作容量公式里的加数项，公式见 {@code CellMath.moduleGains}。 */
    public long baseCapacity() {
        return baseCapacity;
    }

    /** 单模块基础传输速率 V（AE/t）。 */
    public long baseTransferRate() {
        return baseTransferRate;
    }

    /** 每个模块提供的组件槽位数（1 / 2 / 3），也是容量与速率公式里 `C_cap` / `C_tr` 的上界。 */
    public int componentSlots() {
        return componentSlots;
    }

    /** 每块提供的速率上限 V_max（AE/t）；整段上限是逐块之和：{@code T_v = min(Σ D_vj, Σ V_max_j)}。 */
    public long maxTransferRate() {
        return maxTransferRate;
    }

    /**
     * 每块提供的输入速率上限 V_in_max（AE/t）；整段上限同样是逐块之和。
     *
     * <p>当前三档都恰好是 {@link #maxTransferRate()} 的一半，而 {@code ModularCellConstants.INPUT_RATIO}
     * 也是 0.5，所以两道约束重合——把这个上限改小、或把 f_in 改成 1.0，它才会独立生效。</p>
     */
    public long maxInputRate() {
        return maxInputRate;
    }

    /**
     * 按短 id 找等级；找不到返回 {@code null}。
     *
     * <p>存档里出现陌生等级（模组版本回退、存档来自别的分支）时用它兜底——调用方要按「这一段不成立」
     * 处理，不要抛异常：那会把一个方块的问题放大成整段结构的加载失败。</p>
     */
    @Nullable
    public static EnergyCellTier byId(@Nullable String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        for (var tier : values()) {
            if (tier.id.equals(id)) {
                return tier;
            }
        }
        return null;
    }
}
