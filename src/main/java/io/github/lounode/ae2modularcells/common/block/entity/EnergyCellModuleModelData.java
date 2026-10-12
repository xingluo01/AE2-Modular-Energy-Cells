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
 * <p><b>成型与否不在这里</b>：它是方块状态（{@code EnergyCellModuleBlock.FORMED}），照 AE2 合成单元的口径
 * （{@code crafting_unit.json} 的 {@code formed=false/true} 指向两套模型）。方块状态变化必然同步，而模型数据在
 * 客户端按位置缓存、靠自定义包推容易漏——那正是「结构成型了、方块还是未成型纹理」的来源。</p>
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
