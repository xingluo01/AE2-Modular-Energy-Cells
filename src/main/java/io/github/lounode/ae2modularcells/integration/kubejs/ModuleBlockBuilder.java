package io.github.lounode.ae2modularcells.integration.kubejs;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockBehaviour;

import dev.latvian.mods.kubejs.registry.AdditionalObjectRegistry;
import dev.latvian.mods.kubejs.registry.BuilderBase;

import io.github.lounode.ae2modularcells.api.ModularCellsApi;
import io.github.lounode.ae2modularcells.common.EnergyCellTier;
import io.github.lounode.ae2modularcells.common.block.EnergyCellModuleBlock;

/**
 * 脚本里的模块方块 builder：{@code event.create('my_tier_energy_cell_module', 'ae2modularcells:module')}。
 *
 * <p>五个数值就是 {@link EnergyCellTier} 的五个字段，默认值与内置的基础档一致——脚本只写关心的那几个即可。</p>
 *
 * <p>它同时是「脚本定义一个新档位」这个动作的全部：等级由它产出，方块、方块物品与方块实体类型也都由它这条链
 * 建出来（见类 javadoc 里关于入口为何是方块的说明）。</p>
 */
public class ModuleBlockBuilder extends BuilderBase<EnergyCellModuleBlock> {

    private long baseCapacity = 200_000L;
    private long baseTransferRate = 1_000L;
    private int componentSlots = 1;
    private long maxTransferRate = 16_000L;
    private long maxInputRate = 8_000L;
    private String tierId;

    private EnergyCellTier tier;
    private EnergyCellModuleBlock block;
    private ModuleItemBuilder itemBuilder;

    public ModuleBlockBuilder(ResourceLocation id) {
        super(id);
    }

    /** 单模块基础容量 A（AE）。 */
    public ModuleBlockBuilder capacity(long value) {
        this.baseCapacity = value;
        return this;
    }

    /** 单模块基础传输速率 V（AE/t）。 */
    public ModuleBlockBuilder transferRate(long value) {
        this.baseTransferRate = value;
        return this;
    }

    /** 每个模块提供的组件槽位数（本模组内置三档是 1 / 2 / 3）。 */
    public ModuleBlockBuilder componentSlots(int value) {
        this.componentSlots = value;
        return this;
    }

    /** 每块提供的速率上限 V_max（AE/t）；整段上限是逐块之和。 */
    public ModuleBlockBuilder maxTransferRate(long value) {
        this.maxTransferRate = value;
        return this;
    }

    /** 每块提供的输入速率上限 V_in_max（AE/t）；整段上限同样是逐块之和。 */
    public ModuleBlockBuilder maxInputRate(long value) {
        this.maxInputRate = value;
        return this;
    }

    /** 等级名（存档里的 tierMask 位号、语言键与贴图名都按它走）；默认是方块名去掉 {@code _energy_cell_module}。 */
    public ModuleBlockBuilder tierId(String value) {
        this.tierId = value;
        return this;
    }

    String tierId() {
        if (tierId == null) {
            String path = id.getPath();
            String suffix = "_energy_cell_module";
            tierId = path.endsWith(suffix) ? path.substring(0, path.length() - suffix.length()) : path;
        }
        return tierId;
    }

    @Override
    public EnergyCellModuleBlock createObject() {
        return block();
    }

    /**
     * 惰性建一次。
     *
     * <p>方块实例被三处需要：方块注册表落盘、方块物品的构造、以及方块实体类型建好后回填给它。而这三张注册表的
     * 处理顺序由 NeoForge 决定，所以这里缓存同一个实例，谁先要都一样。</p>
     */
    EnergyCellModuleBlock block() {
        if (block == null) {
            block = new EnergyCellModuleBlock(tier(), BlockBehaviour.Properties.of()
                    .strength(2.5F, 6.0F)
                    .requiresCorrectToolForDrops());
        }
        return block;
    }

    /**
     * 等级实例只建一次，并与方块共用同一个。
     *
     * <p>建出来就地注册不了（此刻还没轮到等级注册表，KubeJS 的落盘只认当前注册表），所以交给本模组在等级注册表
     * 那一次 {@code RegisterEvent} 补注册。共用实例是硬要求：启动期会按恒等比对「套装声明的等级」与
     * 「方块自报的等级」。</p>
     */
    EnergyCellTier tier() {
        if (tier == null) {
            tier = new EnergyCellTier(tierId(), baseCapacity, baseTransferRate, componentSlots,
                    maxTransferRate, maxInputRate);
            ModularCellsApi.registerLateTier(
                    ResourceLocation.fromNamespaceAndPath(id.getNamespace(), tierId()), tier);
        }
        return tier;
    }

    /**
     * 顺带登记方块物品与方块实体类型。
     *
     * <p>这两张注册表都比 {@code minecraft:block} 晚，所以在这里登记能落盘——这正是入口选方块的原因。
     * 三件不能分两处给：KubeJS 只对本次收集到的 builder 调一次本方法（见 {@code RegistryKubeEvent#afterPosted}
     * 的字节码），新 {@code add} 进去的 builder 不会再被回调。</p>
     */
    @Override
    public void createAdditionalObjects(AdditionalObjectRegistry registry) {
        itemBuilder = new ModuleItemBuilder(id, this);
        registry.add(Registries.ITEM, itemBuilder);
        registry.add(Registries.BLOCK_ENTITY_TYPE, new ModuleBlockEntityBuilder(id, this, itemBuilder));
    }
}
