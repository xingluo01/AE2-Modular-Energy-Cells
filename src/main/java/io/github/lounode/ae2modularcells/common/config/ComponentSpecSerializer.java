package io.github.lounode.ae2modularcells.common.config;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import net.mezzdev.config.api.value.serializer.IConfigValueSerializer;
import net.mezzdev.config.api.value.serializer.IDeserializeResult;

/**
 * 「额外组件」条目的序列化器：一行文本 ↔ 一个 {@link ComponentSpec}。
 *
 * <p>存在的意义不是换语法（文本形与旧的字符串列表<b>逐字兼容</b>，旧配置文件原样可读），而是把校验挂到
 * 类型上：MezzConfig 的界面拿 {@link #isValid} 实时判断每行能不能用、拿 {@link #deserialize} 的诊断把
 * 原因写在那一行旁边。只存字符串时这些判断只能推迟到运行期，而那时错误已经静默发生了——写错物品名照样
 * 被接收，直到真往槽里插才发现认不出。</p>
 *
 * <p>文本形：{@code <物品 id>=<容量值>:<速率值>}。两个值都可以省略（省略即 0），也可以只给一个
 * （写 {@code =1024} 或 {@code =0:16}）。id 必须带命名空间——不带会被当成 {@code minecraft:xxx}。</p>
 *
 * <p>用户可见的文本（语法说明与诊断）一律走语言键，不在代码里写死；{@code getValidValuesDescription()}
 * 与诊断都是 String 接口，所以这里用 {@link Component#getString()} 取当前语言下的译文。</p>
 */
final class ComponentSpecSerializer implements IConfigValueSerializer<ComponentSpec> {

    static final ComponentSpecSerializer INSTANCE = new ComponentSpecSerializer();

    /** 语言键前缀：schema 的 langKey + 分类 id + 项名。 */
    private static final String KEY = "ae2modularcells.config.cells.components.componentGains";

    /**
     * 注册表是否已经填好。
     *
     * <p>不能用「{@code BuiltInRegistries.ITEM} 非空」当判据：原版内容在<b>模组构造期就已经注入</b>，那个集合
     * 一开局就是非空的，于是「构造期放行」等于没写；而那一刻模组物品（AppFlux / MEGA 那些）还没注册，会把
     * 合法条目一律报成「物品不存在」。所以由 {@code ModularCellConfig#markRegistryReady()} 在注册完成后
     * （{@code FMLCommonSetupEvent}）显式置位。</p>
     */
    private static volatile boolean registryReady;

    /** 注册完成后置位；由 {@code ModularCellConfig} 从模组总线转发过来。 */
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

        ResourceLocation id = ResourceLocation.tryParse(idText);
        if (id == null) {
            return IDeserializeResult.failure(localize(KEY + ".error.badId"));
        }

        long capacity = 0L;
        long transfer = 0L;
        if (equals >= 0) {
            String[] values = trimmed.substring(equals + 1).trim().split(":", -1);
            if (values.length > 2) {
                // 多写的值丢掉，但 id 与能认出的部分保留：条目语法大体不差，不该整条失效。
                var spec = new ComponentSpec(id, parseValueOr0(values[0]), parseValueOr0(values[1]));
                return IDeserializeResult.partialSuccess(spec, localize(KEY + ".error.tooManyValues"));
            }
            Long parsedCapacity = parseValue(values[0]);
            if (parsedCapacity == null) {
                // 值写错不等于条目无效：给 0 并带诊断，保留 id 让它仍然看得见。
                var spec = new ComponentSpec(id, 0L, parseValueOr0(values.length > 1 ? values[1] : ""));
                return IDeserializeResult.partialSuccess(spec, localize(KEY + ".error.badValue", values[0]));
            }
            capacity = parsedCapacity;
            if (values.length > 1) {
                Long parsedTransfer = parseValue(values[1]);
                if (parsedTransfer == null) {
                    var spec = new ComponentSpec(id, capacity, 0L);
                    return IDeserializeResult.partialSuccess(spec, localize(KEY + ".error.badValue", values[1]));
                }
                transfer = parsedTransfer;
            }
        }

        var spec = new ComponentSpec(id, capacity, transfer);
        // 物品不存在只是「可疑」而不是「语法错」：邻居没装、或将来才加的物品都长得这样，
        // 所以给 partialSuccess 带诊断，让人看见原因但条目仍然存得下。
        if (!itemExists(id)) {
            return IDeserializeResult.partialSuccess(spec, localize(KEY + ".warn.unknownItem", id.toString()));
        }
        if (spec.isEmpty()) {
            // 两个值都是 0：它不贡献任何东西，却会因为「命中配置层」把内置识别挡掉。这一条最容易被误写。
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

    /** 物品是否存在；注册表未就绪时一律当作「存在」，不产生诊断。 */
    private static boolean itemExists(ResourceLocation id) {
        return !registryReady || BuiltInRegistries.ITEM.containsKey(id);
    }

    private static String localize(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }
}
