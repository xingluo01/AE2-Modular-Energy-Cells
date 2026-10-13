/**
 * KubeJS 集成：让整合包脚本不写 Java 也能加等级与它的模块方块。
 *
 * <p>隔离口径与本仓其他邻居集成一致：本包里全是邻居的类型名，而它们**只被 KubeJS 自己加载**——
 * {@code resources/kubejs.plugins.txt} 里那一行是唯一的入口，KubeJS 不在场时那个文件没有消费者，
 * 于是本包的类一个都不会被载入，缺模组时也就不会在加载阶段炸。</p>
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
 * <p>入口是方块而不是等级，原因见 {@link ModularCellsKubeJSPlugin} 的类 javadoc：KubeJS 的落盘只认「当前正在
 * 注册的那个注册表」。等级名默认取方块名去掉 {@code _energy_cell_module} 后缀。</p>
 *
 * <p>贴图（两张，按等级 id 命名）、方块状态与物品模型 JSON（或照抄内置三档那几份）与语言键仍要自己准备。
 * <b>掉落不用管</b>——本体与组件都由模组自己给，与数据包无关。口径见 {@code docs/ARCHITECTURE.md} §5.1。</p>
 */
package io.github.lounode.ae2modularcells.integration.kubejs;
