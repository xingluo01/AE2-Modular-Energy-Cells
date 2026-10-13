package io.github.lounode.ae2modularcells.common.config;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import net.mezzdev.config.api.value.serializer.IConfigKeyValueSerializer;
import net.mezzdev.config.api.value.serializer.IConfigValueSerializer;
import net.mezzdev.config.api.value.serializer.IDeserializeResult;

import io.github.lounode.ae2modularcells.common.ComponentSpec;

/**
 * {@code componentGains} 的序列化器：一条 = 物品 id（键）+ 值与倍率（值）。
 *
 * <p>实现的是 {@link IConfigKeyValueSerializer} 而不是普通的单值序列化器——这样配置界面会把一条渲染成
 * <b>两列</b>（左 id、右参数），而不是把整条挤成一行长文本后截断。对应的语义正是「键是那个组件、值是它的衍生
 * 数据」。</p>
 *
 * <p><b>文本形</b>：{@code <物品 id>=<容量值>:<速率值>[:<容量倍率>[:<速率倍率>]]}，从尾部可以省略。倍率是
 * {@code 1.0} 时一律省掉，所以不带修正的条目写出来与加倍率之前<b>逐字一致</b>，旧配置文件原样可读。</p>
 *
 * <p><b>校验与诊断</b>：字符串解析与「物品是否存在」的检查都走这里，界面会直接用同一条路径，于是写错的条目
 * 在界面上就会被标出来并给出原因，而不是等运行期静默失效。注册表未就绪时跳过存在性检查（见
 * {@link #markRegistryReady()}）。</p>
 */
final class ComponentSpecSerializer implements IConfigKeyValueSerializer<ComponentSpec, ResourceLocation, ComponentValues> {

    static final ComponentSpecSerializer INSTANCE = new ComponentSpecSerializer();

    /** 语言键前缀：schema 的 langKey + 分类 id + 项名。 */
    private static final String KEY = "ae2modularcells.config.cells.components.componentGains";

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

    // ---- 键：物品 id ----

    @Override
    public IConfigValueSerializer<ResourceLocation> getKeySerializer() {
        return KeySerializer.INSTANCE;
    }

    @Override
    public ResourceLocation getKey(ComponentSpec spec) {
        return spec.id();
    }

    @Override
    public ComponentSpec createEntry(ResourceLocation id, ComponentValues values) {
        return new ComponentSpec(id, values.capacity(), values.transfer(),
                values.capacityMultiplier(), values.transferMultiplier());
    }

    // ---- 值：两个维度 + 两个倍率 ----

    @Override
    public IConfigValueSerializer<ComponentValues> getValueSerializer() {
        return ValueSerializer.INSTANCE;
    }

    @Override
    public ComponentValues getValue(ComponentSpec spec) {
        return new ComponentValues(spec.capacity(), spec.transfer(),
                spec.capacityMultiplier(), spec.transferMultiplier());
    }

    // ---- 整条 ----

    @Override
    public String serialize(ComponentSpec spec) {
        // 界面「新增一行」时可能拿到空元素，序列化这一侧不能炸。
        if (spec == null) {
            return "";
        }
        return spec.id() + "=" + getValue(spec);
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

        var key = KeySerializer.INSTANCE.deserialize(idText);
        var id = key.getResult().orElse(null);
        if (id == null) {
            return IDeserializeResult.failure(key.getDiagnostics());
        }
        var values = ValueSerializer.INSTANCE.deserialize(valuesText);
        var parsedValues = values.getResult().orElse(new ComponentValues(0L, 0L,
                ComponentValues.NO_MODIFIER, ComponentValues.NO_MODIFIER));
        var spec = createEntry(id, parsedValues);

        var diagnostics = new java.util.ArrayList<String>(values.getDiagnostics());
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
        // null 防御性放行（不对空参数造噪音）；注册表未就绪时不判存在性，否则会把还没注册的模组物品误报。
        return spec == null || !registryReady || BuiltInRegistries.ITEM.containsKey(spec.id());
    }

    @Override
    public String getValidValuesDescription() {
        return localize(KEY + ".syntax");
    }

    // ---- 两列的序列化器 ----

    /** 键列：物品 id。 */
    private static final class KeySerializer implements IConfigValueSerializer<ResourceLocation> {

        static final KeySerializer INSTANCE = new KeySerializer();

        @Override
        public String serialize(ResourceLocation id) {
            return id == null ? "" : id.toString();
        }

        @Override
        public IDeserializeResult<ResourceLocation> deserialize(String text) {
            if (text == null || text.isBlank()) {
                return IDeserializeResult.failure(localize(KEY + ".error.empty"));
            }
            var id = ResourceLocation.tryParse(text.trim());
            // 不带命名空间会被当成 minecraft:xxx，匹配不上任何东西——这属于最容易踩的坑，所以单独报。
            return id == null
                    ? IDeserializeResult.failure(localize(KEY + ".error.badId"))
                    : IDeserializeResult.success(id);
        }

        @Override
        public boolean isValid(ResourceLocation id) {
            return id == null || !registryReady || BuiltInRegistries.ITEM.containsKey(id);
        }

        @Override
        public String getValidValuesDescription() {
            return localize(KEY + ".syntax.id");
        }
    }

    /** 值列：`容量:速率[:容量倍率[:速率倍率]]`。 */
    private static final class ValueSerializer implements IConfigValueSerializer<ComponentValues> {

        static final ValueSerializer INSTANCE = new ValueSerializer();

        @Override
        public String serialize(ComponentValues values) {
            return values == null ? "" : values.toString();
        }

        @Override
        public IDeserializeResult<ComponentValues> deserialize(String text) {
            String trimmed = text == null ? "" : text.trim();
            if (trimmed.isEmpty()) {
                // 值列留空是合法写法：两个维度都是 0。
                return IDeserializeResult.success(new ComponentValues(0L, 0L,
                        ComponentValues.NO_MODIFIER, ComponentValues.NO_MODIFIER));
            }
            String[] parts = trimmed.split(":", -1);
            if (parts.length > 4) {
                return IDeserializeResult.partialSuccess(fromParts(parts), localize(KEY + ".error.tooManyValues"));
            }
            var diagnostics = new java.util.ArrayList<String>();
            for (int i = 0; i < Math.min(parts.length, 2); i++) {
                if (parseLong(parts[i]) == null) {
                    diagnostics.add(localize(KEY + ".error.badValue", parts[i]));
                }
            }
            for (int i = 2; i < parts.length; i++) {
                if (parseDouble(parts[i]) == null) {
                    diagnostics.add(localize(KEY + ".error.badMultiplier", parts[i]));
                }
            }
            var values = fromParts(parts);
            // 值与倍率全是默认值、且没写错时会走到这里：这是「把这个物品禁用」的写法，值得提醒一次。
            if (diagnostics.isEmpty() && values.toString().equals("0:0")) {
                return IDeserializeResult.partialSuccess(values, localize(KEY + ".warn.disablesItem"));
            }
            return diagnostics.isEmpty()
                    ? IDeserializeResult.success(values)
                    : IDeserializeResult.partialSuccess(values, diagnostics);
        }

        /** 解析失败的段按默认值算：条目保留下来、只附一条诊断，不让整行失效。 */
        private static ComponentValues fromParts(String[] parts) {
            long capacity = parts.length > 0 ? orZero(parseLong(parts[0])) : 0L;
            long transfer = parts.length > 1 ? orZero(parseLong(parts[1])) : 0L;
            double capacityMultiplier = parts.length > 2
                    ? orDefault(parseDouble(parts[2]))
                    : ComponentValues.NO_MODIFIER;
            double transferMultiplier = parts.length > 3
                    ? orDefault(parseDouble(parts[3]))
                    : ComponentValues.NO_MODIFIER;
            return new ComponentValues(capacity, transfer, capacityMultiplier, transferMultiplier);
        }

        @Override
        public boolean isValid(ComponentValues values) {
            return true;
        }

        @Override
        public String getValidValuesDescription() {
            return localize(KEY + ".syntax.values");
        }

        private static long orZero(Long value) {
            return value == null ? 0L : value;
        }

        private static double orDefault(Double value) {
            return value == null ? ComponentValues.NO_MODIFIER : value;
        }
    }

    // ---- 解析小工具 ----

    private static Long parseLong(String text) {
        if (text == null || text.isBlank()) {
            return 0L;
        }
        try {
            return Math.max(0L, Long.parseLong(text.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double parseDouble(String text) {
        if (text == null || text.isBlank()) {
            return ComponentValues.NO_MODIFIER;
        }
        try {
            double value = Double.parseDouble(text.trim());
            // NaN / Infinity 会一路乘进公式：NaN 让整块模块容量变 0，Infinity 直接顶到 Long.MAX_VALUE。
            // 这不是「坏数据不参与计算」能兜住的，必须在入口挡住。
            return Double.isFinite(value) ? Math.max(0.0D, value) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String localize(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }
}
