package io.github.lounode.ae2modularcells.common.config;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import net.mezzdev.config.api.value.serializer.IConfigValueSerializer;
import net.mezzdev.config.api.value.serializer.IDeserializeResult;

import io.github.lounode.ae2modularcells.common.ComponentSpec;

/**
 * 「存储组件清单」条目的序列化器：一行文本 ↔ 一个 {@link ComponentSpec}。
 *
 * <p><b>为什么是普通单值序列化器，而不是 {@code IConfigKeyValueSerializer}</b>：那种接口会把一条渲染成
 * 「键格 + 值格」两列，每格只占行宽的<b>一半</b>（MezzConfig 的列表布局把行宽写死二分）。一条组件的文本形有
 * 四个参数，半行放不下会被截断成 {@code ae2omnicells:omni_cell_component...}，看不出是哪一档。用单值序列化器
 * 则整条占满一行，四个参数都能显示。</p>
 *
 * <p><b>文本形</b>：{@code <物品 id>=<容量值>:<速率值>:<容量倍率>:<速率倍率>}。后两段可以省略（省略即
 * {@code 1.0}），但<b>写回时一律补齐四段</b>——这样界面上四个参数都看得见，改倍率不必先去数冒号。整数倍率写成
 * 整数（{@code 1} 而不是 {@code 1.0}）：省 4 个字符，正是大 GUI 缩放下「放得下 / 被截断」的那点余量，而且往返
 * 仍然严格（{@code parseDouble("1") == 1.0}，再写出还是 {@code "1"}）。旧配置里的 {@code mod:item=1024:0}
 * 原样可读，只是在下次保存时被规范化成四段（数值不变）。</p>
 */
final class ComponentSpecSerializer implements IConfigValueSerializer<ComponentSpec> {

    static final ComponentSpecSerializer INSTANCE = new ComponentSpecSerializer();

    /** 语言键前缀：schema 的 langKey + 分类 id + 项名。 */
    private static final String KEY = "ae2modularcells.config.cells.components.componentGains";

    /** 倍率不修正时的取值。 */
    private static final double NO_MODIFIER = 1.0D;

    /** 整数值转 long 时的上界——超过它就别转，免得溢出成无意义的值。 */
    private static final double INTEGER_FORMAT_LIMIT = 1.0E15D;

    /**
     * 注册表是否已经填好。
     *
     * <p>不能用「{@code BuiltInRegistries.ITEM} 非空」当判据：原版内容在<b>模组构造期就已经注入</b>，那个集合
     * 一开局就是非空的，于是「构造期放行」等于没写；而那一刻模组物品（AppFlux / MEGA 那些）还没注册，会把
     * 合法条目一律报成「物品不存在」。所以由服务器启动时显式置位。</p>
     */
    private static volatile boolean registryReady;

    /** 服务器启动后置位；由 {@code ModularCellConfig} 转发过来。 */
    static void markRegistryReady() {
        registryReady = true;
    }

    private ComponentSpecSerializer() {
    }

    @Override
    public String serialize(ComponentSpec spec) {
        // 界面「新增一行」时可能拿到空元素，序列化这一侧不能炸。
        if (spec == null) {
            return "";
        }
        // 四段一律写全：这是「四个参数在界面上都看得见」的那一半，另一半是整条占满一行。
        return spec.id() + "=" + spec.capacity() + ":" + spec.transfer()
                + ":" + formatMultiplier(spec.capacityMultiplier())
                + ":" + formatMultiplier(spec.transferMultiplier());
    }

    @Override
    public IDeserializeResult<ComponentSpec> deserialize(String text) {
        if (text == null || text.isBlank()) {
            return IDeserializeResult.failure(localize(KEY + ".error.empty"));
        }
        String trimmed = text.trim();
        int equals = trimmed.indexOf('=');
        // 只写 id（没有 '='）合法：两个值都当 0，等于声明「这个物品算组件、但不贡献任何东西」。
        String idText = equals < 0 ? trimmed : trimmed.substring(0, equals).trim();
        String valuesText = equals < 0 ? "" : trimmed.substring(equals + 1).trim();

        if (idText.isEmpty()) {
            return IDeserializeResult.failure(localize(KEY + ".error.empty"));
        }
        // 先查命名空间：`cell_component_1k` 这种漏写会被 tryParse 补成 minecraft:cell_component_1k，
        // 而它当然不在注册表里，于是错误会被报成「物品不存在」——把最容易踩的坑指向错误的原因。
        if (idText.indexOf(':') < 0) {
            return IDeserializeResult.failure(localize(KEY + ".error.badId"));
        }
        var id = ResourceLocation.tryParse(idText);
        if (id == null) {
            return IDeserializeResult.failure(localize(KEY + ".error.badId"));
        }

        var diagnostics = new ArrayList<String>();
        String[] parts = valuesText.split(":", -1);
        if (parts.length > 4) {
            diagnostics.add(localize(KEY + ".error.tooManyValues"));
        }
        long capacity = parseLong(parts, 0, diagnostics);
        long transfer = parseLong(parts, 1, diagnostics);
        double capacityMultiplier = parseMultiplier(parts, 2, diagnostics);
        double transferMultiplier = parseMultiplier(parts, 3, diagnostics);
        var spec = new ComponentSpec(id, capacity, transfer, capacityMultiplier, transferMultiplier);

        if (!registryReady || BuiltInRegistries.ITEM.containsKey(id)) {
            return diagnostics.isEmpty()
                    ? IDeserializeResult.success(spec)
                    : IDeserializeResult.partialSuccess(spec, diagnostics);
        }
        // 物品不存在只是「可疑」而不是「语法错」：邻居没装、或将来才加的物品都长得这样，
        // 所以给 partialSuccess 带诊断，让人看见原因但条目仍然存得下。
        diagnostics.add(localize(KEY + ".warn.unknownItem", id.toString()));
        return IDeserializeResult.partialSuccess(spec, diagnostics);
    }

    @Override
    public boolean isValid(ComponentSpec spec) {
        // null 防御性放行（不对空参数造噪音）。数值面与 deserialize 的口径保持一致：它会把负值夹成 0、
        // 把非有限倍率回落成 1.0，所以这里只拒绝「本来就存不了」的值。
        if (spec == null) {
            return true;
        }
        if (registryReady && !BuiltInRegistries.ITEM.containsKey(spec.id())) {
            return false;
        }
        return spec.capacity() >= 0L
                && spec.transfer() >= 0L
                && isUsableMultiplier(spec.capacityMultiplier())
                && isUsableMultiplier(spec.transferMultiplier());
    }

    @Override
    public String getValidValuesDescription() {
        return localize(KEY + ".syntax");
    }

    private static boolean isUsableMultiplier(double value) {
        return Double.isFinite(value) && value >= 0.0D;
    }

    /**
     * 倍率的文本形：整数写成整数，其余交给 {@link Double#toString}。
     *
     * <p>省掉 {@code .0} 是为了宽度——最长的一条内置条目写全四段是 65 个字符，在大 GUI 缩放下正好处在
     * 「放得下 / 被截断」的边界上，省 4 个字符约 24px 就是那点余量。往返不受影响：{@code "1"} 解析回
     * {@code 1.0}，再写出还是 {@code "1"}。</p>
     */
    static String formatMultiplier(double value) {
        if (value == Math.floor(value) && Math.abs(value) < INTEGER_FORMAT_LIMIT) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    // ---- 解析小工具：越界段按默认值算，坏数据只记诊断不抛 ----

    /** 取第 {@code index} 段当 long；缺失给 0，写错或为负记诊断并给 0。 */
    private static long parseLong(String[] parts, int index, List<String> diagnostics) {
        if (index >= parts.length || parts[index].isBlank()) {
            return 0L;
        }
        String text = parts[index].trim();
        try {
            long parsed = Long.parseLong(text);
            if (parsed < 0L) {
                // 明说按 0 算：静默夹取会让「我明明写了 -1」变成一个查不出来的谜。
                diagnostics.add(localize(KEY + ".error.negativeValue", text));
                return 0L;
            }
            return parsed;
        } catch (NumberFormatException e) {
            diagnostics.add(localize(KEY + ".error.badValue", text));
            return 0L;
        }
    }

    /** 取第 {@code index} 段当倍率；缺失给 1.0（不修正），写错、为负或非有限值记诊断并给 1.0。 */
    private static double parseMultiplier(String[] parts, int index, List<String> diagnostics) {
        if (index >= parts.length || parts[index].isBlank()) {
            return NO_MODIFIER;
        }
        String text = parts[index].trim();
        try {
            double value = Double.parseDouble(text);
            // NaN / Infinity 会一路乘进公式：NaN 让整块模块容量变 0，Infinity 直接顶到 Long.MAX_VALUE。
            // 这不是「坏数据不参与计算」能兜住的，必须在入口挡住。
            if (!Double.isFinite(value)) {
                diagnostics.add(localize(KEY + ".error.badMultiplier", text));
                return NO_MODIFIER;
            }
            if (value < 0.0D) {
                diagnostics.add(localize(KEY + ".error.negativeValue", text));
                return NO_MODIFIER;
            }
            return value;
        } catch (NumberFormatException e) {
            diagnostics.add(localize(KEY + ".error.badMultiplier", text));
            return NO_MODIFIER;
        }
    }

    private static String localize(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }
}
