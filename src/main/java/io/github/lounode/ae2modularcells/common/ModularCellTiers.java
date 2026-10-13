package io.github.lounode.ae2modularcells.common;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import io.github.lounode.ae2modularcells.AE2ModularCells;

/**
 * 模块等级的注册表：本模组内置三档，其他模组可以注册自己的。
 *
 * <p><b>这是给别的模组用的扩展点</b>：{@link EnergyCellTier} 是注册表元素而不是枚举，所以第三方能这样接入——
 * 自建一个 {@code DeferredRegister}（用 {@link #TIER_REGISTRY_KEY} 作键、自己的 mod id 作命名空间），注册
 * 自己的等级；方块与方块实体都吃任意等级。配套的方块注册见 {@code ModularCellsApi}。</p>
 *
 * <p><b>位序是存档协议</b>：{@code StructureStats.tierMask} 是「第 N 位为 1 表示出现过第 N 档」的位掩码，而
 * 那个 N 就是等级在注册表里的<b>整数 id</b>（由注册顺序决定）。本模组的三档因此必须保持最先注册——{@code basic}
 * 在第 0 位、{@code dense} 第 1 位、{@code superdense} 第 2 位。改动这个顺序会让已有存档里的混拼结构认错档。
 * 掩码是 {@code int}，所以<b>等级总数上限是 32</b>（{@link #bitOf} 会挡住越界而不是产出错误的位）。</p>
 *
 * <p>不做 ID 同步（{@code sync(false)}）：两端各自加载同一批模组、按同一顺序注册，因此 id 天然一致；而等级
 * 本身也不走网络（客户端从方块取等级，见 {@code EnergyCellModuleBlockEntity}）。</p>
 */
public final class ModularCellTiers {

    /** 注册表的键。第三方注册自己的等级时用它作为 {@code DeferredRegister} 的键。 */
    public static final ResourceKey<Registry<EnergyCellTier>> TIER_REGISTRY_KEY =
            ResourceKey.createRegistryKey(ResourceLocation.fromNamespaceAndPath(AE2ModularCells.MOD_ID, "energy_cell_tier"));

    /**
     * 等级元素的注册器。
     *
     * <p>顺序要求：{@link #REGISTRY} 的静态初始化必须早于 {@link #register(IEventBus)}（NeoForge 要求
     * {@code makeRegistry} 先于 {@code register}），本类的字段声明顺序就是为此排的。</p>
     */
    public static final DeferredRegister<EnergyCellTier> TIERS =
            DeferredRegister.create(TIER_REGISTRY_KEY, AE2ModularCells.MOD_ID);

    /** 注册表本体。 */
    public static final Registry<EnergyCellTier> REGISTRY = TIERS.makeRegistry(builder -> builder.sync(false));

    // ---- 内置三档 ----
    // 数值与顺序都是既有平衡的一部分：顺序见类 javadoc（存档协议），数值见 docs/TODO.md 的平衡表。
    // id 写成常量，因为注册名和实例字段必须是同一个值：注册发生在本类的静态初始化里，那一刻注册表还不存在，
    // 除了自己写的常量什么都读不到（连 DeferredHolder.getId() 都会抛「Registry not present」）。

    /** 基础档的注册名。 */
    public static final String BASIC_ID = "basic";
    /** 致密档的注册名。 */
    public static final String DENSE_ID = "dense";
    /** 超密档的注册名。 */
    public static final String SUPERDENSE_ID = "superdense";

    /*
     * 三个实例在静态初始化时就构造好，注册时登记的也是同一批实例。
     *
     * 不写成 DeferredRegister 的 lambda：RegisterEvent 是「按注册表逐个」触发的，方块注册那一刻等级可能还没
     * 登记，此时 DeferredHolder.get() 拿到的是未绑定值（实测报「Trying to access unbound value」）。等级本身
     * 是不可变数据、构造无副作用，提前建好最省事。
     */

    private static final EnergyCellTier BASIC_INSTANCE =
            new EnergyCellTier(BASIC_ID, 200_000L, 1_000L, 1, 16_000L, 8_000L);
    private static final EnergyCellTier DENSE_INSTANCE =
            new EnergyCellTier(DENSE_ID, 1_600_000L, 8_000L, 2, 128_000L, 64_000L);
    private static final EnergyCellTier SUPERDENSE_INSTANCE =
            new EnergyCellTier(SUPERDENSE_ID, 12_800_000L, 64_000L, 3, 1_024_000L, 512_000L);

    /** 模块化基础能量单元：200 kAE / 块，1 个组件槽位。 */
    public static final DeferredHolder<EnergyCellTier, EnergyCellTier> BASIC =
            TIERS.register(BASIC_ID, () -> BASIC_INSTANCE);

    /** 模块化致密能量单元：1.6 MAE / 块，2 个组件槽位。 */
    public static final DeferredHolder<EnergyCellTier, EnergyCellTier> DENSE =
            TIERS.register(DENSE_ID, () -> DENSE_INSTANCE);

    /** 模块化超密能量单元：12.8 MAE / 块，3 个组件槽位。 */
    public static final DeferredHolder<EnergyCellTier, EnergyCellTier> SUPERDENSE =
            TIERS.register(SUPERDENSE_ID, () -> SUPERDENSE_INSTANCE);

    private ModularCellTiers() {
    }

    /** 把等级注册器挂到模组总线上；由 {@code AEModularCellsRegistries#register} 调用。 */
    public static void register(IEventBus modBus) {
        TIERS.register(modBus);
    }

    /**
     * 所有等级，按注册顺序（= {@code tierMask} 的位序）。
     *
     * <p>每次调用都排一次序而不缓存：等级只有个位数到几十个，一次排序是微秒级；而缓存要处理「注册表在
     * {@code NewRegistryEvent} 之前是空的」这个时序陷阱，容易缓存出一张空表。</p>
     */
    public static List<EnergyCellTier> all() {
        return REGISTRY.stream()
                .sorted(Comparator.comparingInt(REGISTRY::getId))
                .toList();
    }

    /**
     * 本模组内置三档的「注册名 → 等级实例」表，按声明顺序（{@code basic} → {@code dense} → {@code superdense}）。
     *
     * <p>给本模组的注册代码用：它在类加载时就要按等级名建映射，而那一刻等级注册表还是空的（填充发生在
     * {@code RegisterEvent}），连 {@code DeferredHolder.get()} 都拿不到值。这里给的是静态构造好的同一批实例，
     * 注册前后都可用。</p>
     */
    public static Map<String, EnergyCellTier> builtIn() {
        Map<String, EnergyCellTier> map = new LinkedHashMap<>();
        map.put(BASIC_ID, BASIC_INSTANCE);
        map.put(DENSE_ID, DENSE_INSTANCE);
        map.put(SUPERDENSE_ID, SUPERDENSE_INSTANCE);
        return map;
    }

    /** 等级的短 id（注册名的路径部分），例如 {@code basic}。取自实例自己的字段，注册前后都可用。 */
    public static String idOf(EnergyCellTier tier) {
        return tier.id();
    }

    /** 等级的完整注册名，例如 {@code ae2modularcells:basic}；不在注册表里给 {@code null}。 */
    @Nullable
    public static ResourceLocation registryNameOf(EnergyCellTier tier) {
        return REGISTRY.getKey(tier);
    }

    /** 等级在注册表里的序号，也就是 {@code tierMask} 的位号。 */
    public static int indexOf(EnergyCellTier tier) {
        return REGISTRY.getId(tier);
    }

    /**
     * 这个等级在 {@code tierMask} 里占的那一位。
     *
     * <p>越界（第 32 档及以后）直接抛而不是算出一个错误的位：掩码是 {@code int}，静默移位会把它折回来，症状是
     * 「两个不同等级共用一个位」，那比启动期报错难查得多。</p>
     */
    public static int bitOf(EnergyCellTier tier) {
        int index = indexOf(tier);
        if (index < 0 || index >= Integer.SIZE) {
            throw new IllegalStateException(
                    "Energy cell tier " + registryNameOf(tier) + " has id " + index
                            + ", but the tier mask can only hold " + Integer.SIZE + " tiers");
        }
        return 1 << index;
    }

    /**
     * 按短 id 找等级；找不到返回 {@code null}。
     *
     * <p>存档里出现陌生等级（模组版本回退、存档来自别的分支）时用它兜底——调用方要按「这一段不成立」处理，
     * 不要抛异常：那会把一个方块的问题放大成整段结构的加载失败。</p>
     */
    @Nullable
    public static EnergyCellTier byId(@Nullable String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        for (var tier : all()) {
            if (idOf(tier).equals(id)) {
                return tier;
            }
        }
        return null;
    }
}
