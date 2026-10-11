package io.github.lounode.ae2modularcells.common.menu;

import appeng.menu.SlotSemantic;
import appeng.menu.SlotSemantics;

import io.github.lounode.ae2modularcells.AE2ModularCells;
import io.github.lounode.ae2modularcells.common.ModularCellConstants;
import io.github.lounode.ae2modularcells.common.logic.TerminalRows;

/**
 * 模块管理终端里那批槽位的语义（语义名就是样式文档里的键，改名字等于改协议）。
 *
 * <p>面板最多 12 条 18px 带（最少 4 条，实际条数由「终端样式」档位与窗口高度定，见屏幕类），每条带 3 列；
 * 每列是「1 个模块槽 + 3 个组件槽」——贴图那一行 12 格就是「模块槽、组件槽、组件槽、组件槽」重复三次
 * （列 0/4/8 是模块槽）。组件槽固定排三格是刻意的：基础模块只有 1 个槽，多出来的两格由屏幕盖上「不可用槽」
 * 覆盖层，于是滚动时每行的格数恒定，格子不会跳。</p>
 *
 * <p><b>槽位与「带」是绑定关系，与内容无关</b>：第 {@code i} 个模块槽永远在第 {@code i / 3} 条带上，槽位坐标
 * 写死在样式文档里、运行期不改（AE2 的槽位坐标在 {@code init()} 期解析一次，每帧改 {@code Slot.x/y} 会与槽的
 * 背景绘制错位）。行表里哪条带是标题行、哪条是模块行由内容决定（见 {@link TerminalRows}），屏幕只负责把用不到
 * 的那些槽隐藏起来。</p>
 *
 * <p>每条带因此有三套槽：模块行的「模块槽 + 组件槽」与标题行的「标题图标槽」。同一时刻只有一套在用，另一套
 * 由屏幕隐藏——标题行上出现空模块格（以及模块行上出现空标题格）都很难看，而玩家也不该点到它们。</p>
 */
public final class EnergyModuleSlotSemantics {

    /** 面板最少显示几条带（终端样式 SMALL 的档位）。 */
    public static final int MIN_ROWS = 4;

    /** 面板最多显示几条带：注册的槽位数按它来，超出的部分运行时隐藏。 */
    public static final int MAX_ROWS = 12;

    /** 每条带里有几个模块槽（贴图里第 1、5、9 格）。 */
    public static final int MODULES_PER_ROW = TerminalRows.MODULES_PER_ROW;

    /** 全部带上的模块槽/组件语义总数（含运行时被隐藏的那些）。 */
    public static final int MAX_MODULE_SLOTS = MAX_ROWS * MODULES_PER_ROW;

    /** 每个模块预留的组件格数：取三档里最多的那个，见 {@link ModularCellConstants#maxComponentSlots()}。 */
    public static final int COMPONENT_SLOTS_PER_MODULE = ModularCellConstants.maxComponentSlots();

    /** 模块展示槽：一格一个模块，选中它不会拿走东西（只读）。 */
    public static final SlotSemantic[] MODULE = new SlotSemantic[MAX_MODULE_SLOTS];

    /** 组件槽：每个模块三格横排。 */
    public static final SlotSemantic[] COMPONENTS = new SlotSemantic[MAX_MODULE_SLOTS];

    /** 标题行的等级图标槽：每条带一个，与第 1 列的模块格对齐；不是标题行时隐藏。 */
    public static final SlotSemantic[] TITLE_ICON = new SlotSemantic[MAX_ROWS];

    static {
        for (int index = 0; index < MAX_MODULE_SLOTS; index++) {
            // 语义名要带自己的 mod id 前缀，AE2 的注册表是全局的。
            MODULE[index] = SlotSemantics.register(AE2ModularCells.MOD_ID + ":module_" + index, false);
            COMPONENTS[index] = SlotSemantics.register(AE2ModularCells.MOD_ID + ":module_components_" + index, false);
        }
        for (int row = 0; row < MAX_ROWS; row++) {
            TITLE_ICON[row] = SlotSemantics.register(AE2ModularCells.MOD_ID + ":module_title_" + row, false);
        }
    }

    /** 第 {@code row} 条带的模块槽语义（3 个）。 */
    public static SlotSemantic moduleSemantic(int row, int column) {
        return MODULE[row * MODULES_PER_ROW + column];
    }

    private EnergyModuleSlotSemantics() {
    }
}
