package io.github.lounode.ae2modularcells.integration.kubejs;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;

import dev.latvian.mods.kubejs.registry.BuilderBase;

/**
 * 脚本侧的模块方块物品 builder：就是普通的 {@link BlockItem}，方块从 {@link ModuleBlockBuilder} 取同一个实例。
 */
public class ModuleItemBuilder extends BuilderBase<BlockItem> {

    private final ModuleBlockBuilder blockBuilder;
    private BlockItem item;

    ModuleItemBuilder(ResourceLocation id, ModuleBlockBuilder blockBuilder) {
        super(id);
        this.blockBuilder = blockBuilder;
    }

    @Override
    public BlockItem createObject() {
        return item();
    }

    BlockItem item() {
        if (item == null) {
            item = new BlockItem(blockBuilder.block(), new Item.Properties());
        }
        return item;
    }
}
