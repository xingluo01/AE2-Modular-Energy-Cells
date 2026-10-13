package io.github.lounode.ae2modularcells.common;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import appeng.api.implementations.items.IStorageComponent;

import io.github.lounode.ae2modularcells.common.config.ModularCellConfig;
import io.github.lounode.ae2modularcells.integration.appflux.AppFluxComponents;

/**
 * 存储组件识别与取值的唯一入口。
 *
 * <p>槽位收的是「现成的存储组件」——AE2 的 {@code 1k ~ 256k}、MEGA Cells 的 {@code 1m ~ 256m}、AppFlux 的
 * {@code core_1k ~ 256m}，本模组不自制组件物品。识别按优先级三层：</p>
 *
 * <ul>
 * <li><b>配置</b>：玩家或整合包在 {@code componentGains} 里声明的条目，优先级最高
 * （见 {@link ModularCellConfig}）——它要能覆盖下面两条内置识别的结果。</li>
 * <li><b>接口路径</b>：AE2 本体与 MEGA Cells 的组件都实现了 AE2 的公开接口 {@link IStorageComponent}，
 * 只要那个物品实现了它，就认；值取 {@code getBytes(stack)}，这一路只贡献容量。</li>
 * <li><b>名字路径</b>：AppFlux 的 {@code core_1k ~ core_256m} <b>没有</b>实现那个接口，只能按注册名映射到
 * {@link StorageComponentTier}（见 {@code integration.appflux.AppFluxComponents}），这一路只贡献速率。</li>
 * </ul>
 *
 * <p>两条路都产出 {@link ComponentGain}：现有两条路各自只给一个维度，但类型上允许两个维度同时有值——第三方的
 * 「全能」组件（两样都给）接进来时不必再改公式。</p>
 *
 * <p>为什么接口那一路不用一份自己的「物品 → 数值」映射表：那张表一写就与上游脱节——邻居加了档、改了数值，
 * 本模组还按旧表算。名字路径只用于「确实拿不到接口」的邻居。{@link StorageComponentTier} 同时兼作档位与数值的
 * <b>对照表</b>（文档与平衡调整用），名字路径从它取值。</p>
 */
public final class StorageComponents {

    private StorageComponents() {
    }

    /**
     * 这个物品作为存储组件的贡献；不是组件时给 {@code null}。
     *
     * <p>给 {@code null} 而不是抛异常：槽位里放什么由玩家决定，一件不属于任何档位的物品只应「不参与计算」，
     * 不该把一次容量汇总炸掉。</p>
     */
    @Nullable
    public static ComponentGain gainOf(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        // 配置路径：玩家或整合包声明的额外组件，优先级最高——要能覆盖下面两条内置识别的结果。
        // 默认是空表，所以没配过时这一段不花代价。
        var configured = ModularCellConfig.configuredGains();
        if (!configured.isEmpty()) {
            // getKey 可能给 null（未注册的物品），而配置表是不可变 Map、get(null) 会抛，所以先判。
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            var declared = id == null ? null : configured.get(id);
            if (declared != null) {
                return declared;
            }
        }
        // 接口路径：AE2 本体与 MEGA Cells 的 cell_component_*（都实现了这个接口），只贡献容量。
        if (stack.getItem() instanceof IStorageComponent component && component.isStorageComponent(stack)) {
            long bytes = Math.max(0L, component.getBytes(stack));
            return bytes > 0L ? new ComponentGain(bytes, 0L) : null;
        }
        // 名字路径：拿不到接口的邻居（AppFlux 的 core_*，只贡献速率），按注册名映射到档位表。
        Long energy = AppFluxComponents.energyOf(stack);
        return energy != null && energy > 0L ? new ComponentGain(0L, energy) : null;
    }

    /** 这个物品能不能插进组件槽。 */
    public static boolean isComponent(ItemStack stack) {
        var gain = gainOf(stack);
        return gain != null && !gain.isEmpty();
    }

    /**
     * 这件物品是不是「容量类组件」。
     *
     * <p>手势用它区分「同一种」与「另一种」，而两类组件的数值单位不同、不能混着比。一个组件可以两维都给，
     * 那种情况按容量类算——有容量就有可比的标尺。</p>
     */
    public static boolean isCapacityComponent(ItemStack stack) {
        var gain = gainOf(stack);
        return gain != null && gain.hasCapacity();
    }

    /**
     * 这件物品对应的档位：按 {@link ComponentGain#magnitude()} 对齐 {@link StorageComponentTier}；认不出来
     * （陌生模组的组件、数值对不上任何一档）给 {@code null}。
     *
     * <p><b>为什么用一张表当两种组件的公共标尺</b>：两类组件各自的数值单位不同，但档位序列是同一套
     * （{@code 1k < 4k < … < 256m}）。手势要判「更低档」与「同档另一种」，就需要这个公共标尺；一旦认不出档位，
     * 宁可什么都不做，也不要把没数的组件推进槽位当作「升级」。</p>
     */
    @Nullable
    public static StorageComponentTier tierOf(ItemStack stack) {
        var gain = gainOf(stack);
        if (gain == null) {
            return null;
        }
        long value = gain.magnitude();
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
