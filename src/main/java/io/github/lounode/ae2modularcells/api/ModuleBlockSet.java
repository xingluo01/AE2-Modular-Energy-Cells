package io.github.lounode.ae2modularcells.api;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.entity.BlockEntityType;

import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;

import io.github.lounode.ae2modularcells.common.EnergyCellTier;
import io.github.lounode.ae2modularcells.common.block.EnergyCellModuleBlock;
import io.github.lounode.ae2modularcells.common.block.entity.EnergyCellModuleBlockEntity;

/**
 * 一个等级配套的那三件注册物：方块、方块物品、方块实体类型。
 *
 * <p>由 {@link ModularCellsApi#registerEnergyCellModule} 返回，三件一起给你——它们的注册名必须相同（方块物品与
 * 方块实体都按方块名走），分开注册只会写错。</p>
 *
 * <p>{@code blockEntity} 的类型参数写 {@code ?} 是 NeoForge 的要求：方块实体类型注册器的键就是通配的，具体类型
 * 由 {@link DeferredHolder#get()} 还给你的那个 {@code BlockEntityType<EnergyCellModuleBlockEntity>} 表达。</p>
 *
 * @param tier       这个套装服务的等级
 * @param block      模块方块
 * @param item       模块方块物品（{@code BlockItem}）
 * @param blockEntity 模块方块实体类型；本模组三档共用同一个方块实体类，但类型是分开注册的
 */
public record ModuleBlockSet(
        EnergyCellTier tier,
        DeferredBlock<EnergyCellModuleBlock> block,
        DeferredItem<BlockItem> item,
        DeferredHolder<BlockEntityType<?>, BlockEntityType<EnergyCellModuleBlockEntity>> blockEntity) {
}
