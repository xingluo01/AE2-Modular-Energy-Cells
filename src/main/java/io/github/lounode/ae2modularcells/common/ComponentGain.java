package io.github.lounode.ae2modularcells.common;

/**
 * 一个存储组件为增益算法提供的贡献：容量值与传输速率值。
 *
 * <p><b>两个维度可以同时大于 0</b>。早先的口径是两类组件互斥——一个物品只走一条公式。第三方的「全能」
 * 组件可能两样都给，所以贡献改成一个二元组：{@code 0} 表示这一维不参与。</p>
 *
 * <p>数值口径与两条公式一致：{@code capacity} 是组件自己的容量值（AE2 口径的字节），{@code transfer} 是速率
 * 值；它们分别乘 {@code k} 与 {@code kv} 后计入模块的 {@code D} 与 {@code D_v}（见
 * {@code common.logic.CellMath#moduleGains}）。</p>
 *
 * @param capacity 容量值（容量侧公式的 {@code S_i}）；{@code 0} 表示不参与容量
 * @param transfer 速率值（速率侧公式的 {@code Sv_i}）；{@code 0} 表示不参与速率
 */
public record ComponentGain(long capacity, long transfer) {

    public ComponentGain {
        // 负值一律当 0：数值来自物品（后续还会来自配置），坏数据只应「不参与计算」，不该把汇总算歪。
        capacity = Math.max(0L, capacity);
        transfer = Math.max(0L, transfer);
    }

    /** 两个维度都不贡献——空槽，或识别得出但数值为 0 的组件。 */
    public boolean isEmpty() {
        return this.capacity <= 0L && this.transfer <= 0L;
    }

    /** 这个贡献是否落在容量侧。 */
    public boolean hasCapacity() {
        return this.capacity > 0L;
    }

    /** 这个贡献是否落在速率侧。 */
    public boolean hasTransfer() {
        return this.transfer > 0L;
    }

    /**
     * 用于比较「档位高低」的量级：优先容量值，容量为 0 时退到速率值。
     *
     * <p>手势要判「同种、更低档」与「另一种、同档」，需要一个可排序的标尺。原先这个标尺是
     * {@link StorageComponentTier} 的枚举序号；数值化之后，配置里新增一个没人见过的组件也能自动参与比较。</p>
     */
    public long magnitude() {
        return this.capacity > 0L ? this.capacity : this.transfer;
    }
}
