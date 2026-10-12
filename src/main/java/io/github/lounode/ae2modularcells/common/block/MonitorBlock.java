package io.github.lounode.ae2modularcells.common.block;

import java.util.concurrent.atomic.AtomicReference;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.item.context.BlockPlaceContext;

import com.mojang.serialization.MapCodec;

import io.github.lounode.ae2modularcells.common.block.entity.EnergyCellModuleBlockEntity;
import io.github.lounode.ae2modularcells.common.block.entity.MonitorBlockEntity;

/**
 * 能量监控器 / 能量流量监控器：**只显示、不产出**——不提供储能、也不提供传输速率（用户口径）。
 *
 * <p>它不参与容量与速率求和；拼进立方体时算结构件（实现 {@link StructurePart}，参与成型判定但不计入账目），
 * 所以拼进立方体不会破坏结构。读数（储能百分比 / FE 输入流量与吞吐量）由正面渲染从核心账目现取，本类只负责
 * 「是哪一种监控器」与方块实体类型的回填。</p>
 *
 * <p><b>一个口径要注意</b>：立方体最小角必须是储能模块（见
 * {@code EnergyCellModuleBlockEntity#rebuildStructure}）——最小角是监控器时整段不算成型，因为核心必须是一块
 * 有账目的储能模块。</p>
 *
 * <p><b>没有 ticker</b>：方块实体类型两处传 null，与模块方块同一条红线。</p>
 */
public class MonitorBlock extends HorizontalDirectionalBlock implements EntityBlock, StructurePart {

    /** 监控器的两种用途。 */
    public enum Kind {
        /** 正面显示整段储能百分比。 */
        ENERGY,
        /** 正面显示 FE 输入流量与吞吐量（AE/t）。 */
        FLOW
    }

    private final Kind kind;

    /** 方块实体类型回填位：方块先注册、类型后注册，构造时拿不到自己的类型（照抄模块那套做法）。 */
    private final AtomicReference<BlockEntityType<MonitorBlockEntity>> blockEntityType = new AtomicReference<>();

    public MonitorBlock(Kind kind, Properties properties) {
        super(properties);
        this.kind = kind;
    }

    /**
     * 放置：让四周的模块重扫一遍结构。
     *
     * <p>监控器自己不持有账目（没有能重算结构的方块实体），所以不能像模块方块那样「从自己出发检测」
     * （{@code rebuildAt} 只认模块方块实体）。它只负责把事件播出去——否则「最后一块放的监控器」永远不会触发
     * 重扫，一整段就停在不成型。</p>
     */
    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level.isClientSide() || oldState.is(state.getBlock())) {
            return;
        }
        // 与模块方块同一套：延后一格再扫，这时方块实体已经建好。
        level.scheduleTick(pos, this, 1);
    }

    /** 延后一格的重扫：见 {@link #onPlace}。 */
    @Override
    public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        pokeNeighbours(level, pos);
    }

    /**
     * 破坏：结构少了一块，让四周的模块重扫。
     *
     * <p>与模块方块被拆同一条路径：邻块的检测会失败，于是整段自动回到独立元件（{@code clearStructure} 会清整段，
     * 不只是清自己）。</p>
     */
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!level.isClientSide() && !newState.is(state.getBlock())) {
            pokeNeighbours(level, pos);
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    /** 把「这里变了」告诉四周的模块——重算入口只在模块方块实体上。 */
    private static void pokeNeighbours(Level level, BlockPos pos) {
        var cursor = new BlockPos.MutableBlockPos();
        for (var direction : Direction.values()) {
            EnergyCellModuleBlockEntity.rebuildAt(level, cursor.setWithOffset(pos, direction));
        }
    }

    public Kind kind() {
        return this.kind;
    }

    /**
     * 储能档位（0~4）：与模块同一个属性，由核心的 {@code updateFillLevels} 一起写。
     *
     * <p>监控器靠它选覆盖层：0 档 → {@code monitor_light_dark}，1~3 档 → {@code monitor_light_medium}，
     * 4 档 → {@code monitor_light_bright}（用户口径：覆盖层按储能档位三选一）。</p>
     */
    public static final IntegerProperty FILL = IntegerProperty.create("fill", 0, 4);

    /**
     * 成型与否：与模块同一口径（照 AE2 合成单元，成型态走方块状态）。
     *
     * <p>未成型时监控器六面都是空白单元贴图；成型后正面换成基础正面并叠覆盖层。</p>
     */
    public static final BooleanProperty FORMED = BooleanProperty.create("formed");
    /**
     * 正面朝玩家（原版水平朝向约定），模型用 blockstate 的 4 个 y 旋转变体摆放。
     *
     * <p>朝向只决定「读数画在哪一面」：结构检测与能量层面完全不看它。</p>
     */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, FILL, FORMED);
    }

    /** 方块编解码器：{@code HorizontalDirectionalBlock} 把它声明成抽象方法，这里必须给一个。 */
    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return simpleCodec(properties -> new MonitorBlock(this.kind, properties));
    }

    public void setBlockEntityType(BlockEntityType<MonitorBlockEntity> type) {
        this.blockEntityType.setPlain(type);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        var type = this.blockEntityType.get();
        // 注册顺序被改坏时宁可当场炸，也不要静默造出一个不属于任何类型的方块实体。
        if (type == null) {
            throw new IllegalStateException("监控器方块实体类型还没回填：" + state);
        }
        return new MonitorBlockEntity(type, pos, state);
    }
}
