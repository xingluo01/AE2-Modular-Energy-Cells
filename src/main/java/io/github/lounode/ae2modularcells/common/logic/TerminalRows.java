package io.github.lounode.ae2modularcells.common.logic;

import java.util.List;
import java.util.function.IntFunction;

import org.jetbrains.annotations.Nullable;

import io.github.lounode.ae2modularcells.common.EnergyCellTier;
import io.github.lounode.ae2modularcells.common.ModularCellTiers;

/**
 * 模块管理终端里的「行」：一段结构被排成一张按等级分组的平坦行表。
 *
 * <pre>
 * [标题行：模块化基础能量单元]   ← 一个等级组只出现一次
 * [模块行：3 个模块]
 * [模块行：3 个模块]
 * [标题行：模块化致密能量单元]
 * [模块行：3 个模块]
 * ...
 * </pre>
 *
 * <p><b>行表是平坦的、行与行互相独立</b>：标题行与模块行都是普通的一行，各占一条 18px 带。标题行不「绑」在它
 * 下面那条模块行上，屏幕按每一行自己的角色与它在窗口里的位置（首/中/末）去挑带——分组多少、每组几行都能自然
 * 表达，不必为「标题 + 槽」这种固定配对预留位置。</p>
 *
 * <p><b>分组口径</b>：每个等级一个组（用户口径），组的顺序固定为 {@link EnergyCellTier#values()} 的顺序；
 * 组内模块保持结构遍历顺序（{@link MultiblockScanner.Layout#modulePos} 的 y → z → x），于是滑动窗口里看到的
 * 模块次序是稳定的——同一个模块不会因为重算而跳到别处。</p>
 *
 * <p>只算行、不看世界：调用方给一个「第 i 个模块是哪个等级」的函数，本类只负责分组与切块，所以它能单独读懂、
 * 也能单独验。</p>
 */
public final class TerminalRows {

    /** 一条模块行放几个模块（贴图里一行 12 格 = 3 × 「1 模块格 + 3 组件格」）。 */
    public static final int MODULES_PER_ROW = 3;

    private TerminalRows() {
    }

    /**
     * 行表里的一行。
     *
     * @param title         标题行：这一行要显示的等级；{@code null} 表示这是模块行
     * @param firstModule   模块行：本行第一个模块在结构遍历顺序里的序号（标题行恒为 0）；仅供诊断，取模块请看 {@link #moduleIndex(int)}
     * @param moduleCount   模块行：本行的模块数（1..{@link #MODULES_PER_ROW}；标题行恒为 0）
     * @param moduleIndices 模块行：本行那几个模块在结构遍历顺序里的序号，长度 = {@code moduleCount}；**调用方不得改写**（record 持的是同一个数组引用）
     *                      —— 同一档的模块在遍历序里通常不连续，只记「第一个」会让取第 2、3 个时拿到别档的模块
     */
    public record Row(@Nullable EnergyCellTier title, int firstModule, int moduleCount, int[] moduleIndices) {

        /** 标题行/空行的空下标表。 */
        private static final int[] NO_MODULES = new int[0];

        public static Row title(EnergyCellTier tier) {
            return new Row(tier, 0, 0, NO_MODULES);
        }

        public boolean isTitle() {
            return title != null;
        }

        /** 本行第 {@code column} 个模块在结构遍历序里的下标；越界或不是模块行给 -1。 */
        public int moduleIndex(int column) {
            return column >= 0 && column < moduleIndices.length ? moduleIndices[column] : -1;
        }
    }

    /**
     * 按等级分组排出整张行表。
     *
     * @param moduleCount 结构里的模块数
     * @param tierAt      第 {@code i} 个模块的等级；返回 {@code null} 表示那块不是模块（拿不到就算了，不排进任何组）
     * @param out         行表输出（会先清空）
     */
    public static void build(int moduleCount, IntFunction<EnergyCellTier> tierAt, List<Row> out) {
        out.clear();
        if (moduleCount <= 0) {
            return;
        }
        // 外层按等级、内层按结构顺序：分组顺序因此是确定的（等级注册顺序），与结构中各等级的分布无关。
        for (var tier : ModularCellTiers.all()) {
            int groupStart = out.size();
            int pending = 0;
            int[] indices = new int[MODULES_PER_ROW];
            for (int index = 0; index < moduleCount; index++) {
                if (tierAt.apply(index) != tier) {
                    continue;
                }
                indices[pending] = index;
                pending++;
                if (pending == MODULES_PER_ROW) {
                    // 拷一份再交出去：indices 是复用的缓冲区，直接交会被下一行覆写。
                    out.add(new Row(null, indices[0], pending, indices.clone()));
                    pending = 0;
                }
            }
            if (pending > 0) {
                var tail = new int[pending];
                System.arraycopy(indices, 0, tail, 0, pending);
                out.add(new Row(null, tail[0], pending, tail));
            }
            if (out.size() > groupStart) {
                // 这一档确实有模块：在它的模块行前面插一条标题行。
                out.add(groupStart, Row.title(tier));
            }
        }
    }
}
