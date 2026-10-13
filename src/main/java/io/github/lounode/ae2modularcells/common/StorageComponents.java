package io.github.lounode.ae2modularcells.common;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import io.github.lounode.ae2modularcells.common.config.ModularCellConfig;

/**
 * 存储组件识别与取值的唯一入口。
 *
 * <p>槽位收的是「现成的存储组件」——AE2 的 {@code 1k ~ 256k}、MEGA Cells 的 {@code 1m ~ 256m}、AppFlux 的
 * {@code core_1k ~ 256m}、AE2 OMNI Cells 的 {@code *_omni_cell_component_*}，本模组不自制组件物品。</p>
 *
 * <p><b>识别只看配置表</b>：哪些物品算组件、各贡献多少，全权由 {@code componentGains} 托管
 * （见 {@link ModularCellConfig}）。代码侧的 {@link BuiltInComponents} 只是「内置清单」——它在注册完成后
 * 被写进配置当初始值，之后以文件内容为准，改它不会影响已有存档。</p>
 *
 * <p>取舍：组件表因此完全可见可改（整合包想怎么调就怎么调），代价是与上游脱节——邻居加了档、或改了字节数，
 * 本模组不会自己跟；要跟就改配置里那一行。新装的邻居模组靠「注册时把内置清单里配置还没有的条目补进去」接入。</p>
 *
 * <p>条目产出 {@link ComponentGain}：两个维度可以同时有值——第三方的「全能」组件（两样都给）接进来时
 * 不必再改公式。</p>
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
        // 组件表由配置全权托管：哪些物品算组件、各给多少值，只认配置文件里的条目。
        // 代码侧的内置清单只在初始化时写进配置当初始值（见 BuiltInComponents），不参与运行时识别。
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        // getKey 可能给 null（未注册的物品），而配置表是不可变 Map、get(null) 会抛，所以先判。
        return id == null ? null : ModularCellConfig.configuredGains().get(id);
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
