package io.github.lounode.ae2modularcells.common.logic;

import java.util.List;
import java.util.function.IntFunction;

import io.github.lounode.ae2modularcells.common.EnergyCellTier;
import io.github.lounode.ae2modularcells.common.ModularCellTiers;

/**
 * 模块管理终端里的「行」：一段结构按等级分组排成一张平坦行表。
 *
 * <pre>
 * [标题行：模块化基础能量单元]   ← 一档一条：图标、名称、该档模块数、已装配组件数
 * [组件行：12 格存储组件槽]
 * [组件行：12 格存储组件槽]      ← 上一行的 12 格占满（该档已装 12 种以上组件）才延伸出来
 * [标题行：模块化致密能量单元]
 * [组件行：12 格存储组件槽]
 * ...
 * </pre>
 *
 * <p><b>行表是平坦的、行与行互相独立</b>：标题行与组件行都是普通的一行，各占一条 18px 带。标题行不「绑」在它
 * 下面那条组件行上，屏幕按每一行自己的角色与它在窗口里的位置（首/中/末）去挑带——分组多少、每组几行都能自然
 * 表达，不必为「标题 + 槽」这种固定配对预留位置。</p>
 *
 * <p>分组口径：每个等级一个组（用户口径），组的顺序固定为等级的固定位号顺序（见
 * {@link ModularCellTiers#indexOf}），所以滑动窗口里看到的次序是稳定的——同一档不会因为重算而跳到别处。</p>
 *
 * <p><b>组件行按「已装组件种类数」延伸</b>：一行 12 格，一档装了多少种组件就要多少行，至少一行（空档也留一行，
 * 否则玩家没地方放第一个组件）。于是行表不只取决于结构，还取决于组件内容——组件一变行表就可能变，调用方的
 * 缓存必须按内容失效，不能只挂结构账目。</p>
 *
 * <p>只算行、不看世界：调用方给两个「按固定位号索引」的函数（这一档有几块、这一档装了几种组件），本类只负责
 * 分组与切块，所以它能单独读懂、也能单独验。</p>
 */
public final class TerminalRows {

    /** 一条组件行的格数：贴图里那条带就是 12 格等距（18px 一格），现在整行都作组件槽。 */
    public static final int SLOTS_PER_ROW = 12;

    private TerminalRows() {
    }

    /**
     * 行表里的一行。
     *
     * @param tier      这一行属于哪一档
     * @param title     {@code true} = 标题行（带一个等级图标，没有组件格）；{@code false} = 组件行
     * @param firstKind 组件行：本行第 1 格对应该档第<b>几</b>种组件（从 0 数；标题行恒为 0）。格子与类型的
     *                  对应关系全在这一个数上——一行 12 格，第 2 行就从第 12 种接下去
     */
    public record Row(EnergyCellTier tier, boolean title, int firstKind) {

        public static Row title(EnergyCellTier tier) {
            return new Row(tier, true, 0);
        }

        public static Row slots(EnergyCellTier tier, int firstKind) {
            return new Row(tier, false, firstKind);
        }
    }

    /**
     * 按等级分组排出整张行表。
     *
     * @param moduleCounts 每个等级（按固定位号索引）有多少块；{@code <= 0} 表示这一段里没有这一档，整组不出现
     * @param kindsAt      每个等级（按固定位号索引）已装了<b>几种</b>存储组件（同种算一种，与件数无关）
     * @param out          行表输出（会先清空）
     */
    public static void build(IntFunction<Integer> moduleCounts, IntFunction<Integer> kindsAt, List<Row> out) {
        out.clear();
        var tiers = ModularCellTiers.all();
        for (int index = 0; index < tiers.size(); index++) {
            Integer modules = moduleCounts.apply(index);
            if (modules == null || modules <= 0) {
                continue;
            }
            var tier = tiers.get(index);
            out.add(Row.title(tier));
            Integer kindsValue = kindsAt.apply(index);
            int kinds = kindsValue == null ? 0 : Math.max(0, kindsValue);
            // 至少一行：空档也要有 12 个空格子，否则玩家没地方放第一个组件。
            int lines = Math.max(1, (kinds + SLOTS_PER_ROW - 1) / SLOTS_PER_ROW);
            for (int line = 0; line < lines; line++) {
                out.add(Row.slots(tier, line * SLOTS_PER_ROW));
            }
        }
    }
}
