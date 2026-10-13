package io.github.lounode.ae2modularcells.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;

import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.RegisterEvent;

import io.github.lounode.ae2modularcells.common.EnergyCellTier;
import io.github.lounode.ae2modularcells.common.ModularCellTiers;
import io.github.lounode.ae2modularcells.common.block.EnergyCellModuleBlock;
import io.github.lounode.ae2modularcells.common.block.entity.EnergyCellModuleBlockEntity;

/**
 * 给其他模组的接入面：注册一个等级，再注册它配套的方块套装。
 *
 * <h2>怎么用</h2>
 * <pre>{@code
 * // 1) 注册等级（自己的 mod id 作命名空间）
 * public static final DeferredRegister<EnergyCellTier> TIERS =
 *         DeferredRegister.create(ModularCellTiers.TIER_REGISTRY_KEY, "yourmod");
 * public static final DeferredHolder<EnergyCellTier, EnergyCellTier> MY_TIER =
 *         TIERS.register("my_tier", () -> new EnergyCellTier("my_tier", 400_000L, 2_000L, 2, 32_000L, 16_000L));
 *
 * // 2) 注册方块套装（方块 + 方块物品 + 方块实体类型）
 * public static final DeferredModuleBlockSet MY_SET = ModularCellsApi.registerEnergyCellModule(
 *         BLOCKS, ITEMS, BLOCK_ENTITIES, MY_TIER.get());
 * }</pre>
 *
 * <p>两件注册器都要挂到模组总线上（{@code TIERS.register(modBus)} 等），本模组不做代管。</p>
 *
 * <p>脚本侧（KubeJS）走 {@link #registerModuleBlockSet}：那条路不用 {@code DeferredRegister}，直接交实例。
 * 两条路最终汇进同一张清单 {@link #allModuleBlockSets()}，后续处理（能力、模型、创造页）完全一样。</p>
 *
 * <h2>这个入口替你做了什么</h2>
 * <ul>
 *   <li>方块、方块物品、方块实体类型三者同名注册，并把方块实体类型回填给方块（方块先注册、类型后注册，
 *       AE 的方块基类需要这次回填）；</li>
 *   <li>把方块接进本模组的全局清单——FE 输入面、组件槽的物品面、以及<b>网格主机能力</b>都由本模组统一注册。
 *       最后那条最容易漏：AE2 只在它自己的类型表上批量注册网格主机能力（上游
 *       {@code InitCapabilityProviders} 遍历 {@code AEBlockEntities.BLOCK_ENTITY_TYPES}），第三方不补就接不上线缆，
 *       而且不报错、只是那一块永远不联网。</li>
 * </ul>
 *
 * <h2>你还要自己准备的</h2>
 * <ul>
 *   <li><b>贴图</b>：方块模型由本模组的连通模型接管（{@code ConnectedModuleBakedModel}），它按等级 id 找贴图——
 *       你要提供 {@code <tierId>_energy_cell} 与 {@code <tierId>_energy_cell_light} 两张（前者是面板、后者是亮面），
 *       名字不匹配的表现是紫黑格。</li>
 *   <li><b>方块状态与物品模型 JSON</b>：本模组不做数据生成，三档的 {@code blockstates/} 与 {@code models/} 是手写的
 *       可以照抄（{@code basic_energy_cell_module} 那三份 + 形状变体）。</li>
 *   <li><b>语言键</b>：{@code block.<你的命名空间>.<方块名>}。</li>
 *   <li><b>创造模式标签页</b>：本模组只把自己的方块放进自己的标签页，你的进你的。</li>
 * </ul>
 *
 * <h2>边界</h2>
 * <p>等级与方块套装是<b>双端都要满足</b>的：只在服务端注册的等级，客户端解不出那一档（终端里的结构快照按位号
 * 配对）。贴图与模型还只在客户端需要。</p>
 */
public final class ModularCellsApi {

    /**
     * Java 侧登记的套装：此刻还只有句柄（{@code RegisterEvent} 之前 {@code get()} 拿不到实例）。
     *
     * <p>并发安全，因为各模组的构造期是并行跑的（NeoForge 的 modloading worker），而这个表在那时被写入。</p>
     */
    private static final List<DeferredModuleBlockSet> DEFERRED_SETS =
            Collections.synchronizedList(new ArrayList<>());

    /** 脚本侧登记的套装：登记时就是已建好的实例，不必解析。 */
    private static final List<ModuleBlockSet> DIRECT_SETS =
            Collections.synchronizedList(new ArrayList<>());

    /**
     * 两张表合并后的清单；{@code null} 表示还没解析或已被作废（每次登记都会置空）。
     *
     * <p>惰性解析而不是在某个事件里主动重算：这个字段的第一次读取就是能力注册那一步，那时注册表已经填好；
     * 在那之前被读只会拿到一张不含 Java 侧套装的小表，而那时也没人需要完整清单。</p>
     */
    private static volatile List<ModuleBlockSet> merged;

    /**
     * 注册是否已关闭；由 {@link #validateAll()} 置位。
     *
     * <p>服务端启动之后还调 {@link #registerEnergyCellModule} 就晚了：方块不会被 NeoForge 收走，但套装已经进了
     * 清单，于是能力注册那步会对一个未绑定的 holder 调 {@code get()}，报出一句与真正原因毫无关系的
     * “unbound value”。在这里就拦下来，换一句能看懂的话。</p>
     */
    private static volatile boolean registrationClosed;

    private static final Logger LOGGER = LoggerFactory.getLogger(ModularCellsApi.class);

    /**
     * 注册一个等级的方块套装，注册名取 {@code <tierId>_energy_cell_module}。
     *
     * <p>必须在模组构造期调用（注册器还没来得及处理之前），否则这次注册不会被 NeoForge 收走。</p>
     *
     * @param blocks        你自己的方块注册器（命名空间即你的 mod id）
     * @param items         你自己的物品注册器
     * @param blockEntities 你自己的方块实体类型注册器
     * @param tier          要服务的等级；它必须先注册进等级注册表
     * @return 三个句柄；要拿实例得等注册完成（{@link DeferredModuleBlockSet#isBound()}）
     */
    public static DeferredModuleBlockSet registerEnergyCellModule(
            DeferredRegister.Blocks blocks,
            DeferredRegister.Items items,
            DeferredRegister<BlockEntityType<?>> blockEntities,
            EnergyCellTier tier) {
        return registerEnergyCellModule(blocks, items, blockEntities, tier, blockName(tier));
    }

    /**
     * 同上，但方块名自取。
     *
     * <p>不自取时用 {@link #blockName}（{@code <tierId>_energy_cell_module}）。自取的名字要与贴图、语言键、
     * 方块状态 JSON 三处对齐——连通模型按方块 id 找模型，而贴图名仍按等级 id 拼（见类 javadoc）。</p>
     */
    public static DeferredModuleBlockSet registerEnergyCellModule(
            DeferredRegister.Blocks blocks,
            DeferredRegister.Items items,
            DeferredRegister<BlockEntityType<?>> blockEntities,
            EnergyCellTier tier,
            String name) {
        if (registrationClosed) {
            throw new IllegalStateException("ModularCellsApi.registerEnergyCellModule was called for tier " + tier.id()
                    + " after the server started; module block sets must be registered during mod construction"
                    + " (registering this late would leave the block outside NeoForge's registry)");
        }
        DeferredBlock<EnergyCellModuleBlock> block = blocks.register(name,
                () -> new EnergyCellModuleBlock(tier, BlockBehaviour.Properties.of()
                        .strength(2.5F, 6.0F)
                        .requiresCorrectToolForDrops()));
        DeferredItem<BlockItem> item = items.registerSimpleBlockItem(name, block);
        DeferredHolder<BlockEntityType<?>, BlockEntityType<EnergyCellModuleBlockEntity>> blockEntity =
                blockEntities.register(name, () -> createBlockEntityType(block));
        DeferredModuleBlockSet set = new DeferredModuleBlockSet(tier, name, block, item, blockEntity);
        DEFERRED_SETS.add(set);
        merged = null;
        return set;
    }

    /**
     * 直接把建好的实例登记进来（脚本侧走这条）。
     *
     * <p>KubeJS 在 {@code RegisterEvent} 里自己调 {@code event.register}，不经过 {@code DeferredRegister}，所以
     * 它手里只有实例。这个入口让那些套装与 Java 侧注册的汇进同一张清单，从而同样拿到能力注册与模型替换——
     * 否则脚本造出来的模块方块会接不上线缆，而且不报错。</p>
     *
     * <p>调用方要把方块的方块实体类型回填好（{@code block.setBlockEntity(EnergyCellModuleBlockEntity.class,
     * type, null, null)}），否则 AE2 的「拆下来给哪个物品」这类查询会失效。</p>
     *
     * @param tier        要服务的等级；它必须先注册进等级注册表
     * @param id          方块（也是方块物品、方块实体类型、模型与贴图）的注册名
     * @param block       模块方块
     * @param item        模块方块物品
     * @param blockEntity 模块方块实体类型
     */
    public static ModuleBlockSet registerModuleBlockSet(
            EnergyCellTier tier,
            ResourceLocation id,
            EnergyCellModuleBlock block,
            BlockItem item,
            BlockEntityType<EnergyCellModuleBlockEntity> blockEntity) {
        if (registrationClosed) {
            throw new IllegalStateException("ModularCellsApi.registerModuleBlockSet was called for tier " + tier.id()
                    + " after the server started; module block sets must be registered during mod construction");
        }
        ModuleBlockSet set = new ModuleBlockSet(tier, id, block, item, blockEntity);
        DIRECT_SETS.add(set);
        merged = null;
        return set;
    }

    /**
     * 已注册的全部方块套装，按登记顺序（脚本侧的先、Java 侧的后）。
     *
     * <p>本模组用它做能力注册与创造页填充；第三方一般不需要调，但可以用它遍历「所有模块方块」
     * （例如自己的联动逻辑）。</p>
     */
    public static List<ModuleBlockSet> allModuleBlockSets() {
        List<ModuleBlockSet> cache = merged;
        if (cache != null) {
            return cache;
        }
        synchronized (ModularCellsApi.class) {
            if (merged != null) {
                return merged;
            }
            List<ModuleBlockSet> out = new ArrayList<>(DIRECT_SETS);
            boolean everythingBound = true;
            synchronized (DEFERRED_SETS) {
                for (var pending : DEFERRED_SETS) {
                    if (pending.isBound()) {
                        out.add(pending.resolve());
                    } else {
                        everythingBound = false;
                    }
                }
            }
            // 只有全部解析出来才缓存。否则这张「不含 Java 侧套装」的残缺清单会被钉死，而之后再没有登记
            // 动作来把它置空——那样 Java 侧（含本模组三档）的能力与模型会永久静默缺失。
            // 首次真正需要完整清单的是能力注册，那时早已全部绑定；在那之前的读取只会拿到一份临时副本。
            if (everythingBound) {
                merged = List.copyOf(out);
            }
            return List.copyOf(out);
        }
    }

    /**
     * 启动期校验：每个方块套装引用的等级必须真的注册过。
     *
     * <p>漏这一步的后果是安静的：没注册的等级算不出位号（{@code indexOf} 给 -1），那种方块照旧能放、能储能，
     * 但不进 {@code tierMask}——结构账目会把它漏掉，而【{@code ModularCellTiers#validateRegistry} 看不见它】，
     * 因为那一轮遍历的是注册表，而这个套装压根不在注册表里。</p>
     *
     * <p>校验时机同等级那边：服务端启动，此时两张表都已定下来。</p>
     */
    public static void validateAll() {
        Set<EnergyCellTier> tiersWithBlockSets = new HashSet<>();
        for (var set : allModuleBlockSets()) {
            EnergyCellTier tier = set.tier();
            // 三件真的都进了注册表吗。脚本那条路要靠注册表顺序（方块最早，物品与方块实体类型都比它晚），
            // 而丢件的表现是安静的：方块没有物品、或方块实体类型压根不存在，启动日志里什么都看不到。
            // 这里一次问清，把静默丢件变成启动期报错。
            if (!BuiltInRegistries.BLOCK.containsKey(set.id())) {
                throw new IllegalStateException("Module block set " + set.id()
                        + " has no registered block; the registration path did not reach the block registry");
            }
            if (!BuiltInRegistries.ITEM.containsKey(set.id())) {
                throw new IllegalStateException("Module block set " + set.id()
                        + " has no registered item; it would be placed but never dropped");
            }
            if (!BuiltInRegistries.BLOCK_ENTITY_TYPE.containsKey(set.id())) {
                throw new IllegalStateException("Module block set " + set.id()
                        + " has no registered block entity type; the block would have no logic at all");
            }
            if (ModularCellTiers.registryNameOf(tier) == null) {
                throw new IllegalStateException("Module block set for tier " + tier.id()
                        + " was registered, but that tier is not in the energy cell tier registry;"
                        + " register it first (see ModularCellsApi)");
            }
            if (!tiersWithBlockSets.add(tier)) {
                throw new IllegalStateException("Tier " + tier.id()
                        + " has more than one module block set; which one is \"the\" block for that tier would then"
                        + " depend on registration order");
            }
            // 方块自报的等级必须与套装声明的一致。第三方图省事拿两个不同实例混用时，方块能放、能储能、名字也对，
            // 但按等级查套装会查不到（菜单的等级图标那条路径会拿到 null）。
            if (set.block().tier() != tier) {
                throw new IllegalStateException("Module block set for tier " + tier.id()
                        + " wraps a block that reports tier " + set.block().tier().id()
                        + "; the block and the set must reference the same tier instance");
            }
        }
        // 有等级、没方块套装：不会出错（那种等级永远不出现在结构里），但多半是漏了注册，说一声。
        for (var tier : ModularCellTiers.all()) {
            if (!tiersWithBlockSets.contains(tier)) {
                LOGGER.warn("Energy cell tier {} has no module block set; it will never appear in a structure",
                        tier.id());
            }
        }
        registrationClosed = true;
    }

    /** 等级的默认方块名：{@code <tierId>_energy_cell_module}。 */
    public static String blockName(EnergyCellTier tier) {
        return blockName(tier.id());
    }

    /**
     * 默认方块名，按等级 id 拼。
     *
     * <p>分开一个重载是因为注册期拿不到等级实例——方块注册的时机可能早于等级注册表填好，而名字必须先算出来。</p>
     */
    public static String blockName(String tierId) {
        return tierId + "_energy_cell_module";
    }

    /**
     * 建方块实体类型，并把类型回填给方块。
     *
     * <p>回填是必需的：方块先注册、类型后注册，方块在构造时拿不到自己的类型，而 AE 的方块基类要靠它做
     * 「拆下来给哪个物品」这类查询。照抄 AE2 {@code AEBlockEntities} 的做法。ticker 两处都传 {@code null}——
     * 模块方块实体不 tick（本模组的性能红线），周期工作走网格的 {@code IGridTickable}。</p>
     */
    private static BlockEntityType<EnergyCellModuleBlockEntity> createBlockEntityType(
            DeferredBlock<EnergyCellModuleBlock> block) {
        // 类型要交给方块实体，而类型本身还在构建中，所以先用一个引用接住。
        java.util.concurrent.atomic.AtomicReference<BlockEntityType<EnergyCellModuleBlockEntity>> holder =
                new java.util.concurrent.atomic.AtomicReference<>();
        BlockEntityType<EnergyCellModuleBlockEntity> type = BlockEntityType.Builder.of(
                (pos, state) -> new EnergyCellModuleBlockEntity(holder.get(), pos, state),
                block.get()).build(null);
        holder.setPlain(type);
        block.get().setBlockEntity(EnergyCellModuleBlockEntity.class, type, null, null);
        return type;
    }

    /**
     * 脚本建的等级，等着被注册。
     *
     * <p>为什么不能就地注册：KubeJS 的落盘只认「当前正在注册的那个注册表」
     * （见 {\@code RegistryEventHandler#handleRegistryEvent}：它取 {@code RegistryObjectStorage.of(key)} 然后
     * 逐个 {@code event.register}），而 vanilla 的注册表（{@code minecraft:block} 等）都早于本模组的自建注册表。
     * 所以在方块 builder 里 new 出来的等级无处安放——只能先存着，等本模组自己那一次 {@code RegisterEvent}。</p>
     */
    private record PendingTier(ResourceLocation id, EnergyCellTier tier) {}

    private static final List<PendingTier> PENDING_TIERS =
            Collections.synchronizedList(new ArrayList<>());

    /**
     * 登记一个晚到的等级（目前只有脚本会造出这种），真正注册发生在
     * {@link #registerLateTiers(RegisterEvent)}。
     *
     * <p>名字不叫 Script 是有意的：本方法完全不依赖 KubeJS，也不知道调用者是谁——它接的就是「一个已经建好、
     * 但还没进注册表的等级」。谁需要这条路都可以用。</p>
     *
     * <p>等级实例必须与它的方块套装共用同一个——启动期会按恒等比对「套装声明的等级」与「方块自报的等级」。</p>
     */
    public static void registerLateTier(ResourceLocation id, EnergyCellTier tier) {
        if (registrationClosed) {
            throw new IllegalStateException("A late energy cell tier (" + id
                    + ") was registered after the server started; it has to be created during the registry phase");
        }
        PENDING_TIERS.add(new PendingTier(id, tier));
    }

    /**
     * 等级注册表那一次 {@code RegisterEvent}：把晚到的等级补进去。
     *
     * <p>由模组入口挂到模组总线（{@code modBus.addListener(ModularCellsApi::registerLateTiers)}）。</p>
     */
    public static void registerLateTiers(RegisterEvent event) {
        if (!event.getRegistryKey().equals(ModularCellTiers.TIER_REGISTRY_KEY)) {
            return;
        }
        Set<ResourceLocation> seen = new HashSet<>();
        synchronized (PENDING_TIERS) {
            for (var pending : PENDING_TIERS) {
                // 同一批里重名就直接跳过：NeoForge 的重复键异常不会提到是本模组的哪条链
                // （两个方块写了同一个 tierId 就是这个症状），所以在这里先拦下来并说清楚。
                // 与内置三档或第三方撞名的那种跨源重复仍交给 NeoForge，它的异常足够清楚。
                if (!seen.add(pending.id())) {
                    LOGGER.error("More than one scripted module block claims tier {}; skipping it."
                            + " Give them different tierId() values.", pending.id());
                    continue;
                }
                event.register(ModularCellTiers.TIER_REGISTRY_KEY, pending.id(), pending::tier);
            }
            PENDING_TIERS.clear();
        }
    }

    private ModularCellsApi() {
    }
}
