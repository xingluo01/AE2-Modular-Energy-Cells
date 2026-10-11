package io.github.lounode.ae2modularcells.common;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.item.ItemStack;

import appeng.api.implementations.items.IStorageComponent;

import io.github.lounode.ae2modularcells.integration.appflux.AppFluxComponents;

/**
 * 存储组件识别与取值的唯一入口。
 *
 * <p>槽位收的是「现成的存储组件」——AE2 的 {@code 1k ~ 256k}、MEGA Cells 的 {@code 1m ~ 256m}、AppFlux 的
 * {@code core_1k ~ 256m}，本模组不自制组件物品。识别分两条路：</p>
 *
 * <ul>
 * <li><b>接口路径</b>：AE2 本体与 MEGA Cells 的组件都实现了 AE2 的公开接口 {@link IStorageComponent}，
 * 只要那个物品实现了它，就认；值取 {@code getBytes(stack)}。</li>
 * <li><b>名字路径</b>：AppFlux 的 {@code core_1k ~ core_256m} <b>没有</b>实现那个接口，只能按注册名映射到
 * {@link StorageComponentTier}（见 {@code integration.appflux.AppFluxComponents}）。</li>
 * </ul>
 *
 * <p>为什么接口那一路不用一份自己的「物品 → 档位」映射表：那张表一写就与上游脱节——邻居加了档、改了数值，
 * 本模组还按旧表算。名字路径只用于「确实拿不到接口」的邻居。{@link StorageComponentTier} 同时兼作档位与数值的
 * <b>对照表</b>（文档与平衡调整用），两条路都从它取值。</p>
 */
public final class StorageComponents {

    private StorageComponents() {
    }

    /**
     * 这个物品作为存储组件折算多少容量单位；不是组件、或不是存储物品时给 0。
     *
     * <p>返回 0 而不是抛异常：槽位里放什么由玩家决定，一件不属于任何档位的物品只应「不参与计算」，
     * 不该把一次容量汇总炸掉。</p>
     */
    public static long capacityOf(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0L;
        }
        if (stack.getItem() instanceof IStorageComponent component && component.isStorageComponent(stack)) {
            return Math.max(0, component.getBytes(stack));
        }
        // 只有「存储组件」进容量：AE2 的 1k~256k、MEGA Cells 的 cell_component_*（它们都实现了上面的接口）。
        // AppFlux 的 core_* 属于「能量存储组件」，按需求只进输入速率那条，见 energyOf。
        return 0L;
    }

    /**
     * 这个物品作为「能量存储组件」折算多少能量单位，用在<b>输入速率</b>那条公式上；不是能量组件给 0。
     *
     * <p>目前只有 AppFlux 的 {@code core_1k ~ core_256m}（语言名就是 ME Energy Storage Component）：它没实现
     * {@link IStorageComponent}，只能按注册名映射到 {@link StorageComponentTier} 那张表。</p>
     */
    public static long energyOf(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0L;
        }
        Long energy = AppFluxComponents.energyOf(stack);
        return energy != null ? Math.max(0L, energy) : 0L;
    }

    /** 这个物品能不能插进组件槽：两类组件都收（各管一条公式）。 */
    public static boolean isComponent(ItemStack stack) {
        return capacityOf(stack) > 0L || energyOf(stack) > 0L;
    }

    /**
     * 这件物品是「容量类组件」而不是「能量存储组件」。
     *
     * <p>两类组件互斥（{@link #capacityOf} 只认实现 {@code IStorageComponent} 的物品，{@link #energyOf}
     * 只认 AppFlux 的 {@code core_*}），所以一个布尔量就够描述种类。</p>
     */
    public static boolean isCapacityComponent(ItemStack stack) {
        return capacityOf(stack) > 0L;
    }

    /**
     * 这件物品对应的档位：容量类按它自己的值对齐 {@link StorageComponentTier}，能量存储组件由
     * {@link AppFluxComponents} 按注册名映射；认不出来（陌生模组的组件、数值对不上任何一档）给 {@code null}。
     *
     * <p><b>为什么用一张表当两种组件的公共标尺</b>：两类组件各自的数值单位不同，但档位序列是同一套
     * （{@code 1k < 4k < … < 256m}）。手势要判「更低档」与「同档另一种」，就需要这个公共标尺；
     * 一旦认不出档位，宁可什么都不做，也不要把没数的组件推进槽位当作「升级」。</p>
     */
    @Nullable
    public static StorageComponentTier tierOf(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        long value = capacityOf(stack);
        if (value <= 0L) {
            value = energyOf(stack);
        }
        if (value <= 0L) {
            return null;
        }
        for (var tier : StorageComponentTier.values()) {
            if (tier.capacity() == value) {
                return tier;
            }
        }
        return null;
    }
}
