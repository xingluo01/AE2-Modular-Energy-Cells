package io.github.lounode.ae2modularcells;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import appeng.api.AECapabilities;

import io.github.lounode.ae2modularcells.common.EnergyCellTier;
import io.github.lounode.ae2modularcells.common.block.EnergyCellModuleBlock;
import io.github.lounode.ae2modularcells.common.block.MonitorBlock;
import io.github.lounode.ae2modularcells.common.block.entity.EnergyCellModuleBlockEntity;
import io.github.lounode.ae2modularcells.common.block.entity.MonitorBlockEntity;
import io.github.lounode.ae2modularcells.common.menu.EnergyModuleTerminalMenu;

/**
 * 模组注册表的唯一入口。
 *
 * <p>命名约定：方块 {@code <tier>_energy_cell_module}（basic / dense / superdense），注册名与语言键同名，
 * 免得玩家看到两个名字。本模组不做组件物品——槽位插的是 AE2 与邻居们现成的存储组件。</p>
 *
 * <p>mod id 在本仓有三处独立声明：{@code gradle.properties} 的 {@code mod_id}、模组元数据模板里的
 * {@code ${mod_id}}、以及 {@link AE2ModularCells#MOD_ID}。注册命名空间取自 Java 那处，三处不一致会拖到
 * 装载期才炸——要改就三处一起改。</p>
 */
public final class AEModularCellsRegistries {

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(AE2ModularCells.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(AE2ModularCells.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, AE2ModularCells.MOD_ID);
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, AE2ModularCells.MOD_ID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, AE2ModularCells.MOD_ID);

    private static final Map<EnergyCellTier, DeferredBlock<EnergyCellModuleBlock>> MODULE_BLOCKS =
            new EnumMap<>(EnergyCellTier.class);
    private static final Map<EnergyCellTier, DeferredItem<BlockItem>> MODULE_ITEMS =
            new EnumMap<>(EnergyCellTier.class);
    private static final Map<EnergyCellTier, DeferredHolder<BlockEntityType<?>, BlockEntityType<EnergyCellModuleBlockEntity>>> MODULE_BLOCK_ENTITIES =
            new EnumMap<>(EnergyCellTier.class);

    /**
     * 模块管理终端的菜单类型。
     *
     * <p>本仓约定「菜单注册名 / 样式文档名 / 标题语言键」用同一段词：样式文档其实由客户端按路径显式加载
     * （见 {@code AEModularCellsClient#STYLE_DOC}），三者并无硬耦合，改一个不会当场报错——所以它们靠约定对齐，
     * 改名时一起改。</p>
     */
    public static final DeferredHolder<MenuType<?>, MenuType<EnergyModuleTerminalMenu>> MENU_ENERGY_MODULE_TERMINAL =
            MENUS.register("energy_module_terminal", () -> EnergyModuleTerminalMenu.TYPE);

    /** 本模组自己的创造模式标签页（语言键 {@code itemGroup.ae2modularcells}）。 */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN_TAB = CREATIVE_TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup." + AE2ModularCells.MOD_ID))
                    .icon(() -> new ItemStack(moduleItem(EnergyCellTier.BASIC).get()))
                    .displayItems((parameters, output) -> {
                        for (var tier : EnergyCellTier.values()) {
                            output.accept(moduleItem(tier).get());
                        }
                        // 必须写全限定名：本字段的声明在下面，而 Java 不允许静态字段初始化表达式里用简单名「向前引用」
                        // （编译报「非法前向引用」）；限定名不受该规则限制。
                        output.accept(AEModularCellsRegistries.BLANK_ENERGY_UNIT_ITEM.get());
                        output.accept(AEModularCellsRegistries.ENERGY_MONITOR_ITEM.get());
                        output.accept(AEModularCellsRegistries.FLOW_MONITOR_ITEM.get());
                    })
                    .build());

    /**
     * 模块化空白能量单元：**只做合成中间体**。
     *
     * <p>不储能、不成型、不接入 ME 网格（没有方块实体、不挂任何能力）——它是「待加工的一块壳」，拿它去合成
     * 三档模块。因此这里用原版 {@link Block} 就够，不因为看着像模块就给它接上台账：一旦给它方块实体，玩家就
     * 会问「为什么这一块拼进去不算数」。</p>
     *
     * <p>贴图口径（美术给的 {@code unit.png} + {@code unit_base.png}）：侧面用 {@code unit}、上下用
     * {@code unit_base}。若美术后来只要单面，把模型换成 {@code cube_all} 指向 {@code unit} 即可。</p>
     */
    public static final DeferredBlock<Block> BLANK_ENERGY_UNIT = BLOCKS.register("blank_energy_unit",
            () -> new Block(BlockBehaviour.Properties.of()
                    .strength(2.5F, 6.0F)
                    .requiresCorrectToolForDrops()));

    public static final DeferredItem<BlockItem> BLANK_ENERGY_UNIT_ITEM =
            ITEMS.registerSimpleBlockItem("blank_energy_unit", BLANK_ENERGY_UNIT);

    /**
     * 两个监控器方块：能量监控器（正面显示储能百分比）与能量流量监控器（FE 输入流量与吞吐量）。
     *
     * <p>它们不提供储能、也不提供传输速率；拼进立方体时算结构件（参与成型判定，但不进容量/速率求和）。</p>
     */
    public static final DeferredBlock<MonitorBlock> ENERGY_MONITOR = BLOCKS.register("energy_monitor",
            () -> new MonitorBlock(MonitorBlock.Kind.ENERGY, monitorProperties()));

    public static final DeferredBlock<MonitorBlock> FLOW_MONITOR = BLOCKS.register("flow_monitor",
            () -> new MonitorBlock(MonitorBlock.Kind.FLOW, monitorProperties()));

    public static final DeferredItem<BlockItem> ENERGY_MONITOR_ITEM =
            ITEMS.registerSimpleBlockItem("energy_monitor", ENERGY_MONITOR);

    public static final DeferredItem<BlockItem> FLOW_MONITOR_ITEM =
            ITEMS.registerSimpleBlockItem("flow_monitor", FLOW_MONITOR);

    private static final Map<DeferredBlock<MonitorBlock>, DeferredHolder<BlockEntityType<?>, BlockEntityType<MonitorBlockEntity>>> MONITOR_BLOCK_ENTITIES =
            new java.util.IdentityHashMap<>();

    static {
        for (var tier : EnergyCellTier.values()) {
            String name = blockName(tier);
            DeferredBlock<EnergyCellModuleBlock> block = BLOCKS.register(name,
                    () -> new EnergyCellModuleBlock(tier, BlockBehaviour.Properties.of()
                            .strength(2.5F, 6.0F)
                            .requiresCorrectToolForDrops()));
            MODULE_BLOCKS.put(tier, block);
            MODULE_ITEMS.put(tier, ITEMS.registerSimpleBlockItem(name, block));
            MODULE_BLOCK_ENTITIES.put(tier, BLOCK_ENTITIES.register(name, () -> createModuleBlockEntityType(block)));
        }
        for (var monitor : List.of(ENERGY_MONITOR, FLOW_MONITOR)) {
            MONITOR_BLOCK_ENTITIES.put(monitor, BLOCK_ENTITIES.register(monitor.getId().getPath(),
                    () -> createMonitorBlockEntityType(monitor)));
        }
    }

    /** 监控器的方块实体类型。客户端注册渲染器要按类型注册，所以开一个只读取用口。 */
    public static BlockEntityType<MonitorBlockEntity> monitorBlockEntity(DeferredBlock<MonitorBlock> block) {
        return MONITOR_BLOCK_ENTITIES.get(block).get();
    }

    /** 监控器的方块属性：与模块同强度，便于统一。 */
    private static BlockBehaviour.Properties monitorProperties() {
        return BlockBehaviour.Properties.of().strength(2.5F, 6.0F).requiresCorrectToolForDrops();
    }

    private AEModularCellsRegistries() {
    }

    /**
     * 三个等级的模块方块共用一个方块实体类，所以类型要回填给方块。
     *
     * <p>时序：方块先注册、方块实体类型后注册，方块构造时拿不到自己的类型，所以这里在类型建好之后调用
     * {@code setBlockEntity} 回填（照抄 AE2 {@code AEBlockEntities} 的做法）。ticker 两处都传 null——模块
     * 方块实体不 tick。</p>
     */
    private static BlockEntityType<EnergyCellModuleBlockEntity> createModuleBlockEntityType(
            DeferredBlock<EnergyCellModuleBlock> block) {
        AtomicReference<BlockEntityType<EnergyCellModuleBlockEntity>> holder = new AtomicReference<>();
        var type = BlockEntityType.Builder.of(
                (pos, state) -> new EnergyCellModuleBlockEntity(holder.get(), pos, state),
                block.get()).build(null);
        holder.setPlain(type);
        block.get().setBlockEntity(EnergyCellModuleBlockEntity.class, type, null, null);
        return type;
    }

    /** 方块（也是方块物品、方块实体、模型与贴图）的注册名。 */
    public static String blockName(EnergyCellTier tier) {
        return tier.id() + "_energy_cell_module";
    }

    /**
     * 监控器的方块实体类型：同样要回填给方块（方块先注册、类型后注册）。ticker 两处传 null。
     */
    private static BlockEntityType<MonitorBlockEntity> createMonitorBlockEntityType(
            DeferredBlock<MonitorBlock> block) {
        AtomicReference<BlockEntityType<MonitorBlockEntity>> holder = new AtomicReference<>();
        var type = BlockEntityType.Builder.of(
                (pos, state) -> new MonitorBlockEntity(holder.get(), pos, state),
                block.get()).build(null);
        holder.setPlain(type);
        block.get().setBlockEntityType(type);
        return type;
    }

    public static DeferredBlock<EnergyCellModuleBlock> moduleBlock(EnergyCellTier tier) {
        return MODULE_BLOCKS.get(tier);
    }

    public static DeferredItem<BlockItem> moduleItem(EnergyCellTier tier) {
        return MODULE_ITEMS.get(tier);
    }

    public static DeferredHolder<BlockEntityType<?>, BlockEntityType<EnergyCellModuleBlockEntity>> moduleBlockEntity(
            EnergyCellTier tier) {
        return MODULE_BLOCK_ENTITIES.get(tier);
    }

    /** 把注册器挂到模组总线上；由 {@link AE2ModularCells} 的构造器调用。 */
    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        MENUS.register(modBus);
        CREATIVE_TABS.register(modBus);
        // 能力按「方块实体类型」注册，NeoForge 不给「只给核心注册」留口子；所以三档全注册，
        // 由 provider 自己回答「这个面到底收不收」。
        modBus.addListener(AEModularCellsRegistries::registerCapabilities);
    }

    /**
     * FE（NeoForge Energy）能力。
     *
     * <p><b>只进不出</b>：注册的是本模组的 {@code InsertOnlyEnergyStorage}，它包住 AE2 自己的
     * {@code ForgeEnergyAdapter}——换算比例仍由 AE2 负责（{@code [powerRatios] forgeEnergy}，默认
     * 1 FE = 0.5 AE），但对外不报「已存 FE」、也不允许抽 FE。本模组不存 FE：FE 进来当场变成 AE 进能量池。</p>
     *
     * <p>收到的能量不会留在这一块里：方块实体会把非核心收到的能量当场转交给核心的单一能量池。</p>
     */
    private static void registerCapabilities(RegisterCapabilitiesEvent event) {
        for (var type : MODULE_BLOCK_ENTITIES.values()) {
            event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, type.get(),
                    (blockEntity, side) -> blockEntity.getFeInput(side));
            // 组件槽对自动化的面：AE2 的导出/输出总线找目标方块就是查这个能力
            // （上游 StorageExportStrategy 用 Capabilities.ItemHandler.BLOCK）。只进不出、只收存储组件、
            // 未成型不开门——三条门禁都在 StructureComponentItemHandler 里。
            event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, type.get(),
                    (blockEntity, side) -> blockEntity.componentItemHandler());
            // 网格主机能力：<b>这一条不给就接不上线缆</b>。AE2 只在它自己的类型表上批量注册这个能力
            // （上游 InitCapabilityProviders 遍历 AEBlockEntities.BLOCK_ENTITY_TYPES），第三方必须自己补；
            // 线缆侧走 GridHelper#getNodeHost → 查的就是这个块能力（没有 instanceof 扫描）。
            // 返回方块实体自己：它经 AENetworkedPoweredBlockEntity 已经持有可用的网格节点。
            event.registerBlockEntity(AECapabilities.IN_WORLD_GRID_NODE_HOST, type.get(),
                    (blockEntity, context) -> blockEntity);
        }
        // 监控器也得有 FE 面：玩家会把线接在整段的任意一块上，而监控器自己没有能量逻辑，收到的原样转交核心
        // （见 MonitorBlockEntity#getFeInput）。少这一条，接在监控器上的线就是死的——外在表现是「整段一直是 0%」。
        // 只给能量这一条：监控器没有组件槽（不能装存储组件）、也没有自己的网格节点（整段仍只有一个节点）。
        for (var type : MONITOR_BLOCK_ENTITIES.values()) {
            event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, type.get(),
                    (blockEntity, side) -> blockEntity.getFeInput(side));
        }
    }
}
