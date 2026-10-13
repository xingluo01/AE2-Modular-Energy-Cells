package io.github.lounode.ae2modularcells.integration.kubejs;

import dev.latvian.mods.kubejs.plugin.KubeJSPlugin;
import dev.latvian.mods.kubejs.registry.BuilderTypeRegistry;

import io.github.lounode.ae2modularcells.common.ModularCellTiers;

/**
 * KubeJS 插件入口：把本模组的等级注册表接到脚本的 {@code StartupEvents.registry} 上。
 *
 * <p>发现方式是在 {@code resources/kubejs.plugins.txt} 里点名（KubeJS 7.0 起 {@link KubeJSPlugin} 是接口，
 * 所有回调都有默认实现，所以只需覆写用到的那一个）。</p>
 *
 * <p>只注册等级这一种 builder：配套的方块、方块物品与方块实体类型由等级 builder 自己在
 * {@code createAdditionalObjects} 里登记（那是 KubeJS 唯一会对 builder 调用的钩子，见字节码
 * {@code RegistryKubeEvent#afterPosted}），所以不需要——也不应该——再给 {@code minecraft:block} 之类的注册表
 * 换默认 builder，那会和 KubeJS 自己的 {@code BlockBuilder} 打架。</p>
 */
public class ModularCellsKubeJSPlugin implements KubeJSPlugin {

    @Override
    public void registerBuilderTypes(BuilderTypeRegistry registry) {
        registry.of(ModularCellTiers.TIER_REGISTRY_KEY,
                callback -> callback.addDefault(EnergyCellTierBuilder.class, EnergyCellTierBuilder::new));
    }
}
