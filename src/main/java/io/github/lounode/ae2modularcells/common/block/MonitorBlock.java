package io.github.lounode.ae2modularcells.common.block;

import java.util.concurrent.atomic.AtomicReference;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

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
public class MonitorBlock extends Block implements EntityBlock, StructurePart {

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

    public Kind kind() {
        return this.kind;
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
