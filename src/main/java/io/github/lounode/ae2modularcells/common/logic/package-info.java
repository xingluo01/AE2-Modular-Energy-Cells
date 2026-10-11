/**
 * 结构与数值逻辑：能单独读懂的纯计算。
 *
 * <p>这里放两样东西——结构检测（{@code MultiblockScanner}）与容量/速率公式（{@link CellMath}，
 * 已落地）。共同点是不碰世界 tick、不碰菜单、不抓全局状态：检测器收一个「这个坐标是不是模块方块」的
 * 查询回调就能跑，公式收等级与组件数值就能算。</p>
 *
 * <p>把它们独立出来的实际收益在排查期：容量对不上时先读这一层，不用在方块实体、菜单与网络服务之间来回翻。</p>
 */
package io.github.lounode.ae2modularcells.common.logic;
