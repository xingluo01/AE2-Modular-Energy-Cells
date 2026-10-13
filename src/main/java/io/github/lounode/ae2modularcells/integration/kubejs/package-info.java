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
 * StartupEvents.registry('ae2modularcells:energy_cell_tier', event => {
 *     event.create('my_tier')
 *         .capacity(400000)
 *         .transferRate(2000)
 *         .componentSlots(2)
 *         .maxTransferRate(32000)
 *         .maxInputRate(16000)
 * })
 * }</pre>
 *
 * <p>默认会一并建出配套的模块方块、方块物品与方块实体类型。贴图、方块状态 JSON 与语言键仍要自己准备，
 * 口径见 {@code docs/ARCHITECTURE.md} §5.1。</p>
 */
package io.github.lounode.ae2modularcells.integration.kubejs;
