/**
 * 对外稳定面：给其他模组与整合包脚本用的类型。
 *
 * <p>这个包里的包路径与签名改动等于破坏兼容——改之前先在本仓 {@code CHANGELOG.md} 记一笔（与姊妹项目
 * AE2 Pattern Disk 同规矩）。</p>
 *
 * <p>内容分两类：</p>
 *
 * <ul>
 *   <li><b>注册口</b>：{@code ModularCellsApi#registerEnergyCellModule} 一次注册一个等级配套的方块、方块物品与
 *       方块实体类型（第三方 Java 侧）；{@code ModularCellsApi#registerModuleBlockSet} 收已经建好的实例
 *       （不走 {@code DeferredRegister} 的那类写法用它）；{@code ModularCellsApi#registerLateTier} 登记一个建好
 *       但还没进注册表的等级（KubeJS 那条路靠它）。它们汇进同一张清单
 *       {@code ModularCellsApi#allModuleBlockSets()}，后续处理完全一样；{@code ModularCellsApi#validateAll}
 *       在服务器启动时校验这张清单（连同 {@code ModularCellTiers#validateRegistry}）。</li>
 *   <li><b>数据对象</b>：{@code ModuleBlockSet}（清单元素，装实例）与 {@code DeferredModuleBlockSet}
 *       （Java 注册时拿到的句柄回执，含 {@code isBound()}）。命名工具是 {@code ModularCellsApi#blockName}。</li>
 * </ul>
 *
 * <p>等级本身的注册表键不在本包，在 {@code common.ModularCellTiers#TIER_REGISTRY_KEY}：用自己的命名空间建一个
 * {@code DeferredRegister} 即可。接入步骤、要自备的资源、以及失败现象对照表见 {@code docs/INTEGRATION.md}；
 * 设计动机见 {@code docs/ARCHITECTURE.md} §5。</p>
 */
package io.github.lounode.ae2modularcells.api;
