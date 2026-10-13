package io.github.lounode.ae2modularcells.common;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * <p><b>位序是存档协议，而位号是固定的</b>：{@code StructureStats.tierMask} 是「第 N 位为 1 表示出现过第 N 档」
 * 的位掩码。那个 N 是 {@link #indexOf} 给出的<b>固定位号</b>：内置三档写死 0/1/2（与它们曾经是枚举时的
 * {@code ordinal()} 一致，所以只装本模组的旧存档读回来仍认得对），其余等级从 3 起按注册名排序分配。
 *
 * <p><b>位号刻意不取注册表的整数 id</b>：那是「注册分配序」，由各模组的加载与事件触发次序决定，第三方先注册
 * 就会夺走 0/1/2，使本模组三档变成 3/4/5——旧存档的混拼结构全部认错档。位号只依赖「注册了哪几个等级」，
 * 因此同样的模组集合在任何一端都得到同样的位号。改动本类的三档声明顺序仍然是破坏性的：位号跟着变。
 * 掩码是 {@code int}，所以<b>等级总数上限是 32</b>（{@link #bitOf} 会挡住越界而不是产出错误的位）。</p>
 *
 * <p>不做 ID 同步（{@code sync(false)}）：等级本身不走网络（客户端从方块取等级，见
 * {@code EnergyCellModuleBlockEntity}），而按位号配对的同步（终端快照的单字符制）只要求两端注册同一批等级——
 * 满足这个前提就一致，与注册顺序无关。<b>等级因而是双端都要满足的注册表</b>：只在服务端装的等级，客户端解不出那一档。</p>
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

    /** 内置三档的个数：第三方等级的固定位号从它开始。 */
    private static final int BUILT_IN_COUNT = 3;

    private static final Logger LOGGER = LoggerFactory.getLogger(ModularCellTiers.class);

    /**
     * 启动期校验：把配置错误挡在启动阶段，而不是让它到运行期才爆。
     *
     * <p>三件事要在注册表填完之后确认：① 每个等级的注册名与它自称的 {@code id} 一致（否则语言键与方块名会用
     * 一个名字、而按注册名找不到，症状是 {@code byId} 命中但提示与同步对不上）；② id 在全局唯一（id 是存档与
     * 物品 id 的标识，两个等级共用一个 id 会让 {@code byId} 的结果取决于枚举顺序）；③ 固定位号落进 {@code int}
     * 掩码能装的位数内。</p>
     *
     * <p>前两项抛异常（那是模组写错了，fail-fast 比让存档认错档好）；第三项只记 ERROR——位号越界说明等级太多，
     * 属于「功能受限」而不是「数据会被写坏」，而且运行期的 {@link #bitOf} 已经会挡住它，不必把整个启动拦下来。</p>
     *
     * <p>调用时机：服务端启动（配置就绪、注册表已填）。客户端不单独校验——它的角色是按位号解码快照。
     */
    public static void validateRegistry() {
        Set<String> seenIds = new HashSet<>();
        for (var tier : all()) {
            var key = REGISTRY.getKey(tier);
            if (key == null || !key.getPath().equals(tier.id())) {
                throw new IllegalStateException("Energy cell tier registered as " + key
                        + " but reports id " + tier.id() + "; the two must match");
            }
            if (!seenIds.add(tier.id())) {
                throw new IllegalStateException("Energy cell tier id " + tier.id()
                        + " is used by more than one tier; ids identify tiers in saves, so they must be unique");
            }
            int index = indexOf(tier);
            if (index < 0 || index >= Integer.SIZE) {
                LOGGER.error("Energy cell tier {} resolved to bit {}, beyond the {}-bit tier mask;"
                        + " it will not be part of structure masks", key, index, Integer.SIZE);
            }
        }
    }

    /**
     * 第三方等级（非内置）按 id 排序，位号从它取。
     *
     * <p>排序键用 id 而不是注册表序：注册表在 {@code RegisterEvent} 填好之前是空的，那一刻这里返回空表、
     * 第三方等级的位号按 -1 处理——这是刻意的（内置三档不受影响）；等服务端启动时
     * {@link #validateRegistry()} 会在注册表填好之后再核一遍。</p>
     */
    private static List<EnergyCellTier> externalTiersSortedById() {
        var builtIn = builtIn().values();
        return REGISTRY.stream()
                .filter(tier -> !builtIn.contains(tier))
                .sorted(Comparator.comparing(EnergyCellTier::id))
                .toList();
    }

    private ModularCellTiers() {
    }

    /** 把等级注册器挂到模组总线上；由 {@code AEModularCellsRegistries#register} 调用。 */
    public static void register(IEventBus modBus) {
        TIERS.register(modBus);
    }

    /**
     * 所有等级，按固定位号排序。
     *
     * <p>每次调用都排一次序而不缓存：等级只有个位数到几十个，一次排序是微秒级；而缓存要处理「注册表在
     * {@code RegisterEvent} 之前是空的」这个时序陷阱，容易缓存出一张空表。</p>
     */
    public static List<EnergyCellTier> all() {
        return REGISTRY.stream()
                .sorted(Comparator.comparingInt(ModularCellTiers::indexOf))
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

    /** 等级的完整注册名，例如 {@code ae2modularcells:basic}；不在注册表里给 {@code null}。
     *
     * <p>日志与异常消息用它（比短 id 更能定位是哪一份注册），内部逻辑用 {@link #idOf}——后者注册前后都可用。
     */
    @Nullable
    public static ResourceLocation registryNameOf(EnergyCellTier tier) {
        return REGISTRY.getKey(tier);
    }

    /**
     * 等级在 {@code tierMask} 里的固定位号。
     *
     * <p><b>位号不来自注册表 id</b>：注册顺序由各模组的加载与事件触发次序决定，第三方先注册就会夺走 0/1/2，
     * 于是本模组三档变成 3/4/5、旧存档的 {@code tierMask} 全部错位——而位号是存档协议，不能建在「谁先加载」
     * 上。所以：内置三档写死 0/1/2（与枚举时代的 {@code ordinal()} 一致，旧存档因此仍然读得对），其余等级从 3
     * 起<b>按注册名排序</b>分配——同样的模组集合在任何一端都得到同样的位号。
     *
     * <p>不在注册表里的给 {@code -1}。</p>
     */
    public static int indexOf(EnergyCellTier tier) {
        int builtIn = builtInIndex(tier);
        if (builtIn >= 0) {
            return builtIn;
        }
        // 不调 all()：all() 正是按本方法排序的，会无限递归。排序键用 id() 而不是注册名——后者可能为 null，
        // 而 id() 由构造器保证非空且形如 resource path。
        int offset = externalTiersSortedById().indexOf(tier);
        return offset < 0 ? -1 : BUILT_IN_COUNT + offset;
    }

    /** 位号 → 等级；没有这一位给 {@code null}。与 {@link #indexOf} 互逆。 */
    @Nullable
    public static EnergyCellTier byIndex(int index) {
        if (index < 0) {
            return null;
        }
        for (var tier : REGISTRY) {
            if (indexOf(tier) == index) {
                return tier;
            }
        }
        return null;
    }

    /** 内置三档的固定位号（0/1/2），不是内置档给 -1。 */
    private static int builtInIndex(EnergyCellTier tier) {
        if (tier == BASIC_INSTANCE) {
            return 0;
        }
        if (tier == DENSE_INSTANCE) {
            return 1;
        }
        if (tier == SUPERDENSE_INSTANCE) {
            return 2;
        }
        return -1;
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
                    "Energy cell tier " + registryNameOf(tier) + " resolved to bit " + index
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
