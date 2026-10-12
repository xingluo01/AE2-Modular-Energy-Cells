package io.github.lounode.ae2modularcells.integration.jade;

import java.util.List;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import snownee.jade.api.Accessor;
import snownee.jade.api.view.ClientViewGroup;
import snownee.jade.api.view.IClientExtensionProvider;
import snownee.jade.api.view.IServerExtensionProvider;
import snownee.jade.api.view.ItemView;
import snownee.jade.api.view.ViewGroup;

import io.github.lounode.ae2modularcells.AE2ModularCells;

/**
 * 挡掉 Jade 的通用「物品存储」行（与 {@link HideThingsExtensionProvider} 挡 FE 能量条同一个用意）。
 *
 * <p><b>为什么要挡</b>：我们的方块暴露的 ItemHandler 是**整段结构**的组件槽视图（上界 4096 块 × 3 = 12288 槽），
 * 而 Jade 的收集器每趟只读 108 槽并带「获取物品中… N%」进度——结构一大，那行就会把数字一段段往上爬；
 * 小结构一趟读完，所以一次性显示完整。这行对玩家也没有意义：那不是可交互的容器，而是一个只进不出、
 * 只收存储组件的接收口（给 AE2 的导出策略用）。给空组 = Jade 不显示这一行。</p>
 */
public final class HideItemStorageProvider
        implements IServerExtensionProvider<ItemStack>, IClientExtensionProvider<ItemStack, ItemView> {

    private static final HideItemStorageProvider INSTANCE = new HideItemStorageProvider();

    public static HideItemStorageProvider instance() {
        return INSTANCE;
    }

    @Override
    public List<ViewGroup<ItemStack>> getGroups(Accessor<?> accessor) {
        return List.of();
    }

    @Override
    public List<ClientViewGroup<ItemView>> getClientGroups(Accessor<?> accessor,
            List<ViewGroup<ItemStack>> groups) {
        return List.of();
    }

    @Override
    public ResourceLocation getUid() {
        return ResourceLocation.fromNamespaceAndPath(AE2ModularCells.MOD_ID, "hide_item_storage");
    }
}
