package io.github.lounode.ae2modularcells.common.block;

import net.minecraft.world.level.block.state.BlockBehaviour;

import appeng.block.AEBaseEntityBlock;

import io.github.lounode.ae2modularcells.common.block.entity.BlankEnergyUnitBlockEntity;

/**
 * 空白能量单元方块。
 *
 * <p>它本身只是一块壳：不储能、不成型、不接入 ME 网格、不开界面。唯一需要写代码的地方是**能被 AE 的扳手
 * 快捷拆掉**——AE 的扳手钩子（{@code appeng.hooks.WrenchHook}）只认 {@code AEBaseBlockEntity} 的子类，
 * 所以这里继承 AE 的方块基类、配一个没有任何数据的方块实体（见 {@link BlankEnergyUnitBlockEntity}），
 * 而不是用原版 {@code Block}。</p>
 */
public class BlankEnergyUnitBlock extends AEBaseEntityBlock<BlankEnergyUnitBlockEntity> {

    public BlankEnergyUnitBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }
}
