package io.github.lounode.ae2modularcells.integration.kubejs;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;

import dev.latvian.mods.kubejs.registry.AdditionalObjectRegistry;
import dev.latvian.mods.kubejs.registry.BuilderBase;

import io.github.lounode.ae2modularcells.api.ModularCellsApi;
import io.github.lounode.ae2modularcells.common.EnergyCellTier;

/**
 * 脚本里的等级 builder：{@code event.create('my_tier').capacity(...)}。
 *
 * <p>五个数值就是 {@link EnergyCellTier} 的五个字段，默认值与内置的基础档一致——脚本只写关心的那几个即可。</p>
 *
 * <p>默认还会一并建出配套的模块方块（含方块物品与方块实体类型）。这是刻意的：等级本身只是数值，没有方块就没有
 * 实体可放，那种等级永远不出现在结构里——所以「只加等级」几乎总是配置失误，不如默认给全。要故意不建方块用
 * {@link #withoutBlock()}。</p>
 */
public class EnergyCellTierBuilder extends BuilderBase<EnergyCellTier> {

    private long baseCapacity = 200_000L;
    private long baseTransferRate = 1_000L;
    private int componentSlots = 1;
    private long maxTransferRate = 16_000L;
    private long maxInputRate = 8_000L;
    private boolean withBlock = true;
    private EnergyCellTier tier;

    public EnergyCellTierBuilder(ResourceLocation id) {
        super(id);
    }

    /** 单模块基础容量 A（AE）。 */
    public EnergyCellTierBuilder capacity(long value) {
        this.baseCapacity = value;
        return this;
    }

    /** 单模块基础传输速率 V（AE/t）。 */
    public EnergyCellTierBuilder transferRate(long value) {
        this.baseTransferRate = value;
        return this;
    }

    /** 每个模块提供的组件槽位数（本模组内置三档是 1 / 2 / 3）。 */
    public EnergyCellTierBuilder componentSlots(int value) {
        this.componentSlots = value;
        return this;
    }

    /** 每块提供的速率上限 V_max（AE/t）；整段上限是逐块之和。 */
    public EnergyCellTierBuilder maxTransferRate(long value) {
        this.maxTransferRate = value;
        return this;
    }

    /** 每块提供的输入速率上限 V_in_max（AE/t）；整段上限同样是逐块之和。 */
    public EnergyCellTierBuilder maxInputRate(long value) {
        this.maxInputRate = value;
        return this;
    }

    /** 只注册等级，不建配套方块。 */
    public EnergyCellTierBuilder withoutBlock() {
        this.withBlock = false;
        return this;
    }

    @Override
    public EnergyCellTier createObject() {
        return tier();
    }

    /**
     * 等级实例只建一次，并与配套方块套装共用同一个。
     *
     * <p>必须共用：本模组启动期会校验「套装声明的等级」与「方块自报的等级」是同一个实例（按恒等比对），
     * 拿了两个等价实例会在启动时抛。</p>
     */
    EnergyCellTier tier() {
        if (tier == null) {
            tier = new EnergyCellTier(id.getPath(), baseCapacity, baseTransferRate, componentSlots,
                    maxTransferRate, maxInputRate);
        }
        return tier;
    }

    /**
     * 把配套的三件一起登记进各自的注册表。
     *
     * <p>必须在这里一次给全：KubeJS 只对刚创建出来的这个 builder 调一次本方法（见 {@code RegistryKubeEvent#afterPosted}
     * 的字节码——它遍历本次收集到的 builder），新 {@code add} 进来的 builder 不会再被回调，所以物品与方块实体类型
     * 不能指望由方块 builder 自己再登记。</p>
     *
     * <p>方块名与 Java 侧注册时同一口径（{@code <tierId>_energy_cell_module}，见 {@link ModularCellsApi#blockName}），
     * 这样贴图与语言键的命名规则对脚本侧也成立。</p>
     */
    @Override
    public void createAdditionalObjects(AdditionalObjectRegistry registry) {
        if (!withBlock) {
            return;
        }
        ResourceLocation blockId = ResourceLocation.fromNamespaceAndPath(id.getNamespace(),
                ModularCellsApi.blockName(id.getPath()));
        ModuleBlockBuilder blockBuilder = new ModuleBlockBuilder(blockId, this);
        ModuleItemBuilder itemBuilder = new ModuleItemBuilder(blockId, blockBuilder);
        registry.add(Registries.BLOCK, blockBuilder);
        registry.add(Registries.ITEM, itemBuilder);
        registry.add(Registries.BLOCK_ENTITY_TYPE, new ModuleBlockEntityBuilder(blockId, blockBuilder, itemBuilder));
    }
}
