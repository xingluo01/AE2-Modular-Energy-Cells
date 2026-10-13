package io.github.lounode.ae2modularcells.common.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import appeng.blockentity.AEBaseBlockEntity;

/**
 * 空白能量单元的方块实体。
 *
 * <p><b>它不存任何数据，存在的唯一理由是让 AE 的扳手认它。</b>AE2 的拆除钩子
 * （{@code appeng.hooks.WrenchHook#onPlayerUseBlock}）只对 {@link AEBaseBlockEntity} 的子类生效：
 *
 * <pre>
 * var be = level.getBlockEntity(pos);
 * if (be instanceof AEBaseBlockEntity baseBlockEntity) { baseBlockEntity.disassembleWithWrench(...); }
 * </pre>
 *
 * <p>AE2 没有「可扳手拆除」的接口可供实现，判定写死了基类，所以想让这个方块也能被扳手快捷拆掉，就只能给它
 * 一个基类子类。它自己不 tick、不接入 ME 网格、不开界面——方块本身仍然只是一块壳（见注册表里的说明）。</p>
 */
public class BlankEnergyUnitBlockEntity extends AEBaseBlockEntity {

    public BlankEnergyUnitBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }
}
