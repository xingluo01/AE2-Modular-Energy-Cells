package io.github.lounode.ae2modularcells.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;

import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

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
 * public static final ModuleBlockSet MY_SET = ModularCellsApi.registerEnergyCellModule(
 *         BLOCKS, ITEMS, BLOCK_ENTITIES, MY_TIER.get());
 * }</pre>
 *
 * <p>两件注册器都要挂到模组总线上（{@code TIERS.register(modBus)} 等），本模组不做代管。</p>
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
     * 所有已注册的模块方块套装（本模组三档 + 各第三方注册的）。
     *
     * <p>并发安全，因为各模组的构造期是并行跑的（NeoForge 的 modloading worker），而这个表在那时被写入。</p>
     */
    private static final List<ModuleBlockSet> MODULE_BLOCK_SETS =
            Collections.synchronizedList(new ArrayList<>());

    /**
     * 注册一个等级的方块套装，注册名取 {@code <tierId>_energy_cell_module}。
     *
     * <p>必须在模组构造期调用（注册器还没来得及处理之前），否则这次注册不会被 NeoForge 收走。</p>
     *
     * @param blocks        你自己的方块注册器（命名空间即你的 mod id）
     * @param items         你自己的物品注册器
     * @param blockEntities 你自己的方块实体类型注册器
     * @param tier          要服务的等级；它必须先注册进等级注册表
     * @return 三件注册物；方块实体类型要等注册完成才能 {@code get()}
     */
    public static ModuleBlockSet registerEnergyCellModule(
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
    public static ModuleBlockSet registerEnergyCellModule(
            DeferredRegister.Blocks blocks,
            DeferredRegister.Items items,
            DeferredRegister<BlockEntityType<?>> blockEntities,
            EnergyCellTier tier,
            String name) {
        DeferredBlock<EnergyCellModuleBlock> block = blocks.register(name,
                () -> new EnergyCellModuleBlock(tier, BlockBehaviour.Properties.of()
                        .strength(2.5F, 6.0F)
                        .requiresCorrectToolForDrops()));
        DeferredItem<BlockItem> item = items.registerSimpleBlockItem(name, block);
        DeferredHolder<BlockEntityType<?>, BlockEntityType<EnergyCellModuleBlockEntity>> blockEntity =
                blockEntities.register(name, () -> createBlockEntityType(block));
        ModuleBlockSet set = new ModuleBlockSet(tier, block, item, blockEntity);
        MODULE_BLOCK_SETS.add(set);
        return set;
    }

    /**
     * 已注册的全部方块套装，按注册顺序。
     *
     * <p>本模组用它做能力注册与创造页填充；第三方一般不需要调，但可以用它遍历「所有模块方块」
     * （例如自己的联动逻辑）。</p>
     */
    public static List<ModuleBlockSet> allModuleBlockSets() {
        synchronized (MODULE_BLOCK_SETS) {
            return List.copyOf(MODULE_BLOCK_SETS);
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
        for (var set : allModuleBlockSets()) {
            if (ModularCellTiers.registryNameOf(set.tier()) == null) {
                throw new IllegalStateException("Module block set for tier " + set.tier().id()
                        + " was registered, but that tier is not in the energy cell tier registry;"
                        + " register it first (see ModularCellsApi)");
            }
        }
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

    private ModularCellsApi() {
    }
}
