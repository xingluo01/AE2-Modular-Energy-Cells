package io.github.lounode.ae2modularcells.integration.kubejs;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;

import dev.latvian.mods.kubejs.plugin.KubeJSPlugin;
import dev.latvian.mods.kubejs.registry.BuilderTypeRegistry;

import io.github.lounode.ae2modularcells.AE2ModularCells;

/**
 * KubeJS 插件入口：给 {@code minecraft:block} 加一个本模组的具名 builder 变体。
 *
 * <p>发现方式是在 {@code resources/kubejs.plugins.txt} 里点名（KubeJS 7.0 起 {@link KubeJSPlugin} 是接口，
 * 所有回调都有默认实现，所以只需覆写用到的那一个）。</p>
 *
 * <h2>为什么入口是「方块」而不是「等级」</h2>
 * <p>直觉上该让脚本注册等级、等级 builder 顺带把方块建出来。但 KubeJS 的落盘只认「当前正在注册的那个注册表」
 * ——它的 {@code RegistryEventHandler#handleRegistryEvent} 取出 {@code RegistryObjectStorage.of(key)}，然后逐个
 * {@code event.register}——而 {@code minecraft:block} 这类 vanilla 注册表的 {@code RegisterEvent} 早于本模组的
 * 自建注册表。于是等级 builder 里 {@code add(Registries.BLOCK, ...)} 等于往一个早已处理完的注册表里塞东西：
 * <b>被静默丢弃，方块根本不会出现</b>（实测如此，等级建出来了、方块没有）。</p>
 *
 * <p>反过来走得通：方块注册表最早，而 {@code minecraft:item} 与 {@code minecraft:block_entity_type} 都比它晚，
 * 所以以方块为入口、在它的 {@code createAdditionalObjects} 里登记那两件能落盘。等级更晚，于是由方块 builder
 * 产出实例、交给本模组在等级注册表那一次 {@code RegisterEvent} 补注册
 * （{@code ModularCellsApi#registerScriptTiers}）。</p>
 *
 * <p>脚本用法（{@code startup_scripts}）：</p>
 *
 * <pre>{@code
 * StartupEvents.registry('block', event => {
 *     event.create('my_tier_energy_cell_module', 'ae2modularcells:module')
 *         .capacity(400000)
 *         .transferRate(2000)
 *         .componentSlots(2)
 *         .maxTransferRate(32000)
 *         .maxInputRate(16000)
 * })
 * }</pre>
 *
 * <p>等级名默认取方块名去掉 {@code _energy_cell_module} 后缀，可用 {@code tierId('...')} 覆盖。贴图、方块状态
 * JSON 与语言键仍要自己准备，口径见 {@code docs/ARCHITECTURE.md} §5.1。</p>
 */
public class ModularCellsKubeJSPlugin implements KubeJSPlugin {

    /** 脚本里第二个参数用的类型名：{@code event.create('...', 'ae2modularcells:module')}。 */
    public static final ResourceLocation MODULE_BLOCK_TYPE = AE2ModularCells.makeId("module");

    @Override
    public void registerBuilderTypes(BuilderTypeRegistry registry) {
        registry.of(Registries.BLOCK, callback -> callback.add(
                MODULE_BLOCK_TYPE, ModuleBlockBuilder.class, ModuleBlockBuilder::new));
    }
}
