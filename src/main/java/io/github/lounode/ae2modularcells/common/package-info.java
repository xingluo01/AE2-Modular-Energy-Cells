/**
 * 公共与服务端：方块、方块实体、菜单、结构与能量逻辑。
 *
 * <p>这一层不许出现 {@code client} 源集的类型——{@code build.gradle} 把客户端放在独立源集，误引会在
 * 编译期就断，而不是等到缺客户端的专用服务端上炸。</p>
 *
 * <p>子包：{@code common.logic}（结构检测与容量/速率公式，不碰菜单与屏幕，可单独读懂；已落地
 * {@link io.github.lounode.ae2modularcells.common.logic.CellMath}）；其余按规划——{@code common.block}
 * （三个等级的方块）、{@code common.block.entity}（方块实体，不注册 ticker）、{@code common.energy}
 * （能量池与 AE2 的 {@code IAEPowerStorage} 适配）、{@code common.menu}（菜单与其逻辑）、
 * {@code common.item}（方块物品）。</p>
 *
 * <p>组件口径：槽位里插的是现成的存储组件（AE2 的 1k ~ 256k、MEGA Cells 的 1m ~ 256m），不是本模组
 * 自制的物品；档位与数值表见 {@link io.github.lounode.ae2modularcells.common.StorageComponentTier}。</p>
 */
package io.github.lounode.ae2modularcells.common;
