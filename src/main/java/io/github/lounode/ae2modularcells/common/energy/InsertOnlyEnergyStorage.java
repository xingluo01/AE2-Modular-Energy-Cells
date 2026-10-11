package io.github.lounode.ae2modularcells.common.energy;

import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * 只进不出的 FE 能力面：把「FE 输入」与「FE 存储」两件事分开。
 *
 * <pre>
 * 收   receiveEnergy      → 转交 AE2 的适配器（按整合包配置的比例换成 AE，写进单一能量池）
 * 放   extractEnergy      → 恒 0
 * 存量 getEnergyStored    → 恒 0
 * 上限 getMaxEnergyStored → 跟着 AE 容量走（见下）
 * </pre>
 *
 * <p><b>为什么不报「已存 FE」</b>：本模组不存 FE——FE 只是一个入口，进来当场就变成 AE 进了能量池。报一个
 * 「已存 FE」的数字（例如把 AE 存量换算成 FE）会让上游设备以为这里有一罐 FE 可以抽，也会让玩家以为这块方块
 * 有两份存量。所以存量恒 0、抽取恒 0。</p>
 *
 * <p><b>为什么上限仍报真实容量</b>：FE 的上游（发电机、线缆）普遍先看 {@code getMaxEnergyStored() > 0} 才肯
 * 尝试推送；上限报 0 会让它把这里当成「不能接收」而完全不推。上限跟着 AE 容量走，于是上游看到的是一个
 * 「一直是空的、但容量很大」的接收端，会持续把能量灌进来——而实际能收多少仍由 AE 侧的容量与速率决定。</p>
 *
 * <p>这个类不碰世界、不碰方块实体：它只是包一层，把「只进不出」这条口径写在一处。真正的换算比例由被包住的
 * AE2 适配器负责，所以整合包改比例时这里跟着变。</p>
 */
public final class InsertOnlyEnergyStorage implements IEnergyStorage {

    private final IEnergyStorage delegate;

    public InsertOnlyEnergyStorage(IEnergyStorage delegate) {
        this.delegate = delegate;
    }

    @Override
    public int receiveEnergy(int maxReceive, boolean simulate) {
        if (maxReceive <= 0) {
            return 0;
        }
        return this.delegate.receiveEnergy(maxReceive, simulate);
    }

    @Override
    public int extractEnergy(int maxExtract, boolean simulate) {
        // 不提供 FE 输出：本模组对外只做「FE 进来变成 AE」，抽取请走 AE2 的网格。
        return 0;
    }

    @Override
    public int getEnergyStored() {
        return 0;
    }

    @Override
    public int getMaxEnergyStored() {
        return this.delegate.getMaxEnergyStored();
    }

    @Override
    public boolean canExtract() {
        return false;
    }

    @Override
    public boolean canReceive() {
        return this.delegate.canReceive();
    }
}
