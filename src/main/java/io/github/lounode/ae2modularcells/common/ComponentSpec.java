package io.github.lounode.ae2modularcells.common;

import net.minecraft.resources.ResourceLocation;

/**
 * 配置里的一条存储组件：物品 id 加上它给两个维度的值与修正倍率。
 *
 * <p>组件表由配置<b>全权托管</b>——哪些物品算组件、各贡献多少、强弱倍率是多少，只认配置文件里的这些条目
 * （见 {@code common.config.ModularCellConfig}）。代码侧只在初始化时提供一份内置清单当初始值。</p>
 *
 * @param id                 物品的注册名，<b>要带命名空间</b>
 * @param capacity           贡献给容量侧的值（公式里的 {@code S_i}）
 * @param transfer           贡献给速率侧的值（公式里的 {@code Sv_i}）
 * @param capacityMultiplier 这个组件自己的容量修正倍率（公式里的 {@code m_i}）
 * @param transferMultiplier 这个组件自己的速率修正倍率（公式里的 {@code mv_i}）
 * @see io.github.lounode.ae2modularcells.common.config.ComponentSpecSerializer
 */
public record ComponentSpec(ResourceLocation id, long capacity, long transfer,
        double capacityMultiplier, double transferMultiplier) {

    /** 不带修正的条目（两个倍率都是 {@code 1.0}）。 */
    public static ComponentSpec of(ResourceLocation id, long capacity, long transfer) {
        return new ComponentSpec(id, capacity, transfer, 1.0D, 1.0D);
    }

    /** 转成计算用的贡献对象。 */
    public ComponentGain gain() {
        return new ComponentGain(capacity, transfer, capacityMultiplier, transferMultiplier);
    }

    /** 两个维度是否都不贡献——这样的条目只会把这个物品认作组件，什么都加不上。 */
    public boolean isEmpty() {
        return gain().isEmpty();
    }
}
