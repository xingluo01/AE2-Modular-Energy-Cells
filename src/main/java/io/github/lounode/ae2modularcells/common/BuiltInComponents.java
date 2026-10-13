package io.github.lounode.ae2modularcells.common;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * 内置的「按注册名认组件」规则表。
 *
 * <p>AE2 本体与 MEGA Cells 的组件实现了 AE2 的公开接口，识别走接口就够（见 {@link StorageComponents}）。
 * 但有些邻居的组件就是普通物品——它们没实现那个接口，只能按注册名认。这张表就是那些「模组知识」的落点：
 * 一条规则 = 命名空间 + 路径前缀 + 贡献到哪个维度，数值本身仍然只查 {@link StorageComponentTier} 那张档位表
 * （本类里不写第二份数字）。</p>
 *
 * <p><b>为什么不引邻居的类型名</b>：整张表只比较 {@code namespace:path} 字符串，不 import 任何邻居的类。
 * 这是 {@code integration/<邻居>} 那条红线的同一口径——一旦把邻居的类名写进总是加载的类，邻居缺席时会在
 * 加载阶段炸。于是本类不需要门禁、不需要 {@code compileOnly} 依赖，邻居缺席时它只是永远匹配不上。</p>
 *
 * <p>新邻居按注册名接入时，在这里加一条；数值能对上内置档位就直接查表，对不上（或需要同时给两个维度）的
 * 才考虑在配置里逐物品声明，见 {@code common.config.ModularCellConfig}。</p>
 */
public final class BuiltInComponents {

    /** 一条规则把组件值算到哪个维度。 */
    private enum Channel {
        /** 贡献容量（容量侧公式的 {@code S_i}）。 */
        CAPACITY,
        /** 贡献速率（速率侧公式的 {@code Sv_i}）。 */
        TRANSFER
    }

    /**
     * 一条规则：某命名空间下、路径以列出的任一前缀开头的物品，按「去掉前缀后的后缀」查档位表。
     *
     * @param namespace 物品 id 的命名空间
     * @param prefixes  路径前缀；后缀必须能对上 {@link StorageComponentTier} 的某个 id 才算命中
     */
    private record Rule(String namespace, List<String> prefixes, Channel channel) {
    }

    /**
     * 现有规则。加邻居时按命名空间字母序插入，便于对照。
     *
     * <p>OMNI Cells 的组件是纯 {@code new Item(...)}、不实现 AE2 的接口，也不自带任何数值——它的字节数只写在
     * 元件上。档位后缀（{@code 1k} … {@code 256m}）与内置档位表逐项一致，所以能直接查表；它没有速率概念，
     * 于是只贡献容量。</p>
     *
     * <p><b>这条对应关系是按上游命名推断的</b>（组件本身不带数值，只能从「哪个组件合成哪个档位的元件」推出来），
     * 未经实机核对：若某档实际上不是表里那个值，用配置的 {@code componentGains} 逐条覆盖即可——配置优先级高于
     * 本表（见 {@code common.config.ModularCellConfig}）。</p>
     */
    private static final List<Rule> RULES = List.of(
            // AppFlux 的 core_1k ~ core_256m：语言名是「ME Energy Storage Component」，只进速率侧。
            new Rule("appflux", List.of("core_"), Channel.TRANSFER),
            // AE2 OMNI Cells 的三组存储组件，只进容量侧。
            new Rule("ae2omnicells",
                    List.of("omni_cell_component_", "complex_omni_cell_component_", "quantum_omni_cell_component_"),
                    Channel.CAPACITY));

    private BuiltInComponents() {
    }

    /**
     * 按注册名折算这件物品的贡献；没有规则命中给 {@code null}。
     *
     * <p>命中要求「命名空间一致 + 路径以某前缀开头 + 剩下的后缀能对上档位」。前缀对上而后缀对不上时继续试
     * 下一条前缀（例如 {@code omni_cell_component_} 与 {@code complex_omni_cell_component_} 的先后无关）。</p>
     */
    @Nullable
    public static ComponentGain gainOf(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null) {
            return null;
        }
        String path = id.getPath();
        for (var rule : RULES) {
            if (!rule.namespace().equals(id.getNamespace())) {
                continue;
            }
            for (String prefix : rule.prefixes()) {
                if (!path.startsWith(prefix)) {
                    continue;
                }
                var tier = StorageComponentTier.byId(path.substring(prefix.length()));
                if (tier == null) {
                    continue;
                }
                return rule.channel() == Channel.CAPACITY
                        ? new ComponentGain(tier.capacity(), 0L)
                        : new ComponentGain(0L, tier.capacity());
            }
        }
        return null;
    }
}
