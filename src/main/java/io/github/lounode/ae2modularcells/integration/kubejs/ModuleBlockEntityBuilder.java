package io.github.lounode.ae2modularcells.integration.kubejs;

import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntityType;

import dev.latvian.mods.kubejs.registry.BuilderBase;

import io.github.lounode.ae2modularcells.api.ModularCellsApi;
import io.github.lounode.ae2modularcells.common.block.EnergyCellModuleBlock;
import io.github.lounode.ae2modularcells.common.block.entity.EnergyCellModuleBlockEntity;

/**
 * 脚本侧的模块方块实体类型 builder。
 *
 * <p>它同时是这条路的收尾：类型建好、回填给方块、并把三件登记进本模组的套装清单——之后能力注册、模型替换与
 * 创造页就和内置三档走完全相同的处理。</p>
 */
public class ModuleBlockEntityBuilder extends BuilderBase<BlockEntityType<EnergyCellModuleBlockEntity>> {

    private final ModuleBlockBuilder blockBuilder;
    private final ModuleItemBuilder itemBuilder;
    private BlockEntityType<EnergyCellModuleBlockEntity> type;

    ModuleBlockEntityBuilder(ResourceLocation id, ModuleBlockBuilder blockBuilder, ModuleItemBuilder itemBuilder) {
        super(id);
        this.blockBuilder = blockBuilder;
        this.itemBuilder = itemBuilder;
    }

    @Override
    public BlockEntityType<EnergyCellModuleBlockEntity> createObject() {
        if (type != null) {
            // 幂等：KubeJS 可能在建对象与取对象时各调一次，而下面的登记绝不能再跑第二遍
            // ——清单里出现同等级的两个套装会让启动校验直接抛。
            return type;
        }
        EnergyCellModuleBlock block = blockBuilder.block();
        // 类型要交给方块实体，而类型本身还在构建中，所以先用一个引用接住。
        AtomicReference<BlockEntityType<EnergyCellModuleBlockEntity>> holder = new AtomicReference<>();
        type = BlockEntityType.Builder.of(
                (pos, state) -> new EnergyCellModuleBlockEntity(holder.get(), pos, state),
                block).build(null);
        holder.setPlain(type);
        // 回填是必需的：方块先注册、类型后注册，方块在构造时拿不到自己的类型，而 AE 的方块基类要靠它做
        // 「拆下来给哪个物品」这类查询。ticker 两处都传 null——模块方块实体不 tick（本模组的性能红线）。
        block.setBlockEntity(EnergyCellModuleBlockEntity.class, type, null, null);

        // 落盘这一刻三件才算真建好，就在这里登记进清单；晚了会撞上「服务端启动后不许再注册」的拦截。
        // 进清单之后：FE 面、组件槽的物品面、以及网格主机能力都会被统一注册
        // ——最后那条漏了就是接不上线缆，而且不报错。
        ModularCellsApi.registerModuleBlockSet(blockBuilder.tier(), id, block, itemBuilder.item(), type);
        return type;
    }
}
