package io.github.lounode.ae2modularcells.integration.jade;

import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;
import snownee.jade.api.view.HideThingsExtensionProvider;

import io.github.lounode.ae2modularcells.common.block.entity.EnergyCellModuleBlockEntity;

/**
 * Jade 适配：只做一件事——把 Jade <b>通用 FE 能量条</b>对本模组的方块挡掉。
 *
 * <p><b>为什么需要它</b>：本模组给方块实体注册了 NeoForge 的 FE 能力（{@code Capabilities.EnergyStorage.BLOCK}，
 * 只进不出，给外部 FE 电缆/机器充能）。Jade 本体有一个通用提供者 {@code snownee.jade.addon.universal.
 * EnergyStorageProvider}（uid {@code minecraft:energy_storage}）只认那个能力，于是浮层会多出一条 FE 容量条
 * ——而本模组的能量面归 AE 网格（{@code IAEPowerStorage}），FE 只是入口，那条条子报的数是「这一块能进来多少」，
 * 不是储量，留着只会误导。</p>
 *
 * <p><b>官方钩子</b>：Jade 没有「隐藏某个 provider」的 API，官方写法是给目标注册一个<b>空扩展提供者</b>
 * {@link HideThingsExtensionProvider}——它的 {@code getGroups()} 返回非 null 的空列表，Jade 读到空组就不再渲染
 * 那条条子。它按类注册、只影响本模组的方块实体；方块别的浮层行（AE2 自己的储能行、本模组的结构行）不受影响。</p>
 *
 * <p><b>不引邻居类型名进总是加载的类</b>：本类放在 {@code integration/jade}，全仓没有任何总是加载的代码引用它
 * ——Jade 自己在加载完成后扫描 {@code @WailaPlugin} 才会把本类加载进来。Jade 缺席时本类永远不会被加载，也就
 * 不会因为 {@code snownee.jade.*} 缺失而在加载阶段炸。编译期依赖见 build.gradle 的 {@code compileOnly(libs.jade)}。</p>
 */
@WailaPlugin
public final class ModularCellsJadePlugin implements IWailaPlugin {

    @Override
    public void register(IWailaCommonRegistration registration) {
        // 抢在 Jade 通用 FE 读取之前给出「空组」：同目标下按优先级升序取第一个非 null 组，
        // 本注册用默认优先级，而通用 FE 扩展的优先级排在其后（见 Jade 源码 CommonProxy#getServerExtensionData）。
        registration.registerEnergyStorage(HideThingsExtensionProvider.instance(),
                EnergyCellModuleBlockEntity.class);
        // 同理挡掉通用「物品存储」行：我们的 ItemHandler 是整段结构的组件槽视图（上界 12288 槽），
        // Jade 每趟只收 108 槽，结构一大那行就会「逐步增加」；且它不是玩家可用的容器。见 HideItemStorageProvider。
        registration.registerItemStorage(HideItemStorageProvider.instance(), EnergyCellModuleBlockEntity.class);
    }
}
