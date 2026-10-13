package io.github.lounode.ae2modularcells.common;

/**
 * 一个存储组件为增益算法提供的贡献：两个维度各有一个值和一个修正倍率。
 *
 * <p>公式里的位置：{@code D = (A + Σ S_i × m_i × k) × (1 + b × C)}，其中 {@code S_i} 是容量值、
 * {@code m_i} 是这个组件自己的容量倍率（速率侧同理，乘 {@code kv} 与 {@code mv_i}）。倍率让同一档的组件能有
 * 强弱之分——例如 OMNI Cells 的三组组件值完全一样，靠倍率拉开。</p>
 *
 * <p><b>两个维度可以同时大于 0</b>：一个物品可以两样都给，所以贡献是个四元组。{@code 0} 表示这一维不参与。</p>
 *
 * <p>数值口径与两条公式一致：{@code capacity} 是组件自己的容量值（AE2 口径的字节），{@code transfer}
 * 是速率值。</p>
 *
 * @param capacity           容量值（容量侧公式的 {@code S_i}）；{@code 0} 表示不参与容量
 * @param transfer           速率值（速率侧公式的 {@code Sv_i}）；{@code 0} 表示不参与速率
 * @param capacityMultiplier 这个组件自己的容量修正倍率 {@code m_i}；{@code 1.0} 表示不修正
 * @param transferMultiplier 这个组件自己的速率修正倍率 {@code mv_i}；{@code 1.0} 表示不修正
 */
public record ComponentGain(long capacity, long transfer, double capacityMultiplier, double transferMultiplier) {

    /** 不带修正的贡献（两个倍率都是 {@code 1.0}）。 */
    public static ComponentGain of(long capacity, long transfer) {
        return new ComponentGain(capacity, transfer, 1.0D, 1.0D);
    }

    public ComponentGain {
        // 负值一律当 0：数值来自配置，坏数据只应「不参与计算」，不该把汇总算歪。
        capacity = Math.max(0L, capacity);
        transfer = Math.max(0L, transfer);
        capacityMultiplier = clampMultiplier(capacityMultiplier);
        transferMultiplier = clampMultiplier(transferMultiplier);
    }

    /**
     * 倍率的兜底：负值当 0，非有限值（NaN / Infinity）当 1.0。
     *
     * <p>{@code Math.max(0, NaN)} 会原样返回 {@code NaN}——那样乘进公式会让整块模块容量变 0；而 Infinity
     * 会直接顶到 {@code Long.MAX_VALUE}。所以不能只夹下界。</p>
     */
    private static double clampMultiplier(double value) {
        return Double.isFinite(value) ? Math.max(0.0D, value) : 1.0D;
    }

    /** 两个维度都不贡献——空槽，或认得出但值与倍率都是 0 的组件（倍率 0 等于这一维被关掉）。 */
    public boolean isEmpty() {
        return !hasCapacity() && !hasTransfer();
    }

    /** 这个贡献是否落在容量侧。 */
    public boolean hasCapacity() {
        return this.capacity > 0L && this.capacityMultiplier > 0.0D;
    }

    /** 这个贡献是否落在速率侧。 */
    public boolean hasTransfer() {
        return this.transfer > 0L && this.transferMultiplier > 0.0D;
    }

    /**
     * 用于比较「档位高低」的量级：优先容量值，容量为 0 时退到速率值。
     *
     * <p>手势要判「同种、更低档」与「另一种、同档」，需要一个可排序的标尺。标尺取的是<b>值本身</b>，
     * 不含修正倍率——倍率是调强弱的旋钮，不该让「谁比谁高一档」跟着变。数值化之后，配置里新增一个没人见过的
     * 组件也能自动参与比较。</p>
     */
    public long magnitude() {
        return this.capacity > 0L ? this.capacity : this.transfer;
    }
}
