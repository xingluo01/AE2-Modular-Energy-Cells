package io.github.lounode.ae2modularcells.common.block;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;

import java.util.ArrayList;
import java.util.List;

import appeng.block.AEBaseEntityBlock;
import appeng.blockentity.AEBaseBlockEntity;
import appeng.menu.MenuOpener;
import appeng.menu.locator.MenuLocators;
import appeng.util.SettingsFrom;

import io.github.lounode.ae2modularcells.common.EnergyCellTier;
import io.github.lounode.ae2modularcells.common.StorageComponents;
import io.github.lounode.ae2modularcells.common.block.entity.EnergyCellModuleBlockEntity;
import io.github.lounode.ae2modularcells.common.menu.EnergyModuleTerminalMenu;

/**
 * 能源模块方块：三档共用这一个类，等级由构造参数钉住。
 *
 * <p>这一块自己不知道「我在哪一段结构里」——那是方块实体的账。方块这一侧只做三件事：右键开屏、
 * 邻居形状变化时让渲染重取连接状态、放置/破坏时把结构重算的球踢给方块实体。</p>
 *
 * <p>没有 ticker：方块实体不注册 tick（见 {@code docs/ARCHITECTURE.md} §8），这个类里也就不该出现任何
 * 与时间有关的东西。</p>
 */
public class EnergyCellModuleBlock extends AEBaseEntityBlock<EnergyCellModuleBlockEntity> implements StructurePart {

    /**
     * 储能档位（0~4）：只影响画面——发光层的强度按档位取（0 档不发光 → 4 档满亮），不参与任何能量计算。
     *
     * <p><b>为什么是分档而不是直接读百分比</b>：模型是烘焙期静态的，拿不到逐帧能量；分档让“电量越多越亮”
     * 以 5 级跳变的形式成立，跨档时由服务端改这个属性（一项方块状态变化，比逐帧发数据便宜得多）。</p>
     *
     * <p>默认 0 档：未成型/空电的模块不发光，与“0% 不发光”的口径一致。</p>
     */
    public static final IntegerProperty FILL = IntegerProperty.create("fill", 0, 4);

    /**
     * 成型与否。
     *
     * <p><b>放方块状态而不是方块实体数据</b>：这是照 AE2 合成单元的口径（{@code AbstractCraftingUnitBlock.FORMED}，
     * 以及 {@code crafting_unit.json} 的 {@code formed=false → crafting/unit}、{@code formed=true → crafting/unit_formed}）。
     * 方块状态变化是一等公民、必然同步；而模型数据在客户端是按位置缓存的，靠自定义包推容易漏一环——漏了就出现
     * 「结构成型了，方块还是未成型纹理」。</p>
     *
     * <p>纯视觉属性：不参与结构检测，也不参与能量账目（检测仍以世界里的实际方块为准）。</p>
     */
    public static final BooleanProperty FORMED = BooleanProperty.create("formed");

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FILL, FORMED);
    }

    private final EnergyCellTier tier;

    public EnergyCellModuleBlock(EnergyCellTier tier, Properties properties) {
        super(properties);
        this.tier = tier;
        // 默认必须是未成型。布尔属性在 vanilla 里是 ImmutableSet.of(true, false)，而 BlockState 的默认值取每个属性
        // 的**第一个**值，所以不显式声明的话新放下的方块就是 formed=true——客户端会先画一帧成型（连接边框 + 自发光层），
        // 等下一 tick 重扫判不成型再切成素面，玩家看到的是「每次放下都闪一下」。
        registerDefaultState(defaultBlockState().setValue(FORMED, false));
    }

    /** 这一档的等级。结构检测、容量汇总与菜单展示都按它取数值。 */
    public EnergyCellTier tier() {
        return this.tier;
    }

    
    /**
     * 右键：只在成型后开屏。
     *
     * <p>未成型（单块，或没拼成立方体的一堆）右键什么都不发生——与 AE2 的合成单元同口径：不成型时它也不给
     * 界面。否则玩家会在一个根本没有结构的单块上打开一个显示「1 个模块」的屏，看不出哪里不对。</p>
     *
     * <p>开屏只在服务端发起，客户端拿到的是服务端给的菜单。</p>
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hitResult) {
        if (!(level.getBlockEntity(pos) instanceof EnergyCellModuleBlockEntity module)) {
            return InteractionResult.PASS;
        }
        if (!module.isFormed()) {
            // 没成型就什么都不做，把这次交互还给原版：以前这里返回 sidedSuccess，等于把「右键」吞了——
            // 玩家手上拿着方块也对准了脸，却放不下去（开不了屏不是拒绝交互的理由）。
            return InteractionResult.PASS;
        }
        if (!level.isClientSide()) {
            MenuOpener.open(EnergyModuleTerminalMenu.TYPE, player, MenuLocators.forBlockEntity(module));
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    /**
     * Shift + 右键：把手上的存储组件装进这一段结构，或换掉里面更次的那一件。
     *
     * <p>口径（与需求一致）：先填空槽，没有空槽再换「同种、更低档」的，最后才换「另一种、同档」的；
     * 扫描顺序从核心开始，所以反复用这个手势换掉的是排在最前面的那一件。换下来的组件先塞背包，
     * 塞不下就掉在脚边（与 AE2 拆下元件同口径）。</p>
     *
     * <p><b>为何在 {@code useItemOn} 里做</b>：这是 1.21 里「手上拿着东西右键方块」的入口；
     * 且只有按着 shift（{@code isSecondaryUseActive}）才接管，不按 shift 的右键保持原样，免得抢原版手感。</p>
     *
     * <p><b>两端语义</b>：客户端不做任何改动，但两端都返回「已处理」——服务端真改、客户端只是不让原版
     * 接着把这次交互当成「用物品」。它俩对「有没有可换的」判断可能差一瞬（客户端看到的是同步过来的
     * 成型状态），差的后果只是那一下没反应，不会产生幽灵物品。</p>
     */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (stack.isEmpty() || !player.isSecondaryUseActive()) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!(level.getBlockEntity(pos) instanceof EnergyCellModuleBlockEntity module)
                || !module.isFormed()
                || !StorageComponents.isComponent(stack)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level.isClientSide()) {
            return ItemInteractionResult.SUCCESS;
        }

        var result = module.installComponent(stack);
        if (result == null) {
            // 没有下手的地方就说一声（动作栏）：手势被吞掉而毫无反馈，玩家只会以为模组坏了。
            player.displayClientMessage(
                    Component.translatable("msg.ae2modularcells.no_component_target"), true);
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        stack.shrink(1);
        if (!result.replaced().isEmpty()) {
            if (!player.getInventory().add(result.replaced())) {
                player.drop(result.replaced(), false);
            }
            player.displayClientMessage(Component.translatable("msg.ae2modularcells.component_replaced",
                    stack.getHoverName(), result.replaced().getHoverName()), true);
        } else {
            player.displayClientMessage(Component.translatable("msg.ae2modularcells.component_inserted",
                    stack.getHoverName()), true);
        }
        return ItemInteractionResult.SUCCESS;
    }

    /**
     * 邻居形状变化时通知渲染重取连接状态。
     *
     * <p>连接关系只从世界方块状态推出来，两端各自算（见 {@code EnergyCellModuleBlockEntity#getModelData}），
     * 所以这条路在客户端与服务端都会走到——这也是它能跨区块同步、不需要自定义网络包的原因。</p>
     */
    @Override
    public BlockState updateShape(BlockState state, Direction facing, BlockState facingState, LevelAccessor level,
            BlockPos pos, BlockPos facingPos) {
        if (level.getBlockEntity(pos) instanceof EnergyCellModuleBlockEntity module) {
            module.requestModelDataUpdate();
        }
        return super.updateShape(state, facing, facingState, level, pos, facingPos);
    }

    /**
     * 放置：登记一次下一 tick 的结构检测。
     *
     * <p><b>扫描不能写在这里。</b>{@code LevelChunk#setBlockState} 是先调 {@code onPlace}、之后才建方块实体
     * （1.21 的源码里前者在 283 行、后者在 286~300 行），所以这一刻 {@code getBlockEntity(pos)} 还是空的，
     * 扫描会被安静地跳过——症状是结构永不成型、界面打不开，而连接边框照常显示（它只看世界方块状态）。</p>
     *
     * <p>用调度 tick 而不是自建 ticker：方块实体依旧不注册 tick，这里只是把这次事件往后挪一格；
     * 它也顺便覆盖直接改方块的路径（结构生成、命令、活塞等，那些路径不一定过 `setPlacedBy`）。</p>
     */
    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level.isClientSide() || oldState.is(state.getBlock())) {
            return;
        }
        level.scheduleTick(pos, this, 1);
    }

    /** 延迟一格再扫：这时方块实体已经建好（见 {@link #onPlace}）。 */
    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        super.tick(state, level, pos, random);
        EnergyCellModuleBlockEntity.rebuildAt(level, pos);
    }

    /** 破坏：先让整段失效（含组件掉落），再让剩下的每一块各自回退。 */
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!level.isClientSide() && !newState.is(state.getBlock())) {
            // newState 一起交出去：新方块仍是结构件（模块换监控器之类）时整段并没有缺块，不该解散。
            EnergyCellModuleBlockEntity.onModuleRemoved(level, pos, newState);
        }
        // 父类负责把方块实体里的组件掉出来（AEBaseEntityBlock#onRemove → addAdditionalDrops）。
        super.onRemove(state, level, pos, newState, isMoving);
    }

    /**
     * 掉落：本体一件。
     *
     * <p><b>为什么自己给而不走掉落表</b>：原先每个档位都要一份 {@code data/<ns>/loot_table/...}，而脚本与第三方
     * 注册的档位没法复用它（脚本那边连一份能抄的都没有），抄漏了也不报错，只表现为挖下来什么都不掉。
     * 改到这里之后，谁注册的模块方块都不需要数据包。</p>
     *
     * <p><b>为什么是 {@code getDrops} 而不是 {@code playerDestroy}</b>：走掉落表的不只「玩家挖掘」——爆炸、
     * {@code Level#destroyBlock}（AE2 的扳手拆解走的就是它）、第三方工具的批量破坏都从 {@code getDrops} 取。
     * 只盖 {@code playerDestroy} 会让那些路径<b>把本体吞掉</b>（方块没了、物品也没了）。</p>
     *
     * <p><b>返回的必须是可变列表</b>：AE2 的扳手拆解（{@code AEBaseBlockEntity#disassembleWithWrench}）拿到
     * 这份返回值后，会把它原样交给 {@code addAdditionalDrops} 往里追加方块实体库存（我们的组件槽），
     * 而那个方法里执行的是 {@code drops.add(stack)}。交一个 {@code List.of(...)} 过去，一有组件就抛
     * {@code UnsupportedOperationException}——它在 {@code removeBlock} 之前，于是方块拆不掉。
     * 库里没组件时那个 {@code add} 不会被调到，所以症状看起来是「只有存了组件才拆不下来」。</p>
     *
     * <p>组件不在这里给：它们由父类的 {@code addAdditionalDrops} 交出来（经 {@code onRemove}）。</p>
     */
    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        var drops = new ArrayList<ItemStack>(1);
        if (asItem() == Items.AIR) {
            return drops;
        }
        var drop = new ItemStack(asItem());
        // 照父类同一口径把方块实体的设置套上（内存卡命名过的模块，拆下来仍带着那个名字）。
        // 不调 super 是因为它那套先要问掉落表，而模块方块已经没有掉落表了。
        var lootContext = params.withParameter(LootContextParams.BLOCK_STATE, state)
                .create(LootContextParamSets.BLOCK);
        if (lootContext.getParamOrNull(LootContextParams.BLOCK_ENTITY) instanceof AEBaseBlockEntity blockEntity) {
            var looter = lootContext.getParamOrNull(LootContextParams.THIS_ENTITY);
            drop.applyComponents(blockEntity.exportSettings(SettingsFrom.DISMANTLE_ITEM,
                    looter instanceof Player player ? player : null));
        }
        drops.add(drop);
        return drops;
    }

    /** 只有这一档的方块才有对应的方块实体，取用一律经它，别在这里直接 new。 */
    @Nullable
    public EnergyCellModuleBlockEntity moduleAt(BlockGetter level, BlockPos pos) {
        return getBlockEntity(level, pos);
    }
}
