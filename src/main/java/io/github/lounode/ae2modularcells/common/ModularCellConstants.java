package io.github.lounode.ae2modularcells.common;

/**
 * 多方块的尺寸、上限与两条公式的系数。
 *
 * <p>这些数字同时被结构检测、容量汇总与菜单展示引用：{@link #MAX_BLOCKS} 是任何一次结构扫描的硬上界，
 * {@link #COMPONENT_DISCOUNT} 与 {@link #TRANSFER_DISCOUNT} 是组件折扣，{@link #CAPACITY_COUNT_BONUS}
 * 与 {@link #TRANSFER_COUNT_BONUS} 是组件数量加成，{@link #INPUT_RATIO} 是充能侧的折半系数。</p>
 *
 * <p>分头写死这些常数就会在某一处悄悄脱节——「16³ = 4096」不是可以随手重算的数，各处的数组、位图与
 * 迭代上限都要照它取。</p>
 */
public final class ModularCellConstants {

    /** 多方块最小边长（3×3×3 = 27 个方块）。 */
    public static final int MIN_SIDE = 3;

    /** 多方块最大边长（16×16×16）。 */
    public static final int MAX_SIDE = 16;

    /**
     * 单段结构的方块数上限 = {@link #MAX_SIDE}³。
     *
     * <p>上界针对的是「结构边长」。未成型的单个方块在 GUI 里显示为边长 1，那是展示口径，不在
     * {@link #isValidSide} 的合法区间里——别拿它去当合法结构判断。</p>
     */
    public static final int MAX_BLOCKS = MAX_SIDE * MAX_SIDE * MAX_SIDE;

    // ---- 容量公式：D = (A + Σ S_i × k) × (1 + b × C) ----

    /**
     * 存储组件容量折扣 k：每个组件往模块容量里贡献 {@code S_i × k}。
     *
     * <p>取值口径是「对得起堆叠」：一整座 16³ 最多塞进 {@code 4096 × 3 = 12288} 个 256m 组件，组件总值
     * 3.30 TAE。取 k = 0.2、b = 2.0 时——超密 16³ 落在 4.98 TAE（组件堆叠的 1.51 倍）、致密 16³
     * （2 槽、8192 个组件、堆叠 2.20 TAE）落在 2.23 TAE（1.01 倍）。推导见指南的增益算法页。</p>
     *
     * <p>一个 256m 组件（268 435 456 AE）在 k = 0.2 下折算 53 687 091 AE，约等于 268 台基础模块。</p>
     */
    public static final double COMPONENT_DISCOUNT = 0.2D;

    /** 组件容量折扣的配置下限。 */
    public static final double COMPONENT_DISCOUNT_MIN = 0.001D;

    /**
     * 组件容量折扣的候选上限。
     *
     * <p>目前没有配置系统读这三个区间常量——它们是给「做成可配置项」预留的边界。真接上配置时记得让上限
     * 盖得住 {@link #COMPONENT_DISCOUNT}，否则默认值会被夹回去。</p>
     */
    public static final double COMPONENT_DISCOUNT_MAX = 0.5D;

    /** 组件数量加成 b：装满 C 个组件时，容量乘 {@code (1 + b × C)}，故基础/致密/超密最多 ×3 / ×5 / ×7。 */
    public static final double CAPACITY_COUNT_BONUS = 2.0D;

    // ---- 速率公式：D_v = (V + Σ Sv_i × kv) × (1 + bv × C) ----

    /** 传输组件速率折扣 kv：每个组件往模块速率里贡献 {@code Sv_i × kv}（容量折扣的 1/50）。 */
    public static final double TRANSFER_DISCOUNT = 0.0001D;

    /** 速率侧的组件数量加成 bv：装满 C 个组件时，速率乘 {@code (1 + bv × C)}。 */
    public static final double TRANSFER_COUNT_BONUS = 0.1D;

    /**
     * 输入比例系数 f_in：充能速率 = 总速率 × f_in，再被等级的 V_in_max 夹住。
     *
     * <p>取 0.5 而不是 1.0：放电（供应网络）保持全速，充能侧折半，避免一座超大结构在几秒内被灌满
     * 而失去「容量」这件事的意义。要做成可配置项时这两个值就是候选项。</p>
     *
     * <p>注意当前三档的 {@code V_in_max} 都恰好是 {@code V_max} 的一半，于是 f_in = 0.5 时两道约束重合；
     * 只有把 f_in 改成 1.0（或调低 V_in_max）时，总输入速率那道 min 才会独立生效。</p>
     */
    public static final double INPUT_RATIO = 0.5D;

    private ModularCellConstants() {
    }

    /** 边长是否在合法区间内。 */
    public static boolean isValidSide(int side) {
        return side >= MIN_SIDE && side <= MAX_SIDE;
    }

    /** 单个模块最多能插几个组件——取三个等级里的最大值，用作菜单与存档读取时的防御上界。 */
    public static int maxComponentSlots() {
        int max = 0;
        for (var tier : EnergyCellTier.values()) {
            max = Math.max(max, tier.componentSlots());
        }
        return max;
    }
}
