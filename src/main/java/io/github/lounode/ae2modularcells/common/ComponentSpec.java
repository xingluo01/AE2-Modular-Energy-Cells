package io.github.lounode.ae2modularcells.common;

import net.minecraft.resources.ResourceLocation;

/**
 * 配置里的一条存储组件：物品 id 加上它给两个维度的值。
 *
 * <p>组件表由配置<b>全权托管</b>——哪些物品算组件、各贡献多少，只认配置文件里的这些条目（见
 * {@code common.config.ModularCellConfig}）。代码侧只在初始化时提供一份内置清单当初始值；写进配置之后，
 * 一切都以文件内容为准，这里不再有第二处权威。</p>
 *
 * @param id       物品的注册名，<b>要带命名空间</b>
 * @param capacity 贡献给容量侧的值（公式里的 {@code S_i}）
 * @param transfer 贡献给速率侧的值（公式里的 {@code Sv_i}）
 * @see io.github.lounode.ae2modularcells.common.config.ComponentSpecSerializer
 */
public record ComponentSpec(ResourceLocation id, long capacity, long transfer) {

    /** 转成识别路径用的贡献对象。 */
    public ComponentGain gain() {
        return new ComponentGain(capacity, transfer);
    }

    /** 两个维度是否都是 0——这样的条目不贡献任何东西，但仍会把这个物品认作组件。 */
    public boolean isEmpty() {
        return capacity <= 0L && transfer <= 0L;
    }
}
