package io.github.lounode.ae2modularcells.common.block;

/**
 * 结构件：能被算进一段模块立方体的方块。
 *
 * <p>目前有两类实现：三档<b>储能模块</b>（贡献容量与速率、带组件槽）与两个<b>监控器</b>（只显示、不贡献
 * 任何数值）。检测、渲染的连接判定与结构汇总都只问这一个接口，不再问具体方块类型——否则每加一种结构件都要
 * 去改四处 instanceof。</p>
 *
 * <p><b>口径</b>：是不是结构件只看方块；「这一块算不算储能成员」再按方块实体类型分流（见
 * {@code EnergyCellModuleBlockEntity#applyStructure}）。</p>
 */
public interface StructurePart {
}
