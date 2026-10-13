package io.github.lounode.ae2modularcells.api;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.entity.BlockEntityType;

import io.github.lounode.ae2modularcells.common.EnergyCellTier;
import io.github.lounode.ae2modularcells.common.block.EnergyCellModuleBlock;
import io.github.lounode.ae2modularcells.common.block.entity.EnergyCellModuleBlockEntity;

/**
 * 一个等级配套的那几件东西，**装的是已注册的实例**：模块方块、它的方块物品、方块实体类型。
 *
 * <p>这是本模组对「一个模块方块套装」的唯一表述（{@link ModularCellsApi#allModuleBlockSets()} 给的元素类型）。
 * 两个来源汇进同一个形状：Java 侧经 {@link ModularCellsApi#registerEnergyCellModule} 注册（内部先收着
 * {@code DeferredRegister} 的句柄，注册完成后解析成实例，见 {@link DeferredModuleBlockSet}），脚本侧经
 * {@link ModularCellsApi#registerModuleBlockSet} 直接交实例——KubeJS 的注册不走 {@code DeferredRegister}，
 * 它手里只有实例。</p>
 *
 * <p><b>为什么要实例而不是句柄</b>：能力注册与客户端模型替换跨模组遍历这张清单，它们要的是真对象。两条路径若
 * 各存一份，脚本注册的方块就会漏掉能力——表现为接不上线缆，而且不报错。</p>
 *
 * @param tier        这个套装服务的等级
 * @param id          方块（也是方块物品、方块实体类型、模型与贴图）的注册名
 * @param block       模块方块
 * @param item        模块方块物品
 * @param blockEntity 模块方块实体类型
 */
public record ModuleBlockSet(
        EnergyCellTier tier,
        ResourceLocation id,
        EnergyCellModuleBlock block,
        BlockItem item,
        BlockEntityType<EnergyCellModuleBlockEntity> blockEntity) {
}
