package io.github.lounode.ae2modularcells.common.block.entity;

import java.util.EnumSet;

import net.minecraft.core.Direction;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;

/**
 * 渲染要用的「连接状态」数据键。
 *
 * <p>连接状态 = 「六个方向上有没有模块方块」（不分等级），由方块实体按需从世界方块状态算出来（见
 * {@link EnergyCellModuleBlockEntity#getModelData()}），客户端与服务端各算各的，所以不需要自定义网络包。</p>
 *
 * <p>失效只有两处：邻居形状变化（{@code updateShape}）与自身方块状态变化。这一点照抄 AE2 合成单元的做法
 * （{@code CraftingBlockEntity#getModelData()} + {@code AbstractCraftingUnitBlock#updateShape}）：连接数据是
 * 推导值，不持久化、也不做增量维护。</p>
 */
public final class EnergyCellModuleModelData {

    /** 六向连接位。空集合表示孤零零一块，四周都画自身边框。 */
    public static final ModelProperty<EnumSet<Direction>> CONNECTIONS = new ModelProperty<>();

    private EnergyCellModuleModelData() {
    }

    public static ModelData create(EnumSet<Direction> connections) {
        return ModelData.builder()
                .with(CONNECTIONS, connections)
                .build();
    }
}
