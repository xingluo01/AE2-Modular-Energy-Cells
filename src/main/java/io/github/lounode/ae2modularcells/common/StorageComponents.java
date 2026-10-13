package io.github.lounode.ae2modularcells.common;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import appeng.api.implementations.items.IStorageComponent;

import io.github.lounode.ae2modularcells.common.config.ModularCellConfig;

/**
 * 存储组件识别与取值的唯一入口。
 *
 * <p>槽位收的是「现成的存储组件」——AE2 的 {@code 1k ~ 256k}、MEGA Cells 的 {@code 1m ~ 256m}、AppFlux 的
 * {@code core_1k ~ 256m}、AE2 OMNI Cells 的 {@code *_omni_cell_component_*}，本模组不自制组件物品。识别按
 * 优先级三层：</p>
 *
 * <ul>
 * <li><b>配置</b>：玩家或整合包在 {@code componentGains} 里声明的条目，优先级最高
 * （见 {@link ModularCellConfig}）——它要能覆盖下面两条内置识别的结果。</li>
 * <li><b>接口路径</b>：AE2 本体与 MEGA Cells 的组件都实现了 AE2 的公开接口 {@link IStorageComponent}，
 * 只要那个物品实现了它，就认；值取 {@code getBytes(stack)}，这一路只贡献容量。</li>
 * <li><b>名字路径</b>：拿不到接口的邻居，按注册名查 {@link BuiltInComponents} 的规则表（AppFlux 只贡献速率、
 * OMNI Cells 只贡献容量），数值取自 {@link StorageComponentTier} 那张档位表。</li>
 * </ul>
 *
 * <p>三条路都产出 {@link ComponentGain}：现有各条路只给一个维度，但类型上允许两个维度同时有值——第三方的
 * 「全能」组件（两样都给）接进来时不必再改公式。</p>
 *
 * <p>为什么接口那一路不用一份自己的「物品 → 数值」映射表：那张表一写就与上游脱节——邻居加了档、改了数值，
 * 本模组还按旧表算。名字路径只用于「确实拿不到接口」的邻居。</p>
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
        // 名字路径：拿不到接口的邻居（AppFlux / OMNI Cells 等），按注册名查规则表。
        return BuiltInComponents.gainOf(stack);
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
     * 这件物品的量级：容量值优先，容量为 0 时退到速率值；不是组件给 {@code 0}。
     *
     * <p>手势要判「同种、更低档」与「另一种、同档」，需要一个可排序的标尺。这个标尺就是数值本身——早先它由
     * {@link StorageComponentTier} 的枚举序号担任，代价是「数值对不上那张表」的组件（配置里新增的、或将来
     * 某个模组自己的档位）没法参与手势。</p>
     */
    public static long magnitudeOf(ItemStack stack) {
        var gain = gainOf(stack);
        return gain == null ? 0L : gain.magnitude();
    }
}
