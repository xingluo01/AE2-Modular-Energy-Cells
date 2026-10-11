package io.github.lounode.ae2modularcells.common.block;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import appeng.block.AEBaseEntityBlock;
import appeng.menu.MenuOpener;
import appeng.menu.locator.MenuLocators;

import io.github.lounode.ae2modularcells.common.EnergyCellTier;
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
public class EnergyCellModuleBlock extends AEBaseEntityBlock<EnergyCellModuleBlockEntity> {

    private final EnergyCellTier tier;

    public EnergyCellModuleBlock(EnergyCellTier tier, Properties properties) {
        super(properties);
        this.tier = tier;
    }

    /** 这一档的等级。结构检测、容量汇总与菜单展示都按它取数值。 */
    public EnergyCellTier tier() {
        return this.tier;
    }

    /**
     * 这一块状态是不是模块方块（不分等级）。
     *
     * <p><b>渲染的连接判定与结构检测共用它</b>，两处口径必须一致：结构成立与否不看等级（见
     * {@code MultiblockScanner}），那连接边框也不能看等级——否则一段混拼的立方体画面互不相连、功能上却是一整
     * 块电池，玩家看不出它已经成型了。</p>
     *
     * <p>曾经的口径是「同等级才算相邻」；在允许混等级之后，那个口径会把混拼结构的边框全画成断开。</p>
     */
    public static boolean isModule(@Nullable BlockState state) {
        return state != null && state.getBlock() instanceof EnergyCellModuleBlock;
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
            EnergyCellModuleBlockEntity.onModuleRemoved(level, pos);
        }
        // 父类负责把方块实体里的组件掉出来（AEBaseEntityBlock#onRemove → addAdditionalDrops）。
        super.onRemove(state, level, pos, newState, isMoving);
    }

    /** 只有这一档的方块才有对应的方块实体，取用一律经它，别在这里直接 new。 */
    @Nullable
    public EnergyCellModuleBlockEntity moduleAt(BlockGetter level, BlockPos pos) {
        return getBlockEntity(level, pos);
    }
}
