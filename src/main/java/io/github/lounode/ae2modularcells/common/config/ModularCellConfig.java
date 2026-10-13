package io.github.lounode.ae2modularcells.common.config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

import io.github.lounode.ae2modularcells.AE2ModularCells;
import io.github.lounode.ae2modularcells.common.ComponentGain;
import io.github.lounode.ae2modularcells.common.ModularCellConstants;

/**
 * 模组配置：两条公式的系数，以及「额外把哪些物品认作存储组件」。
 *
 * <p><b>为什么这些数要能改</b>：{@link ModularCellConstants} 里那五个系数是平衡值——整合包想调堆叠收益、
 * 或者想让自己的组件比内置档位更强，改数字比等发版现实。同理，<b>不是每个邻居都实现了 AE2 的公开接口</b>，
 * 现有两条识别路径（接口 / AppFlux 注册名）都是写死在代码里的模组知识；{@code componentGains} 让玩家与
 * 整合包不动代码就能补上任意物品。</p>
 *
 * <p><b>为什么表是字符串列表而不是结构化的 TOML 表</b>：NeoForge 的 {@code ModConfigSpec} 表达能力有限，
 * 写不出「对象数组」。取一个够用的约定语法：{@code <物品 id>=<容量值>:<速率值>}，两个值都可以省略。</p>
 *
 * <p><b>写条目的两个坑</b>：① id 要带命名空间（{@code somemod:component}）——不带就会被当成
 * {@code minecraft:component}，匹配不上任何东西；② 两个值都省略会把这个物品「禁用」——它仍被认作组件，
 * 但贡献全为 0，于是遮蔽掉下面两条内置识别。要「不认这个物品」就直接别写它。</p>
 *
 * <p>配置类型取 {@code COMMON}：客户端与服务端各自加载同一份文件。客户端也需要这些数——屏幕上的手势
 * （同档 / 更低档判据）与槽位校验都跑在客户端。</p>
 */
public final class ModularCellConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(AE2ModularCells.MOD_ID);

    public static final ModConfigSpec SPEC;

    private static final ModConfigSpec.DoubleValue COMPONENT_DISCOUNT;
    private static final ModConfigSpec.DoubleValue CAPACITY_COUNT_BONUS;
    private static final ModConfigSpec.DoubleValue TRANSFER_DISCOUNT;
    private static final ModConfigSpec.DoubleValue TRANSFER_COUNT_BONUS;
    private static final ModConfigSpec.DoubleValue INPUT_RATIO;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> COMPONENT_GAINS;

    /** 解析好的额外组件表：物品 id → 贡献。配置重载时整表重建；默认空表，空表时识别路径零开销。 */
    private static volatile Map<ResourceLocation, ComponentGain> configuredGains = Map.of();

    static {
        var builder = new ModConfigSpec.Builder();

        builder.comment("两条公式里的系数。改这里会让整段结构的容量与速率跟着变。").push("coefficients");
        COMPONENT_DISCOUNT = builder
                .comment("存储组件容量折扣 k（单模块容量 D = (A + Σ S_i × k) × (1 + b × C)）")
                .defineInRange("componentDiscount", ModularCellConstants.COMPONENT_DISCOUNT,
                        ModularCellConstants.COMPONENT_DISCOUNT_MIN, ModularCellConstants.COMPONENT_DISCOUNT_MAX);
        CAPACITY_COUNT_BONUS = builder
                .comment("容量侧的数量加成 b：插满 C 个组件时，容量乘 (1 + b × C)")
                .defineInRange("capacityCountBonus", ModularCellConstants.CAPACITY_COUNT_BONUS, 0.0D, 100.0D);
        TRANSFER_DISCOUNT = builder
                .comment("能量存储组件速率折扣 kv（单模块速率 D_v = (V + Σ Sv_i × kv) × (1 + bv × C)）")
                .defineInRange("transferDiscount", ModularCellConstants.TRANSFER_DISCOUNT, 0.0D, 1.0D);
        TRANSFER_COUNT_BONUS = builder
                .comment("速率侧的数量加成 bv")
                .defineInRange("transferCountBonus", ModularCellConstants.TRANSFER_COUNT_BONUS, 0.0D, 100.0D);
        INPUT_RATIO = builder
                .comment("输入比例 f_in：整段输入上限 = 总速率 × f_in，再被各块输入上限之和夹住")
                .defineInRange("inputRatio", ModularCellConstants.INPUT_RATIO, 0.0D, 1.0D);
        builder.pop();

        COMPONENT_GAINS = builder
                .comment("额外认作存储组件的物品。每行一条，语法 <物品 id>=<容量值>:<速率值>，两个值都可以省略。",
                        "例：\"somemod:cap_only=4096\"（只加容量）、\"othermod:rate_only=0:64\"（只加速率）、",
                        "\"thirdmod:both=1024:16\"（两样都加）。这里写的条目优先于内置识别——AE2 与 MEGA Cells",
                        "走公开接口，AppFlux 走注册名。条目写错只会被跳过并在日志里报一行，不影响其他条目。")
                .defineListAllowEmpty("componentGains", List.of(), () -> "", element -> element instanceof String);
        SPEC = builder.build();
    }

    private ModularCellConfig() {
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
     * 配置加载：重建解析结果。
     *
     * <p><b>为什么分挂 Loading / Reloading 两个具体子类，而不是抽象父类 {@link ModConfigEvent}</b>：父类会
     * 不会被派发没有本地证据，挂错了的后果是 {@code componentGains} 静默失效（五个系数不受影响，它们是现读
     * 的，但那种失效一句话也不会报）。AE2 在同一个 NeoForge 版本上就是分挂两个子类
     * （{@code appeng.core.AEConfig} 的两处 {@code addListener}）。</p>
     *
     * <p>重建放在事件里而不是每次读都解析：条目是字符串，解析要查注册表，而这表会被识别路径逐槽命中，
     * 放在读取路径上太贵。</p>
     */
    public static void onConfigLoading(ModConfigEvent.Loading event) {
        refresh(event);
    }

    /** 配置重载（改了文件后重启或 {@code /reload}）：重建解析结果。 */
    public static void onConfigReloading(ModConfigEvent.Reloading event) {
        refresh(event);
    }

    private static void refresh(ModConfigEvent event) {
        if (event.getConfig().getSpec() != SPEC) {
            return;
        }
        Map<ResourceLocation, ComponentGain> parsed = new HashMap<>();
        for (String entry : COMPONENT_GAINS.get()) {
            var parsedEntry = parseEntry(entry);
            if (parsedEntry == null) {
                continue;
            }
            parsed.put(parsedEntry.id(), parsedEntry.gain());
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
