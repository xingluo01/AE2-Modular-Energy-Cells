package io.github.lounode.ae2modularcells.common.inventory;

import java.util.function.BooleanSupplier;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * 组件槽对外的自动化面：<b>只进不出</b>，且只在结构成型后开口。
 *
 * <p>给 ME 网络的自动化用（导出/输出总线、ME 接口的推送）：它们找目标方块走的是 NeoForge 的
 * {@code Capabilities.ItemHandler.BLOCK}，AE2 自己的 `IStorageMonitorable` 那条路在 1.21 上已经换成了
 * 平台能力（上游 {@code StorageExportStrategy} 就是查 ItemHandler）。所以只要把组件槽包成 ItemHandler 就
 * 能被总线看到。</p>
 *
 * <p><b>为什么只进不出</b>：组件槽是「装上去就算账」的配置位，不是库存。允许抽取等于让导入总线把玩家的
 * 组件吸回网络——那不是本模组要提供的玩法，也会让「组件被谁拿走了」变得难以解释。装机与拆除都由 GUI 负责。</p>
 *
 * <p><b>为什么还要成型</b>：未成型时 GUI 打不开（见 {@code EnergyCellModuleBlock}），自动化此时能塞组件就会
 * 出现「东西进去了但玩家没法管理」的状态；等结构拼好再放，与人手的门槛一致。</p>
 *
 * <p>插入门禁每次调用现问（{@link BooleanSupplier}），不缓存成型状态：结构随时可能被拆，缓存会让这个面在
 * 拆完之后还继续收东西。</p>
 */
public final class ComponentInsertOnlyItemHandler implements IItemHandler {

    private final IItemHandler delegate;
    private final BooleanSupplier formed;
    private final java.util.function.Predicate<ItemStack> accepted;

    /**
     * @param delegate 真正的库存（我方组件槽的 ItemHandler 视图）
     * @param formed   结构是否成型；每次插入都会问一遍
     * @param accepted 收不收这个物品（只收存储组件）
     */
    public ComponentInsertOnlyItemHandler(IItemHandler delegate, BooleanSupplier formed,
            java.util.function.Predicate<ItemStack> accepted) {
        this.delegate = delegate;
        this.formed = formed;
        this.accepted = accepted;
    }

    @Override
    public int getSlots() {
        return this.delegate.getSlots();
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        return this.delegate.getStackInSlot(slot);
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (stack.isEmpty() || !this.formed.getAsBoolean() || !this.accepted.test(stack)) {
            return stack;
        }
        return this.delegate.insertItem(slot, stack, simulate);
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        // 只进不出：抽取一律空手而归，导入总线据此认为这里没东西可搬。
        return ItemStack.EMPTY;
    }

    @Override
    public int getSlotLimit(int slot) {
        return this.delegate.getSlotLimit(slot);
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        // isItemValid 是「物品能不能进」的问法，答的是插入口径（总线也会用它做预判）。
        return this.formed.getAsBoolean() && this.accepted.test(stack) && this.delegate.isItemValid(slot, stack);
    }
}
