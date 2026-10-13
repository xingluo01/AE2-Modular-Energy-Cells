package io.github.lounode.ae2modularcells.common.config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.resources.ResourceLocation;

import net.mezzdev.config.api.Configs;
import net.mezzdev.config.api.schema.IConfigSchema;
import net.mezzdev.config.api.value.IConfigValue;

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
 * <p><b>为什么这些数要能改</b>：{@link ModularCellConstants} 里那五个系数是平衡值——整合包想调堆叠收益、
 * 或者想让自己的组件比内置档位更强，改数字比等发版现实。同理，<b>不是每个邻居都实现了 AE2 的公开接口</b>，
 * 内置识别路径（接口 / 注册名规则表）都是写死在代码里的模组知识；{@code componentGains} 让玩家与整合包
 * 不动代码就能补上任意物品。</p>
 *
 * <p><b>为什么表是字符串列表而不是结构化的表</b>：MezzConfig 的建值 API 是强类型的（内建类型各有一个
 * {@code addXxx}），没有「任意对象数组」这一档。取一个够用的约定语法：
 * {@code <物品 id>=<容量值>:<速率值>}，两个值都可以省略。</p>
 *
 * <p><b>写条目的两个坑</b>：① id 要带命名空间（{@code somemod:component}）——不带就会被当成
 * {@code minecraft:component}，匹配不上任何东西；② 两个值都省略会把这个物品「禁用」——它仍被认作组件，
 * 但贡献全为 0，于是遮蔽掉下面两条内置识别。要「不认这个物品」就直接别写它。</p>
 *
 * <p>配置项的显示名与说明不写在代码里，走语言键
 * {@code ae2modularcells.config.cells.<分类>.<项名>}（见 {@code assets/ae2modularcells/lang/}）。</p>
 */
public final class ModularCellConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(AE2ModularCells.MOD_ID);

    /** 配置 schema。建值顺序即界面上的顺序。 */
    public static final IConfigSchema SCHEMA;

    private static final IConfigValue<Double> COMPONENT_DISCOUNT;
    private static final IConfigValue<Double> CAPACITY_COUNT_BONUS;
    private static final IConfigValue<Double> TRANSFER_DISCOUNT;
    private static final IConfigValue<Double> TRANSFER_COUNT_BONUS;
    private static final IConfigValue<Double> INPUT_RATIO;
    private static final IConfigValue<List<String>> COMPONENT_GAINS;

    /** 解析好的额外组件表：物品 id → 贡献。配置变化时整表重建；默认空表，空表时识别路径零开销。 */
    private static volatile Map<ResourceLocation, ComponentGain> configuredGains = Map.of();

    static {
        var registration = Configs.forMod(AE2ModularCells.MOD_ID);
        var builder = registration.createServerSchemaBuilder("cells.ini", "ae2modularcells.config.cells");

        var coefficients = builder.addCategory("coefficients");
        COMPONENT_DISCOUNT = coefficients
                .addDouble("componentDiscount", ModularCellConstants.COMPONENT_DISCOUNT,
                        ModularCellConstants.COMPONENT_DISCOUNT_MIN, ModularCellConstants.COMPONENT_DISCOUNT_MAX)
                .build();
        CAPACITY_COUNT_BONUS = coefficients
                .addDouble("capacityCountBonus", ModularCellConstants.CAPACITY_COUNT_BONUS, 0.0D, 100.0D)
                .build();
        TRANSFER_DISCOUNT = coefficients
                .addDouble("transferDiscount", ModularCellConstants.TRANSFER_DISCOUNT, 0.0D, 1.0D)
                .build();
        TRANSFER_COUNT_BONUS = coefficients
                .addDouble("transferCountBonus", ModularCellConstants.TRANSFER_COUNT_BONUS, 0.0D, 100.0D)
                .build();
        INPUT_RATIO = coefficients
                .addDouble("inputRatio", ModularCellConstants.INPUT_RATIO, 0.0D, 1.0D)
                .build();

        var components = builder.addCategory("components");
        COMPONENT_GAINS = components.addStringList("componentGains", List.of()).build();

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

    /** 存储组件容量折扣 k。 */
    public static double componentDiscount() {
        return COMPONENT_DISCOUNT.get();
    }

    /** 容量侧的数量加成 b。 */
    public static double capacityCountBonus() {
        return CAPACITY_COUNT_BONUS.get();
    }

    /** 能量存储组件速率折扣 kv。 */
    public static double transferDiscount() {
        return TRANSFER_DISCOUNT.get();
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
     * 重建解析结果：条目是字符串，解析要查注册表，而这表会被识别路径逐槽命中，所以放在变更监听里做，
     * 不放在读取路径上。
     */
    private static void refresh() {
        Map<ResourceLocation, ComponentGain> parsed = new HashMap<>();
        try {
            for (String entry : COMPONENT_GAINS.get()) {
                var parsedEntry = parseEntry(entry);
                if (parsedEntry == null) {
                    continue;
                }
                parsed.put(parsedEntry.id(), parsedEntry.gain());
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

    /** 解析好的一条配置：物品 id 与它的贡献。 */
    private record ParsedEntry(ResourceLocation id, ComponentGain gain) {
    }

    /**
     * 解析 {@code <物品 id>=<容量值>:<速率值>}；格式不对给 {@code null} 并记一行日志。
     *
     * <p>省略度允许：{@code "mod:item"}（两个值都 0，等于没写）、{@code "mod:item=1024"}（只给容量）、
     * {@code "mod:item=0:64"}（只给速率）。</p>
     */
    @Nullable
    private static ParsedEntry parseEntry(String entry) {
        if (entry == null || entry.isBlank()) {
            return null;
        }
        String text = entry.trim();
        int equals = text.indexOf('=');
        String idText = equals < 0 ? text : text.substring(0, equals).trim();
        ResourceLocation id = ResourceLocation.tryParse(idText);
        if (id == null) {
            LOGGER.warn("组件配置条目「{}」的物品 id 解析不出来，已跳过", entry);
            return null;
        }
        long capacity = 0L;
        long transfer = 0L;
        if (equals >= 0) {
            String[] values = text.substring(equals + 1).trim().split(":", -1);
            if (values.length > 2) {
                LOGGER.warn("组件配置条目「{}」的贡献值格式不对（应为 <容量>:<速率>），已跳过", entry);
                return null;
            }
            capacity = parseValue(values[0], entry);
            transfer = values.length > 1 ? parseValue(values[1], entry) : 0L;
        }
        return new ParsedEntry(id, new ComponentGain(capacity, transfer));
    }

    /** 解析一个数值：空串当 0，解析不出来当 0 并记一行日志。 */
    private static long parseValue(String text, String entry) {
        if (text == null || text.isBlank()) {
            return 0L;
        }
        try {
            return Math.max(0L, Long.parseLong(text.trim()));
        } catch (NumberFormatException e) {
            LOGGER.warn("组件配置条目「{}」里的数值「{}」解析不出来，按 0 处理", entry, text);
            return 0L;
        }
    }
}
