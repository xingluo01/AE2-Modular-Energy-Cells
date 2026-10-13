package io.github.lounode.ae2modularcells.integration.kubejs;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockBehaviour;

import dev.latvian.mods.kubejs.registry.BuilderBase;

import io.github.lounode.ae2modularcells.common.EnergyCellTier;
import io.github.lounode.ae2modularcells.common.block.EnergyCellModuleBlock;

/**
 * 脚本侧的模块方块 builder。
 *
 * <p>只在 {@code createAdditionalObjects} 里被登记，脚本不会直接看到它——所以没有任何可链式调用的方法，
 * 数值与外观都跟着等级走。</p>
 */
public class ModuleBlockBuilder extends BuilderBase<EnergyCellModuleBlock> {

    private final EnergyCellTierBuilder tierBuilder;
    private EnergyCellModuleBlock block;

    ModuleBlockBuilder(ResourceLocation id, EnergyCellTierBuilder tierBuilder) {
        super(id);
        this.tierBuilder = tierBuilder;
    }

    @Override
    public EnergyCellModuleBlock createObject() {
        return block();
    }

    /**
     * 惰性建一次。
     *
     * <p>方块实例被三处需要：方块注册表落盘、方块物品的构造、以及方块实体类型建好后回填给它。而这三张注册表
     * 的处理顺序由 NeoForge 决定，所以这里缓存同一个实例，谁先要都一样。</p>
     */
    EnergyCellModuleBlock block() {
        if (block == null) {
            block = new EnergyCellModuleBlock(tierBuilder.tier(), BlockBehaviour.Properties.of()
                    .strength(2.5F, 6.0F)
                    .requiresCorrectToolForDrops());
        }
        return block;
    }

    EnergyCellTier tier() {
        return tierBuilder.tier();
    }
}
