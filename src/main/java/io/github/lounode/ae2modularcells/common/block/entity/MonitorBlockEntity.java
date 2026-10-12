package io.github.lounode.ae2modularcells.common.block.entity;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import java.util.Objects;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 监控器方块实体：只记「我在哪一段结构里」，自身不存能量、不算账。
 *
 * <p>两种监控器（能量 / 能量流量）共用这一个类，读出什么由方块的 {@code MonitorKind} 决定——与三档模块
 * 共用方块实体类同一套做法。</p>
 *
 * <p><b>不注册 ticker</b>：读数由客户端每帧向核心现取（正面那几行数字），不需要服务端每 tick 推。等结构成员
 * 接线（下一轮）把 {@link #corePos} 填上之后，这里也只多一个「核心在哪」的引用。</p>
 */
public class MonitorBlockEntity extends BlockEntity {

    /** 所属结构的核心；未成型、或还没接线时为 null。 */
    @Nullable
    private BlockPos corePos;

    public MonitorBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Nullable
    public BlockPos corePos() {
        return this.corePos;
    }

    public void setCorePos(@Nullable BlockPos corePos) {
        if (Objects.equals(this.corePos, corePos)) {
            return;
        }
        this.corePos = corePos;
        setChanged();
    }

    @Override
    public void saveAdditional(CompoundTag data, HolderLookup.Provider registries) {
        super.saveAdditional(data, registries);
        if (this.corePos != null) {
            data.putLong("core", this.corePos.asLong());
        }
    }

    @Override
    public void loadAdditional(CompoundTag data, HolderLookup.Provider registries) {
        super.loadAdditional(data, registries);
        this.corePos = data.contains("core") ? BlockPos.of(data.getLong("core")) : null;
    }
}
