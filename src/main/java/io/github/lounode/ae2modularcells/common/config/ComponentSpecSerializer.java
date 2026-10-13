package io.github.lounode.ae2modularcells.common.config;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import net.mezzdev.config.api.value.serializer.IConfigKeyValueSerializer;
import net.mezzdev.config.api.value.serializer.IConfigValueSerializer;
import net.mezzdev.config.api.value.serializer.IDeserializeResult;

import io.github.lounode.ae2modularcells.common.ComponentSpec;

/**
 * 「存储组件清单」条目的序列化器：一行文本 ↔ 一个 {@link ComponentSpec}。
 *
 * <p><b>为什么实现 {@code IConfigKeyValueSerializer} 而不是基础的 {@code IConfigValueSerializer}</b>：
 * 这个接口把一个元素拆成「键」与「值」两半，配置界面会把它们渲染成<b>两列</b>分别编辑——左列是物品 id、
 * 右列是那两个数字。只实现基础接口的话，整条会挤成一串长文本，而
 * {@code ae2omnicells:quantum_omni_cell_component_256m=268435456:0} 有 50 个字符，在列表里会被截断成
 * 「ae2omnicells:omni_cell_component...」，看不出是哪个档。</p>
 *
 * <p>文本形仍然是 {@code <物品 id>=<容量值>:<速率值>}，与写入配置文件的形式一致，所以旧文件原样可读。
 * 两个值都可以省略（省略即 0）。id 必须带命名空间——不带会被当成 {@code minecraft:xxx}。</p>
 *
 * <p>用户可见的文本（语法说明与诊断）一律走语言键，不在代码里写死；{@code getValidValuesDescription()}
 * 与诊断都是 String 接口，所以这里用 {@link Component#getString()} 取当前语言下的译文。</p>
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
     * 合法条目一律报成「物品不存在」。所以由 {@code ModularCellConfig#onServerStarted()} 显式置位。</p>
     */
    private static volatile boolean registryReady;

    /** 服务器启动后置位；由 {@code ModularCellConfig} 转发。 */
    static void markRegistryReady() {
        registryReady = true;
    }

    private ComponentSpecSerializer() {
    }

    // ---- 键：物品 id ----

    @Override
    public IConfigValueSerializer<ResourceLocation> getKeySerializer() {
        return ItemIdSerializer.INSTANCE;
    }

    @Override
    public ResourceLocation getKey(ComponentSpec spec) {
        return spec.id();
    }

    // ---- 值：容量与速率 ----

    @Override
    public IConfigValueSerializer<ComponentValues> getValueSerializer() {
        return ValuesSerializer.INSTANCE;
    }

    @Override
    public ComponentValues getValue(ComponentSpec spec) {
        return new ComponentValues(spec.capacity(), spec.transfer());
    }

    @Override
    public ComponentSpec createEntry(ResourceLocation id, ComponentValues values) {
        return new ComponentSpec(id, values.capacity(), values.transfer());
    }

    // ---- 整条：文本形与配置文件的写法一致 ----

    @Override
    public String serialize(ComponentSpec spec) {
        // 界面「新增一行」时可能拿到空元素，序列化这一侧不能炸。
        if (spec == null) {
            return "";
        }
        return spec.id() + "=" + spec.capacity() + ":" + spec.transfer();
    }

    @Override
    public IDeserializeResult<ComponentSpec> deserialize(String text) {
        if (text == null || text.isBlank()) {
            return IDeserializeResult.failure(localize(KEY + ".error.empty"));
        }
        String trimmed = text.trim();
        int equals = trimmed.indexOf('=');
        String idText = equals < 0 ? trimmed : trimmed.substring(0, equals).trim();

        IDeserializeResult<ResourceLocation> parsedId = ItemIdSerializer.INSTANCE.deserialize(idText);
        var id = parsedId.getResult().orElse(null);
        if (id == null) {
            return IDeserializeResult.failure(parsedId.getDiagnostics());
        }
        if (equals < 0) {
            return IDeserializeResult.success(new ComponentSpec(id, 0L, 0L));
        }

        IDeserializeResult<ComponentValues> parsedValues =
                ValuesSerializer.INSTANCE.deserialize(trimmed.substring(equals + 1));
        var values = parsedValues.getResult().orElse(null);
        if (values == null) {
            // 值写错不等于条目无效：给 0 并带诊断，保留 id 让它仍然看得见。
            var spec = new ComponentSpec(id, 0L, 0L);
            return IDeserializeResult.partialSuccess(spec, parsedValues.getDiagnostics());
        }

        var spec = new ComponentSpec(id, values.capacity(), values.transfer());
        // 物品不存在只是「可疑」而不是「语法错」：邻居没装、或将来才加的物品都长得这样，
        // 所以给 partialSuccess 带诊断，让人看见原因但条目仍然存得下。
        if (!itemExists(id)) {
            return IDeserializeResult.partialSuccess(spec, localize(KEY + ".warn.unknownItem", id.toString()));
        }
        if (spec.isEmpty()) {
            // 两个值都是 0：它不贡献任何东西，却会把内置识别挡掉（识别只看这张表）。这一条最容易被误写。
            return IDeserializeResult.partialSuccess(spec, localize(KEY + ".warn.disablesItem", id.toString()));
        }
        return IDeserializeResult.success(spec);
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

    /** 物品是否存在；注册表未就绪时一律当作「存在」，不产生诊断。 */
    private static boolean itemExists(ResourceLocation id) {
        return !registryReady || BuiltInRegistries.ITEM.containsKey(id);
    }

    private static String localize(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    /** 键列：物品 id 本身。 */
    private static final class ItemIdSerializer implements IConfigValueSerializer<ResourceLocation> {

        static final ItemIdSerializer INSTANCE = new ItemIdSerializer();

        @Override
        public String serialize(ResourceLocation id) {
            return id == null ? "" : id.toString();
        }

        @Override
        public IDeserializeResult<ResourceLocation> deserialize(String text) {
            ResourceLocation id = text == null ? null : ResourceLocation.tryParse(text.trim());
            return id != null ? IDeserializeResult.success(id) : IDeserializeResult.failure(
                    localize(KEY + ".error.badId"));
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

    /** 值列：{@code 容量:速率}，两个值都可以省略。 */
    private static final class ValuesSerializer implements IConfigValueSerializer<ComponentValues> {

        static final ValuesSerializer INSTANCE = new ValuesSerializer();

        @Override
        public String serialize(ComponentValues values) {
            return values == null ? "" : values.toString();
        }

        @Override
        public IDeserializeResult<ComponentValues> deserialize(String text) {
            String trimmed = text == null ? "" : text.trim();
            String[] parts = trimmed.split(":", -1);
            if (parts.length > 2) {
                return IDeserializeResult.partialSuccess(
                        new ComponentValues(parseValueOr0(parts[0]), parseValueOr0(parts[1])),
                        localize(KEY + ".error.tooManyValues"));
            }
            Long capacity = parseValue(parts[0]);
            if (capacity == null) {
                return IDeserializeResult.partialSuccess(new ComponentValues(0L, 0L),
                        localize(KEY + ".error.badValue", parts[0]));
            }
            long transfer = 0L;
            if (parts.length > 1) {
                Long parsedTransfer = parseValue(parts[1]);
                if (parsedTransfer == null) {
                    return IDeserializeResult.partialSuccess(new ComponentValues(capacity, 0L),
                            localize(KEY + ".error.badValue", parts[1]));
                }
                transfer = parsedTransfer;
            }
            return IDeserializeResult.success(new ComponentValues(capacity, transfer));
        }

        @Override
        public boolean isValid(ComponentValues values) {
            return true;
        }

        @Override
        public String getValidValuesDescription() {
            return localize(KEY + ".syntax.values");
        }
    }

    /** 解析一个数值：空串当 0；不是数字给 {@code null}（由调用方转成诊断）。 */
    private static Long parseValue(String text) {
        if (text == null || text.isBlank()) {
            return 0L;
        }
        try {
            return Math.max(0L, Long.parseLong(text.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 解析一个数值，失败当 0——用于「已经决定要保留条目、只是记一条诊断」的场合。 */
    private static long parseValueOr0(String text) {
        Long value = parseValue(text);
        return value == null ? 0L : value;
    }
}
