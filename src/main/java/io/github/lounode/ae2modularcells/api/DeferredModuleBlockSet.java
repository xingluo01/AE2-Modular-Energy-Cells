package io.github.lounode.ae2modularcells.api;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.entity.BlockEntityType;

import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;

import io.github.lounode.ae2modularcells.common.EnergyCellTier;
import io.github.lounode.ae2modularcells.common.block.EnergyCellModuleBlock;
import io.github.lounode.ae2modularcells.common.block.entity.EnergyCellModuleBlockEntity;

/**
 * Java 侧注册一个方块套装时拿到的回执：三个 {@code DeferredRegister} 句柄。
 *
 * <p>为什么还要这么一个「句柄版」而不是直接用 {@link ModuleBlockSet}：经 {@code DeferredRegister} 注册的东西
 * 在 {@code RegisterEvent} 之前只是排队，那时 {@code get()} 拿不到实例，所以这一刻只能先收着句柄；等注册表填好
 * （{@link ModularCellsApi#allModuleBlockSets()} 首次被调用，即能力注册那一步）再解析成
 * {@link ModuleBlockSet}。</p>
 *
 * <p>脚本那条路（KubeJS）不走 {@code DeferredRegister}，它在 {@code RegisterEvent} 里自己登记，手里本来就
 * 只有实例，所以走 {@link ModularCellsApi#registerModuleBlockSet}，不经过本类型。</p>
 *
 * @param tier        要服务的等级
 * @param name        注册名（方块、方块物品、方块实体类型三者同名）
 * @param block       模块方块句柄
 * @param item        模块方块物品句柄
 * @param blockEntity 模块方块实体类型句柄
 */
public record DeferredModuleBlockSet(
        EnergyCellTier tier,
        String name,
        DeferredBlock<EnergyCellModuleBlock> block,
        DeferredItem<BlockItem> item,
        DeferredHolder<BlockEntityType<?>, BlockEntityType<EnergyCellModuleBlockEntity>> blockEntity) {

    /**
     * 三件是否都已绑定。
     *
     * <p>三者落在三张不同注册表（方块 / 物品 / 方块实体类型），分三次绑定，所以必须逐个看：只看方块的话，
     * 「方块已绑定而物品未绑定」那个窗口里 {@link #resolve()} 里的 {@code item.get()} 会抛。</p>
     */
    public boolean isBound() {
        return block.isBound() && item.isBound() && blockEntity.isBound();
    }

    /** 解析成实例制。只在 {@link #isBound()} 为真时调用。 */
    public ModuleBlockSet resolve() {
        return new ModuleBlockSet(tier, block.getId(), block.get(), item.get(), blockEntity.get());
    }
}
