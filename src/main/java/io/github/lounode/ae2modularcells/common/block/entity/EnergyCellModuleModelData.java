package io.github.lounode.ae2modularcells.common.block.entity;

import java.util.EnumSet;

import net.minecraft.core.Direction;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;

/**
 * 渲染要用的「连接状态」数据键。
 *
 * <p>渲染要用的「连接状态」与「成型与否」：连接位决定边框怎么画，成型与否决定用成型纹理还是未成型纹理。</p>
 *
 * <p>失效只有两处：邻居形状变化（{@code updateShape}）与自身方块状态变化。这一点照抄 AE2 合成单元的做法
 * （{@code CraftingBlockEntity#getModelData()} + {@code AbstractCraftingUnitBlock#updateShape}）：连接数据是
 * 推导值，不持久化、也不做增量维护。</p>
 */
public final class EnergyCellModuleModelData {

    /** 六向连接位。空集合表示孤零零一块，四周都画自身边框。 */
    public static final ModelProperty<EnumSet<Direction>> CONNECTIONS = new ModelProperty<>();

    /**
     * 这一块是不是「已成型结构的一部分」。
     *
     * <p>与 {@link #CONNECTIONS} 是两件事：连接位只说「四周有没有同类块」，而成型是结构检测的结论
     * （边长合法、实心、达到 3×3×3 起）。判据必须用成型，不能用「有没有邻居」代替：
     * 2×2×2、或者边长合法的段里被拆掉一块——这两种情况每块都有邻居，但它们并没有成型。</p>
     */
    public static final ModelProperty<Boolean> FORMED = new ModelProperty<>();

    private EnergyCellModuleModelData() {
    }

    public static ModelData create(EnumSet<Direction> connections, boolean formed) {
        return ModelData.builder()
                .with(CONNECTIONS, connections)
                .with(FORMED, formed)
                .build();
    }
}
