package io.github.lounode.ae2modularcells.common.config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.resources.ResourceLocation;

import net.mezzdev.config.api.Configs;
import net.mezzdev.config.api.schema.IConfigSchema;
import net.mezzdev.config.api.value.IConfigValue;
import net.mezzdev.config.api.value.serializer.ConfigListOrdering;

import io.github.lounode.ae2modularcells.AE2ModularCells;
import io.github.lounode.ae2modularcells.common.ComponentGain;
import io.github.lounode.ae2modularcells.common.ModularCellConstants;

/**
 * 模组配置：两条公式的系数，以及「额外把哪些物品认作存储组件」。
 *
 * <p>配置由 MezzConfig 托管。schema 取<b>服务端归属</b>：值存在存档侧、由服务端下发给客户端，于是联机时
 * 两端用的是同一份数。这条对本模组是必要的——屏幕上的手势判据与槽位校验都跑在客户端，客户端拿到的必须
 * 是服务端那份；换成客户端归属就成了「各读各的文件」，两端会分叉。</p>
 *
 * <p><b>为什么 k 与 kv 不在配置里</b>：它们乘的是「存储组件自己带来的值」（档位表的数值），属于组件常量的
 * 一部分。组件值本身不开放调整，那么「一个组件折算多少」也不该单独可调——放进来只会让人以为可以靠调它把
 * 组件变强，而组件强度是它自己的事。可调的是结构规模与输入口径那三项（b / bv / f_in）；k 与 kv 只读
 * {@link ModularCellConstants} 里的常量。</p>
 *
 * <p><b>为什么这些数要能改</b>：{@link ModularCellConstants} 里可配的那三个系数是平衡值——整合包想调堆叠
 * 收益、或调整输入口径，改数字比等发版现实。{@code componentGains} 解决的是另一件事：<b>不是每个邻居都
 * 实现了 AE2 的公开接口</b>，内置识别路径（接口 / 注册名规则表）都是写死在代码里的模组知识，它让玩家与
 * 整合包不动代码就能补上任意物品。</p>
 *
 * <p><b>「额外组件」为什么是自定义类型的列表，而不是字符串列表</b>：元素的类型是
 * {@link ComponentSpec}，它自带序列化器（{@link ComponentSpecSerializer}）。这样 MezzConfig 的界面
 * 就能<b>逐行</b>校验并给出具体原因（物品不存在、两个值都是 0 之类），而不是把错误留到运行期静默发生。
 * 文本形与旧的字符串写法逐字兼容，旧配置文件原样可读。</p>
 *
 * <p><b>写条目的两个坑</b>（现在会在界面上直接提示，但仍值得知道）：① id 要带命名空间
 * （{@code somemod:component}）——不带就会被当成 {@code minecraft:component}；② 两个值都省略会让这个物品
 * 被「禁用」——它仍被认作组件、贡献全为 0，却会遮蔽掉内置识别。要「不认这个物品」就别写它。</p>
 *
 * <p>配置项的显示名与说明不写在代码里，走语言键
 * {@code ae2modularcells.config.cells.<分类>.<项名>}（见 {@code assets/ae2modularcells/lang/}）。</p>
 */
public final class ModularCellConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(AE2ModularCells.MOD_ID);

    /** 配置 schema。建值顺序即界面上的顺序。 */
    public static final IConfigSchema SCHEMA;

    private static final IConfigValue<Double> CAPACITY_COUNT_BONUS;
    private static final IConfigValue<Double> TRANSFER_COUNT_BONUS;
    private static final IConfigValue<Double> INPUT_RATIO;
    private static final IConfigValue<List<ComponentSpec>> COMPONENT_GAINS;

    /** 解析好的额外组件表：物品 id → 贡献。配置变化时整表重建；默认空表，空表时识别路径零开销。 */
    private static volatile Map<ResourceLocation, ComponentGain> configuredGains = Map.of();

    static {
        var registration = Configs.forMod(AE2ModularCells.MOD_ID);
        var builder = registration.createServerSchemaBuilder("cells.ini", "ae2modularcells.config.cells");

        var coefficients = builder.addCategory("coefficients");
        CAPACITY_COUNT_BONUS = coefficients
                .addDouble("capacityCountBonus", ModularCellConstants.CAPACITY_COUNT_BONUS, 0.0D, 100.0D)
                .build();
        TRANSFER_COUNT_BONUS = coefficients
                .addDouble("transferCountBonus", ModularCellConstants.TRANSFER_COUNT_BONUS, 0.0D, 100.0D)
                .build();
        INPUT_RATIO = coefficients
                .addDouble("inputRatio", ModularCellConstants.INPUT_RATIO, 0.0D, 1.0D)
                .build();

        var components = builder.addCategory("components");
        COMPONENT_GAINS = components
                .addList("componentGains", List.of(), ComponentSpecSerializer.INSTANCE, ConfigListOrdering.ORDERED)
                .build();

        SCHEMA = builder.build();

        // 值的变更走监听；启动那一次不算「变更」，所以手动解析一遍。
        COMPONENT_GAINS.addListener(change -> refresh());
        refresh();
    }

    private ModularCellConfig() {
    }

    /**
     * 触发本类初始化。
     *
     * <p>模组构造期要主动调一次：schema 必须先于配置界面的注册建好（界面靠枚举已 build 的 schema 发现
     * 配置），而本类的静态初始化只会在「有人第一次碰它」时才跑——那个人可能是很晚才发生的容量计算。</p>
     */
    public static void init() {
        // 动作全在静态初始化里，这里只提供入口。
    }

    /** 存储组件容量折扣 k。组件自带的常量，不进配置——理由见类 javadoc。 */
    public static double componentDiscount() {
        return ModularCellConstants.COMPONENT_DISCOUNT;
    }

    /** 容量侧的数量加成 b。 */
    public static double capacityCountBonus() {
        return CAPACITY_COUNT_BONUS.get();
    }

    /** 能量存储组件速率折扣 kv。组件自带的常量，不进配置——理由见类 javadoc。 */
    public static double transferDiscount() {
        return ModularCellConstants.TRANSFER_DISCOUNT;
    }

    /** 速率侧的数量加成 bv。 */
    public static double transferCountBonus() {
        return TRANSFER_COUNT_BONUS.get();
    }

    /** 输入比例 f_in。 */
    public static double inputRatio() {
        return INPUT_RATIO.get();
    }

    /** 配置里额外声明的组件；没配过时是空表。 */
    public static Map<ResourceLocation, ComponentGain> configuredGains() {
        return configuredGains;
    }

    /**
     * 注册完成后置位。
     *
     * <p>在那之前，{@code componentGains} 的条目不做「物品是否存在」的校验——模组物品要到注册事件才进表，
     * 构造期查什么都是「不存在」，会把合法条目误报。见 {@link ComponentSpecSerializer#markRegistryReady()}。</p>
     */
    public static void markRegistryReady() {
        ComponentSpecSerializer.markRegistryReady();
    }

    /**
     * 重建解析结果：元素已经是 {@link ComponentSpec}，这里只把它摊成识别路径要用的两张对照表。
     *
     * <p>文本解析与校验在序列化器里做（界面也会用同一条路径），所以这里不再自己拆字符串。</p>
     */
    private static void refresh() {
        Map<ResourceLocation, ComponentGain> parsed = new HashMap<>();
        try {
            for (ComponentSpec spec : COMPONENT_GAINS.get()) {
                if (spec == null) {
                    continue;
                }
                parsed.put(spec.id(), spec.gain());
            }
        } catch (RuntimeException e) {
            // 静态初始化那一次读值可能早于配置加载完成。不能让它把类初始化搞崩——那会升级成
            // ExceptionInInitializerError，之后每次碰本类都是 NoClassDefFoundError，而症状只是
            // 「额外组件表空着」。退化成保留现有表、记一行。
            LOGGER.warn("额外组件表读取失败，保留现有 {} 条", configuredGains.size(), e);
            return;
        }
        configuredGains = Map.copyOf(parsed);
        LOGGER.info("组件配置：{} 条额外条目已生效", configuredGains.size());
    }
}
