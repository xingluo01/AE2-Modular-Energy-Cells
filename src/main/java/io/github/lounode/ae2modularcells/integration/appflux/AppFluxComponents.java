package io.github.lounode.ae2modularcells.integration.appflux;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import io.github.lounode.ae2modularcells.common.StorageComponentTier;

/**
 * AppFlux（Applied Flux）的能量存储组件适配。
 *
 * <p><b>为什么需要一层适配</b>：AE2 本体与 MEGA Cells 的存储组件都实现了 AE2 的公开接口
 * {@code IStorageComponent}，识别走接口就够；而 AppFlux 的 {@code core_1k ~ core_256m}（语言文件里的名字是
 * 「N ME Energy Storage Component」）<b>没有</b>实现那个接口。结果是这类物品进不了组件槽——槽位的
 * {@code isItemValid} 按接口判断，认不出来就拒收。这一层就是补上那段映射。</p>
 *
 * <p><b>不引邻居的类型名</b>：整个类只比较注册名（{@code namespace:path}）字符串，不 import 任何 AppFlux 的
 * 类。这是 {@code integration/<邻居>} 的红线——一旦把邻居的类名写进总是加载的类，邻居缺席时会在加载阶段炸。
 * 于是这个类不需要门禁、不需要 {@code compileOnly} 依赖，AppFlux 缺席时它只是永远匹配不上。</p>
 *
 * <p>名字口径：{@code appflux:core_<档位>}，档位后缀与 {@link StorageComponentTier} 的 id 完全一致
 * （{@code 1k} / {@code 4k} / … / {@code 256m}），所以取值直接查那张表——数值不在这里写第二份。</p>
 */
public final class AppFluxComponents {

    /** AppFlux 的命名空间。 */
    private static final String NAMESPACE = "appflux";

    /** 组件物品的路径前缀：{@code core_1k}、{@code core_256m}…… */
    private static final String PATH_PREFIX = "core_";

    private AppFluxComponents() {
    }

    /**
     * 这件物品是不是 AppFlux 的能量存储组件；是就给它的容量值（AE），不是给 {@code null}。
     *
     * <p>返回 {@code null} 而不是 0：调用方要能区分「这不是 AppFlux 的组件」与「是组件但值为 0」，前者交给
     * 接口路径继续判，后者按「不参与计算」处理。</p>
     */
    @Nullable
    public static Long energyOf(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null || !NAMESPACE.equals(id.getNamespace())) {
            return null;
        }
        String path = id.getPath();
        if (!path.startsWith(PATH_PREFIX)) {
            return null;
        }
        var tier = StorageComponentTier.byId(path.substring(PATH_PREFIX.length()));
        return tier == null ? null : tier.capacity();
    }
}
