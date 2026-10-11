package io.github.lounode.ae2modularcells.common.inventory;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

import org.jetbrains.annotations.Nullable;

import io.github.lounode.ae2modularcells.common.block.entity.EnergyCellModuleBlockEntity;

/**
 * 组件槽对自动化的面：<b>整段结构</b>的一个视图，只进不出、只收存储组件、只在成型后开口。
 *
 * <p><b>为什么必须按整段暴露</b>：AE2 的导出/输出总线把目标「钉死」在它贴着的那一块上——{@code ExportBusPart}
 * 造策略时算一次坐标与面并长期缓存（见上游 {@code ExportBusPart#getExportStrategy} 与
 * {@code StorageExportStrategy} 里的 {@code BlockCapabilityCache}），所以只包一块的 handler 会让「贴着的这块满了」
 * 直接变成「怎么都塞不进去」，总线永远不会去试别的模块。整段视图把这个决定权收回本模组：槽位下标按
 * 「模块顺序 × 块内槽序」摊平，总线按序插入自然就分散落到不同模块上。</p>
 *
 * <p><b>下标映射每次现算</b>：上游会长期缓存 handler 实例，所以这里不能在构造时把模块列表或每个块的对象钉住；
 * 真正权威的成员列表由宿主预取（{@link EnergyCellModuleBlockEntity#structureModules()}，按结构变更失效），
 * 本类只把摊平的下标映射到「第几块的第几个槽」。</p>
 *
 * <p><b>为什么只进不出</b>：组件槽是「装上去就算账」的配置位，不是库存；允许抽取等于让导入总线把玩家的组件
 * 吸回网络。装机与拆除都由 GUI 与 Shift 手势负责。</p>
 */
public final class StructureComponentItemHandler implements IItemHandler {

    private final EnergyCellModuleBlockEntity host;

    /**
     * 摊平映射的缓存：模块列表一变（结构变更时由核心作废重建）就重算。
     *
     * <p>为什么需要：AE2 的推送是「逐槽试」——满槽时会把每一槽都问一遍，而槽位数上界是 4096 块 × 3 = 12288。
     * 每次现算映射就是千万级迭代。成员列表本身是 {@code List.copyOf} 出来的不可变表，实例没变说明结构没变，
     * 这时直接拿数组查表就是 O(1)。</p>
     */
    @Nullable
    private List<EnergyCellModuleBlockEntity> mappedModules;
    @Nullable
    private Located[] mappedSlots;

    public StructureComponentItemHandler(EnergyCellModuleBlockEntity host) {
        this.host = host;
    }

    /** 当前这一段（含只有自己一块的情形）的成员，顺序稳定：从核心起、按结构布局的模块序。 */
    private List<EnergyCellModuleBlockEntity> modules() {
        return this.host.structureModules();
    }

    @Override
    public int getSlots() {
        // 摊平后的总槽数：每块的槽数按它自己的档（1 / 2 / 3）。上界 = 4096 块 × 3 = 12288。
        return flattened().length;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        var located = locate(slot);
        return located == null ? ItemStack.EMPTY : located.module().componentStack(located.localSlot());
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        var located = locate(slot);
        if (located == null) {
            // 下标越界（结构刚变小）：原样退回，让调用方当成「这一格塞不下」。
            return stack;
        }
        return located.module().insertComponent(located.localSlot(), stack, simulate);
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        // 只进不出：抽取一律空手而归，导入总线据此认为这里没东西可搬。
        return ItemStack.EMPTY;
    }

    @Override
    public int getSlotLimit(int slot) {
        // 组件槽一个槽只放一件（容量折扣按「插了几件」算，堆叠会让公式失去意义）。
        return 1;
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        var located = locate(slot);
        return located != null && located.module().isComponentSlotAcceptable(located.localSlot(), stack);
    }

    /** 摊平下标 → 「第几块的第几个槽」；越界给 {@code null}。 */
    private record Located(EnergyCellModuleBlockEntity module, int localSlot) {
    }

    private Located locate(int flatSlot) {
        if (flatSlot < 0) {
            return null;
        }
        var map = flattened();
        return flatSlot < map.length ? map[flatSlot] : null;
    }

    /** 摊平后的下标表；模块列表实例没变就直接复用上一次算好的表。 */
    private Located[] flattened() {
        var modules = modules();
        if (this.mappedSlots != null && this.mappedModules == modules) {
            return this.mappedSlots;
        }
        int total = 0;
        for (var module : modules) {
            total += module.componentSlotCount();
        }
        var map = new Located[total];
        int index = 0;
        for (var module : modules) {
            for (int local = 0; local < module.componentSlotCount(); local++) {
                map[index++] = new Located(module, local);
            }
        }
        this.mappedModules = modules;
        this.mappedSlots = map;
        return map;
    }

    /** 只读快照，给调试/日志用：摊平后的槽位与所属方块。 */
    public List<String> describeSlots() {
        var out = new ArrayList<String>();
        int index = 0;
        for (var module : modules()) {
            for (int local = 0; local < module.componentSlotCount(); local++) {
                out.add(index++ + " -> " + module.getBlockPos().toShortString() + "#" + local);
            }
        }
        return out;
    }
}
