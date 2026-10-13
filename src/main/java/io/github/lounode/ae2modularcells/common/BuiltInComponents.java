package io.github.lounode.ae2modularcells.common;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.resources.ResourceLocation;

/**
 * 内置组件目录：本模组「认识哪些组件」这份知识的落点，也是配置初始值的来源。
 *
 * <p><b>它不参与运行时识别</b>：识别只看配置表（见 {@link StorageComponents}），这里的作用是在
 * {@code FMLCommonSetupEvent} 那一步把清单写进配置——写成一次性的初始值。写完之后，哪个组件给多少值
 * 就完全以配置文件为准，改本类不会再影响已有存档。</p>
 *
 * <p>清单分两类来源：
 * <ul>
 *   <li><b>接口路径</b>（AE2 本体、MEGA Cells）：它们的组件实现了 AE2 的公开接口，但 id 形制规整，
 *       直接列出来即可。注意 AE2 只出 k 系（{@code 1k} ~ {@code 256k}）、MEGA 只出 m 系
 *       （{@code 1m} ~ {@code 256m}），不分的话会生成 {@code ae2:cell_component_1m} 这种不存在的物品。</li>
 *   <li><b>名字路径</b>（{@link #RULES} 里的邻居）：那些组件是普通物品、没实现接口，只能按注册名认。</li>
 * </ul>
 * 数值全部取自 {@link StorageComponentTier}，本类不写第二份数字。</p>
 *
 * <p><b>为什么不引邻居的类型名</b>：整张表只拿来拼 {@code namespace:path} 字符串，不 import 任何邻居的类。
 * 这是 {@code integration/<邻居>} 那条红线的同一口径——一旦把邻居的类名写进总是加载的类，邻居缺席时会在
 * 加载阶段炸。邻居缺席时，它的条目只是永远不会被用上。</p>
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
     * 接口路径提供的组件的来源：命名空间，以及该模组的组件只覆盖哪一类档位。
     *
     * <p>AE2 本体只有 k 系（{@code 1k} ~ {@code 256k}），MEGA Cells 只有 m 系（{@code 1m} ~ {@code 256m}）；
     * 不分的话会生成 {@code ae2:cell_component_1m} 这种不存在的物品。</p>
     */
    private record InterfaceSource(String namespace, String tierSuffix) {
    }

    private static final List<InterfaceSource> INTERFACE_SOURCES = List.of(
            new InterfaceSource("ae2", "k"),
            new InterfaceSource("megacells", "m"));

    /** 接口路径组件的路径前缀，与档位 id 拼成完整路径。 */
    private static final String INTERFACE_PREFIX = "cell_component_";

    /**
     * 内置支持的组件的完整清单，带值——配置的初始值就是它。
     *
     * <p>顺序：接口路径的两个模组在前，然后按 {@link #RULES} 的顺序（AppFlux → OMNI）。</p>
     */
    public static List<ComponentSpec> catalog() {
        List<ComponentSpec> all = new ArrayList<>();
        for (var source : INTERFACE_SOURCES) {
            for (var tier : StorageComponentTier.values()) {
                if (!tier.id().endsWith(source.tierSuffix())) {
                    continue;
                }
                all.add(ComponentSpec.of(
                        ResourceLocation.fromNamespaceAndPath(source.namespace(), INTERFACE_PREFIX + tier.id()),
                        tier.capacity(), 0L));
            }
        }
        for (var rule : RULES) {
            boolean capacitySide = rule.channel() == Channel.CAPACITY;
            for (String prefix : rule.prefixes()) {
                for (var tier : StorageComponentTier.values()) {
                    all.add(ComponentSpec.of(
                            ResourceLocation.fromNamespaceAndPath(rule.namespace(), prefix + tier.id()),
                            capacitySide ? tier.capacity() : 0L,
                            capacitySide ? 0L : tier.capacity()));
                }
            }
        }
        return List.copyOf(all);
    }
}
