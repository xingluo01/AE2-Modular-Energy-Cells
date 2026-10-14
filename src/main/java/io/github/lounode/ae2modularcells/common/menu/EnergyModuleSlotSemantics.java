package io.github.lounode.ae2modularcells.common.menu;

import appeng.menu.SlotSemantic;
import appeng.menu.SlotSemantics;

import io.github.lounode.ae2modularcells.AE2ModularCells;
import io.github.lounode.ae2modularcells.common.logic.TerminalRows;

/**
 * 模块管理终端里那批槽位的语义（语义名就是样式文档里的键，改名字等于改协议）。
 *
 * <p>面板最多 12 条 18px 带（最少 4 条，实际条数由「终端样式」档位与窗口高度定，见屏幕类），每条带
 * {@link #SLOTS_PER_ROW} 格存储组件槽——贴图里那一行本来就是 12 格等距（18px 一格，从 x=8 起），以前只有其中
 * 9 格是组件格、另外 3 格留给模块图标，现在整行都作组件槽，于是贴图不必重画。</p>
 *
 * <p><b>槽位与「带」是绑定关系，与内容无关</b>：第 {@code i} 条带的第 {@code j} 格永远在同一处坐标上，坐标写死在
 * 样式文档里、运行期不改（AE2 的槽位坐标在 {@code init()} 期解析一次，每帧改 {@code Slot.x/y} 会与槽的背景绘制
 * 错位）。行表里哪条带是标题行、哪条是组件行由内容决定（见 {@link TerminalRows}），屏幕只负责把用不到的那些槽
 * 隐藏起来。</p>
 *
 * <p>每条带因此有两套槽：组件行的 12 个组件格与标题行的等级图标格。同一时刻只有一套在用，另一套由屏幕隐藏
 * ——标题行上浮着 12 个空格子（以及组件行上出现一个空图标格）都很难看，而玩家也不该点到它们。</p>
 */
public final class EnergyModuleSlotSemantics {

    /** 面板最少显示几条带（终端样式 SMALL 的档位）。 */
    public static final int MIN_ROWS = 4;

    /** 面板最多显示几条带：注册的槽位数按它来，超出的部分运行时隐藏。 */
    public static final int MAX_ROWS = 12;

    /** 每条带里的组件格数。 */
    public static final int SLOTS_PER_ROW = TerminalRows.SLOTS_PER_ROW;

    /**
     * 报给原版的单格件数上限。
     *
     * <p>真实件数必须报出去：原版的左键取整格、右键取一半、shift 的搬运量都取自栈自己的数量，报 1 会让三种
     * 操作都只搬一件（格里明明显着几十个）。但它也不能无限大——超过一叠的上限就可能被上层按「一叠」处理坏掉，
     * 所以封顶 64。</p>
     *
     * <p>注意这个数字<b>只管搬运量，不管显示</b>：它是「栈里放几个」，而界面上的数字由屏幕单独画（组件槽都被
     * 关掉了原版数量，见菜单里建槽那处）。二者曾经共用一个数字，结果是超过 64 的格子会被画上两次。</p>
     */
    public static final int MAX_REPORTED_COUNT = 64;

    /** 全部带上的组件格总数（含运行时被隐藏的那些）。 */
    public static final int MAX_SLOT_COUNT = MAX_ROWS * SLOTS_PER_ROW;

    /** 存储组件槽：每条带 12 格横排（同一语义下挂 12 个槽，坐标由样式文档的 grid 排开）。 */
    public static final SlotSemantic[] COMPONENTS = new SlotSemantic[MAX_ROWS];

    /** 标题行的等级图标槽：每条带一个，与第 1 个组件格对齐；不是标题行时隐藏。 */
    public static final SlotSemantic[] TITLE_ICON = new SlotSemantic[MAX_ROWS];

    static {
        for (int band = 0; band < MAX_ROWS; band++) {
            // 语义名要带自己的 mod id 前缀，AE2 的注册表是全局的。
            COMPONENTS[band] = SlotSemantics.register(AE2ModularCells.MOD_ID + ":line_slot_" + band, false);
            TITLE_ICON[band] = SlotSemantics.register(AE2ModularCells.MOD_ID + ":module_title_" + band, false);
        }
    }

    /** 第 {@code band} 条带的组件语义（那一条带上 12 个槽共用它）。 */
    public static SlotSemantic componentSemantic(int band) {
        return COMPONENTS[band];
    }

    private EnergyModuleSlotSemantics() {
    }
}
