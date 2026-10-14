package io.github.lounode.ae2modularcells.client.gui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

import appeng.api.config.Settings;
import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.style.Blitter;
import appeng.client.gui.style.PaletteColor;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.widgets.Scrollbar;
import appeng.client.gui.widgets.SettingToggleButton;
import appeng.core.AEConfig;
import appeng.menu.SlotSemantic;
import appeng.menu.SlotSemantics;

import io.github.lounode.ae2modularcells.AEModularCellsRegistries;
import io.github.lounode.ae2modularcells.common.EnergyCellTier;
import io.github.lounode.ae2modularcells.common.ModularCellTiers;
import io.github.lounode.ae2modularcells.common.block.EnergyCellModuleBlock;
import io.github.lounode.ae2modularcells.common.logic.TerminalRows;
import io.github.lounode.ae2modularcells.common.menu.EnergyModuleSlotSemantics;
import io.github.lounode.ae2modularcells.common.menu.EnergyModuleTerminalMenu;

/**
 * 模块管理终端的屏幕。
 *
 * <p>布局与几何全部来自样式文档（{@code assets/ae2/screens/ae2modularcells/energy_module_terminal.json}）：
 * 代码只按 id 取贴图与槽位，不写死坐标；行高、表头与表尾的高都从取到的贴图切片反推。文档里少声明一个 id 就会
 * 在开屏时抛异常把玩家踢下线（自检脚本 {@code tools/check-screen-widgets.cjs} 就是为此存在）。</p>
 *
 * <p><b>面板高度跟着「终端样式」走</b>：样式档位决定显示几条 18px 带（SMALL 4 / MEDIUM 6 / TALL 8 /
 * FULL 填满窗口，上限 {@link EnergyModuleSlotSemantics#MAX_ROWS}）。面板因此不是固定高度，而是
 * 「表头 + N 条带 + 表尾」拼出来的——表头与表尾都是贴图切片，带是可重复的中段。表尾（玩家背包区、两条进度条、
 * 固定标签）跟着往下挪，挪的距离就是 {@link #footerDelta}。</p>
 *
 * <p><b>行分割与拼接</b>：每条带一张图，逐条贴。每条带是什么由行表决定：{@code T:<等级短 id>} 是标题行
 * （平面带 + 一个等级图标槽 + 「等级名 / 模块数量 / 已装配存储组件」三段文字），{@code S:<等级短 id>} 是组件行
 * （带 12 格槽带），{@code -} 是行表到头（或被样式档位裁掉）。带图的选取是「行角色 × 位置」两个维度：角色决定
 * 用平面带还是槽带，位置（首/中/末）决定用哪一条边线变体——两者互不隶属，所以是 2×3 的可用组合，
 * 而不是固定搭配。</p>
 *
 * <p>每条带上两套槽（12 个组件格 / 1 个标题图标格），同一时刻只有一套在用，另一套由
 * {@link #applySlotVisibility()} 隐藏——把用不到的那一套留在界面上，玩家会看到空图标格或空格子，也可能点到
 * 它们。</p>
 */
public class EnergyModuleTerminalScreen extends AEBaseScreen<EnergyModuleTerminalMenu> {

    private static final int SLOTS_PER_ROW = EnergyModuleSlotSemantics.SLOTS_PER_ROW;
    private static final int MIN_ROWS = EnergyModuleSlotSemantics.MIN_ROWS;
    private static final int MAX_ROWS = EnergyModuleSlotSemantics.MAX_ROWS;

    /** 行表到头了（这条带空着）的标记，与菜单侧的拼装约好。 */
    private static final char BAND_EMPTY = '-';
    /** 空标记的字符串形式：每帧要用好几次，定成常量免得反复 String.valueOf。 */
    private static final String EMPTY_MARK = String.valueOf(BAND_EMPTY);
    /** 标题行的前缀：{@code T:<等级短 id>}。 */
    private static final String BAND_TITLE_PREFIX = "T:";
    /** 组件行的前缀：{@code S:<等级短 id>}。 */
    private static final String BAND_SLOTS_PREFIX = "S:";

    /** 标题文字距图标槽左缘的缩进：图标 16px + 2px 间隔，文字就落在组件列的位置上。 */
    private static final int TITLE_TEXT_INSET_X = 18;
    /** 图标槽查不到时的退路：从带左缘算，8（图标槽在文档里的 left）+ 18。 */
    private static final int TITLE_TEXT_ICON_ONLY_X = 26;
    /** 一条带在界面上能用到的最右缘：最后一格（left 206 + 16）再留 2px，不碰滚动条轨道（229 起）。 */
    private static final int BAND_RIGHT_EDGE = 224;
    /** 标题文字距图标槽顶的下移：槽高 18、字高 9，落下 5 上下就均了。 */
    private static final int TITLE_TEXT_INSET_Y = 5;
    /** 等级名与后面那两段之间的间隔。 */
    private static final String TITLE_TEXT_GAP_TEXT = "  ";

    /** 标题行文字的最小缩放：三段加起来顶穿面板时缩到这里为止（再小就看不清了）。 */
    private static final float TITLE_MIN_SCALE = 0.55F;

    /** 数值右缘留白：留一点，别贴着面板边框。 */
    private static final int BAR_VALUE_RIGHT_MARGIN = 12;

    /** 数值文字的最大缩放：右下两条数值用它的宽反推缩放，小于这个值就不再缩（再小就看不清了）。 */
    private static final float VALUE_MIN_SCALE = 0.4F;

    /** 一格槽的边长（原版固定 16px，槽位坐标就是格子的左上角）。 */
    private static final int SLOT_SIZE = 16;

    /** 格内数字的内边：离格线留一点，不让笔画压在贴图的花边上。 */
    private static final int SLOT_COUNT_INSET = 2;

    /**
     * 格内数字的最小缩放。
     *
     * <p>要看住的是计数模式给的写法，不是原始数字：{@code 12.3k} 这类最多 5 字符，降到 0.35 才塞得进 12px
     * 的可用宽（0.5 时画宽约 15px，最后一列会压到滚动条轨道上）。</p>
     */
    private static final float SLOT_COUNT_MIN_SCALE = 0.35F;

    /** 被选中的等级组，整条带压一层这个透明度的选择色。 */
    private static final int SELECTION_ALPHA = 0x40;

    private final Blitter tableHeader;
    private final Blitter rowTextTop;
    private final Blitter rowTextMiddle;
    private final Blitter rowTextBottom;
    private final Blitter rowSlotsTop;
    private final Blitter rowSlotsMiddle;
    private final Blitter rowSlotsBottom;
    private final Blitter tableFooter;
    private final Blitter componentSlot;
    private final Blitter storageBar;
    private final Blitter inputBar;
    private final Blitter storageBarOverlay;
    private final Blitter inputBarOverlay;
    private final Scrollbar tableScrollbar;
    private final int textColor;
    private final int selectionColor;

    /** 表头高（也是第一条带的 y）、带高、表尾高：全部取自贴图切片。 */
    private final int tableTop;
    private final int bandHeight;
    private final int footerHeight;
    /** 表尾在贴图里的原始 y：算「挪了多远」用它当基准。 */
    private final int footerBaseY;

    /** 文档声明的锚点（未含 {@link #footerDelta}）：进度条两条数值与顶栏汇总。 */
    private final int storageValueBaseY;
    private final int inputValueBaseY;
    private final int barValueX;

    /**
     * 表尾里的三段固定文字（「物品栏」「储能：」「输入速率：」）。
     *
     * <p>它们的坐标在样式文档的 text 段里，而 text 段是绝对坐标、不会跟着表尾挪，所以构造时按 id 取到坐标与文案、
     * 把文档里那份隐藏，改由 {@link #drawFG} 按「文档坐标 + 表尾偏移」重画。取坐标而不是写死，是为了让样式文档
     * 仍然是唯一的布局源。</p>
     */
    private final FooterText playerInventoryTitle;
    private final FooterText storageLabel;
    private final FooterText inputLabel;

    /** 进度条轨道/覆盖层在贴图里的 y 就在切片里，绘制时用 {@code getSrcY() + footerDelta} 落地。 */

    /** 当前显示的条数（由样式档位与窗口高度算出来）与表尾实际 y。 */
    private int bands = MIN_ROWS;
    private int footerTop;
    private int footerDelta;

    /** 玩家槽位在样式文档里的原始 y：只记一次，之后每次重排都用「原始 y + 偏移」重算，避免反复累加漂移。 */
    private final Map<Slot, Integer> playerSlotBaseY = new HashMap<>();

    /**
     * 本帧每个带槽的解析结果（{@code -} / {@code T:<id>:<count>} / {@code M}）与拆出来的等级、模块数。
     *
     * <p>每帧解析一次存下来：绘制、标题、覆盖层、右键选组四处都要问「这条带是什么」，每次都 split 一遍就是
     * 帧内的重复分配。</p>
     */
    /**
     * 本地行表：客户端自己排（照 AE2 样板访问终端），见 {@link #refreshLocalRows()}。
     */
    private final java.util.List<io.github.lounode.ae2modularcells.common.logic.TerminalRows.Row> localRows = new java.util.ArrayList<>();

    /** 上一次用过的结构快照；变了才重排。 */
    private String lastSnapshot = "";

    /** 上一次同步给服务端的本地行数；变了才要一次内容重同步（见 updateBeforeRender）。 */
    private int syncedRows = -1;

    private final String[] bandMarks = new String[MAX_ROWS];
    /** 每条带是不是标题行。光看 {@link #bandTiers} 分不出：组件行也属于某一档。 */
    private final boolean[] bandTitleFlags = new boolean[MAX_ROWS];
    private final EnergyCellTier[] bandTiers = new EnergyCellTier[MAX_ROWS];
    private final int[] bandCounts = new int[MAX_ROWS];

    /** 标题行那两段文字的数值：已装配组件数 / 装配名额（名额 = 该档模块数 × 该档每块槽位数）。 */
    private final int[] bandAssembled = new int[MAX_ROWS];
    private final int[] bandCapacity = new int[MAX_ROWS];

    /** 本地行表的两个输入：每档模块数与已装件数，按固定位号索引；由同步快照算出来。 */
    private int[] tierModuleCounts = new int[0];
    private int[] tierAssembled = new int[0];

    /** 每个组件格已装的件数（下标 = 带 × 12 + 列），由 slotCountSnapshot 解析。 */
    private int[] slotCounts = new int[0];

    /** 上一次应用过的隐藏状态，按语义记：没变就不调 AE2（它会触发一次全量重摆）。 */
    private final Map<SlotSemantic, Boolean> appliedHidden = new HashMap<>();

    /** 行表是否已到手。首帧可能先渲染、后收到同步包；那时不能动槽位（见 applySlotVisibility）。 */

    /** 行表就绪后是否已补过一次槽位可见性重算（只补一次，之后走每帧的增量路径）。 */

    public EnergyModuleTerminalScreen(EnergyModuleTerminalMenu menu, Inventory playerInventory, Component title,
            ScreenStyle style) {
        super(menu, playerInventory, title, style);

        this.tableHeader = style.getImage("tableHeader");
        this.rowTextTop = style.getImage("rowTextTop");
        this.rowTextMiddle = style.getImage("rowTextMiddle");
        this.rowTextBottom = style.getImage("rowTextBottom");
        this.rowSlotsTop = style.getImage("rowSlotsTop");
        this.rowSlotsMiddle = style.getImage("rowSlotsMiddle");
        this.rowSlotsBottom = style.getImage("rowSlotsBottom");
        this.tableFooter = style.getImage("tableFooter");
        this.componentSlot = style.getImage("componentSlot");
        this.storageBar = style.getImage("storageBar");
        this.inputBar = style.getImage("inputBar");
        this.storageBarOverlay = style.getImage("storageBarOverlay");
        this.inputBarOverlay = style.getImage("inputBarOverlay");
        this.tableScrollbar = widgets.addScrollBar("tableScrollbar", Scrollbar.BIG);
        this.textColor = style.getColor(PaletteColor.DEFAULT_TEXT_COLOR).toARGB();
        this.selectionColor = style.getColor(PaletteColor.SELECTION_COLOR).toARGB();

        // 几何从贴图切片反推，代码里不留第二份坐标。
        this.bandHeight = this.rowTextTop.getSrcHeight();
        this.tableTop = this.tableHeader.getSrcHeight();
        this.footerHeight = this.tableFooter.getSrcHeight();
        this.footerBaseY = this.tableFooter.getSrcY();

        var bounds = new Rect2i(0, 0, imageWidth, imageHeight);
        var storageAnchor = style.getWidget("storageValue").resolve(bounds);
        this.barValueX = storageAnchor.getX();
        this.storageValueBaseY = storageAnchor.getY();
        var inputAnchor = style.getWidget("inputValue").resolve(bounds);
        this.inputValueBaseY = inputAnchor.getY();

        // 表尾里那三段固定文字改由本类自己画：文档里的 text 段是绝对坐标，而表尾会跟着「终端样式」档位往下挪。
        this.playerInventoryTitle = footerText(style, bounds, "player_inventory_title");
        this.storageLabel = footerText(style, bounds, "energy_storage_label");
        this.inputLabel = footerText(style, bounds, "input_rate_label");

        addToolbarButtons();
        this.footerTop = this.tableTop + MIN_ROWS * this.bandHeight;
        this.footerDelta = this.footerTop - this.footerBaseY;
    }

    /**
     * 按 id 取一段表尾固定文字：拿它的坐标与文案，并把文档里那份隐藏（改由 {@link #drawFG} 重画）。
     *
     * <p>文档里没有这个 id 时给一段空文字、也不抛：布局改坏了就屏幕上少一行字，而不是开屏就把玩家踢下线。</p>
     */
    private FooterText footerText(ScreenStyle style, Rect2i bounds, String id) {
        var text = style.getText().get(id);
        if (text == null) {
            return new FooterText(0, 0, Component.empty());
        }
        setTextHidden(id, true);
        var pos = text.getPosition().resolve(bounds);
        return new FooterText(pos.getX(), pos.getY(), text.getText());
    }

    /** 表尾里的一段固定文字：文档坐标（未含表尾偏移）与内容。 */
    private record FooterText(int x, int y, Component content) {
        void draw(GuiGraphics guiGraphics, net.minecraft.client.gui.Font font, int delta, int color) {
            if (this.content.getString().isEmpty()) {
                return;
            }
            guiGraphics.drawString(font, this.content, this.x, this.y + delta, color, false);
        }
    }

    /**
     * 每次开屏 / 重排都算一遍：这一屏显示几条带、面板多高、表尾落在哪。
     *
     * <p>条数由「终端样式档位」与「窗口放得下几条」共同决定，算完要报给服务端（它得知道填几行内容）。</p>
     */
    @Override
    public void init() {
        // 放得下几条：扣掉终端边距、表头与表尾，剩下的除以带高。
        int margin = AEConfig.instance().getTerminalMargin();
        int available = this.height - 2 * margin;
        int possible = Math.max(1, (available - this.tableTop - this.footerHeight) / this.bandHeight);
        this.bands = Mth.clamp(targetBands(AEConfig.instance().getTerminalStyle(), possible), MIN_ROWS,
                Math.min(MAX_ROWS, Math.max(possible, MIN_ROWS)));

        this.footerTop = this.tableTop + this.bands * this.bandHeight;
        this.footerDelta = this.footerTop - this.footerBaseY;
        this.imageHeight = this.footerTop + this.footerHeight;

        super.init();

        // 槽位与控件都在 super.init() 里按样式文档摆好了：这里只做「隐藏用不到的」（表尾位移也在
        // applySlotVisibility 内部上一次，因为 setSlotsHidden 会把槽位重摆回文档坐标）。
        // 重排后 AE2 已经把槽位重摆过一遍，隐藏状态得重算一遍。
        this.appliedHidden.clear();
        applySlotVisibility();
    }

    /**
     * 档位 → 目标条数。
     *
     * <p>与 AE2 自己的 {@code TerminalStyle#getRows}（分数制）不同：我们的带是固定 18px 的模块行，档位直接给
     * 条数比「放得下几条的几分之几」更好懂，也更符合「SMALL / MEDIUM / TALL 就是 4 / 6 / 8 行」的直觉。
     * FULL 才是「填满窗口」。</p>
     */
    private static int targetBands(appeng.api.config.TerminalStyle style, int possible) {
        return switch (style) {
            case SMALL -> 4;
            case MEDIUM -> 6;
            case TALL -> 8;
            case FULL -> possible;
        };
    }

    /** 表尾里的控件（玩家槽位与两条进度条的数值锚点）跟着表尾挪。 */
    private void shiftFooterAnchors() {
        for (var semantic : List.of(SlotSemantics.PLAYER_INVENTORY, SlotSemantics.PLAYER_HOTBAR)) {
            for (Slot slot : getMenu().getSlots(semantic)) {
                int base = this.playerSlotBaseY.computeIfAbsent(slot, s -> s.y);
                slot.y = base + this.footerDelta;
            }
        }
    }

    /**
     * 按行表把用不到的槽藏起来，用得到的放出来。
     *
     * <p>规则只有两条：超出可见条数的带全部藏；标题行藏「组件槽」、放「标题图标槽」，组件行反过来。每帧算一次
     * 期望状态，但只有真变了才调 AE2 的 {@code setSlotsHidden}（它会触发一次全量重摆）。</p>
     *
     * <p><b>快照没到手时行表是空的</b>，于是所有槽都被藏起来；{@code updateBeforeRender} 会在行数变化时向服务端
     * 要一次重同步（{@code setRowOffset}），快照到手后立刻恢复——这正是「开屏是空的，拖一下滚动条才出现」那条
     * 老毛病的修法。</p>
     */
    private void applySlotVisibility() {
        // 行表每帧只解一次（绘制/标题/覆盖层/选组都读它）；这里先保证它已解——本方法在 init() 里也会被调到，
        // 而那时这一帧的 updateBeforeRender 还没跑过。
        refreshLocalRows();
        for (int band = 0; band < MAX_ROWS; band++) {
            boolean visible = band < this.bands && !EMPTY_MARK.equals(bandMark(band));
            boolean title = visible && bandIsTitle(band);
            applyHidden(EnergyModuleSlotSemantics.componentSemantic(band), !visible || title);
            applyHidden(EnergyModuleSlotSemantics.TITLE_ICON[band], !title);
        }

        // setSlotsHidden(sem, false) 内部会调 positionSlots()，把所有已声明的语义拉回文档坐标——其中就包括
        // 玩家背包与快捷栏。所以表尾位移要在这里再上一次（幂等：基准坐标只记一次，每次都是「基准 + 偏移」）。
        shiftFooterAnchors();
    }

    /** 只在该语义的隐藏状态真变了时才调 AE2。 */
    private void applyHidden(SlotSemantic semantic, boolean hidden) {
        Boolean applied = this.appliedHidden.get(semantic);
        if (applied != null && applied == hidden) {
            return;
        }
        this.appliedHidden.put(semantic, hidden);
        setSlotsHidden(semantic, hidden);
    }

    /**
     * 面板 = 表头 + N 条带 + 表尾 + 两条进度条。
     *
     * <p>不走 super：底图不是一整块能直接贴的图——面板高度会随样式档位变，只有「表头 / 可重复的带 / 表尾」三段
     * 拼起来才拼得出正确高度（贴图里的三条带本来就是可重复的中段）。</p>
     */
    @Override
    public void drawBG(GuiGraphics guiGraphics, int offsetX, int offsetY, int mouseX, int mouseY,
            float partialTicks) {
        this.tableHeader.copy().dest(offsetX, offsetY).blit(guiGraphics);

        // 逐条贴带：每条带是什么由行表决定，带图按「行角色 × 位置」两面选。
        // 位置指的是这条带在窗口里的位置（首条取「上」、末条取「下」、其余取「中」），与它上下面是标题还是模块行无关。
        for (int band = 0; band < this.bands; band++) {
            // drawBG 跑在「面板原点平移之前」（AE2 在 renderBg 阶段直接调它），所以本函数里的坐标一律要自己加 offsetX/offsetY。
            // —— 别和 drawFG 弄混：那边 pose 已平移到 leftPos/topPos，再加一次反而会偏。
            int rowY = offsetY + this.tableTop + band * this.bandHeight;

            if (bandIsTitle(band)) {
                titleBand(band).copy().dest(offsetX, rowY).blit(guiGraphics);
            } else {
                slotsBand(band).copy().dest(offsetX, rowY).blit(guiGraphics);
            }
        }

        this.tableFooter.copy().dest(offsetX, offsetY + this.footerTop).blit(guiGraphics);

        drawBar(guiGraphics, offsetX, offsetY, this.storageBar, this.storageBarOverlay,
                getMenu().currentEnergy, getMenu().totalCapacity);
        drawBar(guiGraphics, offsetX, offsetY, this.inputBar, this.inputBarOverlay,
                getMenu().currentInputRate, getMenu().totalInputRate);

        drawComponentSlotOverlays(guiGraphics, offsetX, offsetY);
    }

    /**
     * 一条进度条：先贴轨道，再把覆盖层按比例裁左端、贴在同一位置。
     *
     * <p>比例算不出来时（上限非正）当空条处理：宁可看到空条，也不能让这里抛异常——那一帧之后玩家看到的
     * 是整个屏幕塔掉。</p>
     *
     * <p><b>覆盖层的坐标不是落点</b>：样式文档里给的 （0,221）一类只是「素材在这里」，它的尺寸与位置靠轨道
     * 决定。所以源取覆盖层自己的切片，落点直接复用轨道那一份。</p>
     */
    private void drawBar(GuiGraphics guiGraphics, int offsetX, int offsetY, Blitter track,
            Blitter overlay, long current, long max) {
        int x = offsetX + track.getSrcX();
        int y = offsetY + barY(track.getSrcY());
        track.copy().dest(x, y).blit(guiGraphics);

        if (max <= 0L) {
            return;
        }
        double ratio = Mth.clamp((double) current / (double) max, 0.0D, 1.0D);
        // 比例按轨道的宽算，但采样不能越过覆盖层自己那份切片：两张图的宽将来若不等，先例是轨道的。
        int fillWidth = Math.min((int) Math.round(track.getSrcWidth() * ratio), overlay.getSrcWidth());
        if (fillWidth <= 0) {
            return;
        }
        overlay.copy()
                .src(overlay.getSrcX(), overlay.getSrcY(), fillWidth, overlay.getSrcHeight())
                .dest(x, y)
                .blit(guiGraphics);
    }

    /** 贴图里的绝对 y → 面板上的 y：素材在表尾那一段里，表尾挪多少它挪多少。 */
    private int barY(int srcY) {
        return srcY + this.footerDelta;
    }

    /**
     * 带图按「行角色 × 位置」选：角色（标题/模块）决定用平面带还是槽带，位置（首/中/末）决定用哪一条边线变体。
     *
     * <p>两个维度互不隶属：一条带既可以是「标题 + 首行」，也可以是「模块 + 末行」。</p>
     */
    private Blitter titleBand(int band) {
        return edgeVariant(band, this.rowTextTop, this.rowTextMiddle, this.rowTextBottom);
    }

    /** 模块行的带，规则同 {@link #titleBand}。 */
    private Blitter slotsBand(int band) {
        return edgeVariant(band, this.rowSlotsTop, this.rowSlotsMiddle, this.rowSlotsBottom);
    }

    private Blitter edgeVariant(int band, Blitter top, Blitter middle, Blitter bottom) {
        if (band <= 0) {
            return top;
        }
        return band >= this.bands - 1 ? bottom : middle;
    }

    /**
     * 组件行那 12 格的「可放」覆盖层。
     *
     * <p>聚合视图下每一格都可用（一格一种组件，满 12 种就往下一行延伸），所以不再有「不可用槽」那一层——
     * 以前是「模块只给得出 1~2 格、多出来的盖暗格」，而格子的数量现在由种类数决定，不由某一块决定。</p>
     */
    private void drawComponentSlotOverlays(GuiGraphics guiGraphics, int offsetX, int offsetY) {
        for (int band = 0; band < this.bands; band++) {
            // 标题行（与行表到头的那条空带）上没有组件槽：一条覆盖层也不铺，否则整条带上会浮出 12 个暗格。
            if (bandIsTitle(band) || bandMark(band).equals(EMPTY_MARK)) {
                continue;
            }
            for (var slot : getMenu().getSlots(EnergyModuleSlotSemantics.componentSemantic(band))) {
                this.componentSlot.copy().dest(offsetX + slot.x, offsetY + slot.y).blit(guiGraphics);
            }
        }
    }

    /** 前景：标题行写「等级名 + 模块数量」，右下两条进度条的数值。 */
    @Override
    public void drawFG(GuiGraphics guiGraphics, int offsetX, int offsetY, int mouseX, int mouseY) {
        for (int band = 0; band < this.bands; band++) {
            var title = bandTiers[band];
            if (title == null) {
                continue;
            }
            // 本函数（drawFG）跑在 pose 已平移到 (leftPos, topPos) 之后，坐标一律是「面板相对」；
            // 再叠一次 offsetX/offsetY 等于把面板原点算两遍（图标是槽物品、按 slot.x 画，文字就会跑到右缘外）。
            int rowY = this.tableTop + band * this.bandHeight;
            // 文字位置优先跟「等级图标槽」走（需求：以虚拟物品为准）；槽位查不到时退回带几何，
            // 否则整行文字会直接消失——图标是服务端填的槽、文字是客户端算的，两者不能共用一个失败点。
            var iconSlot = titleIconSlot(band);
            int textX = iconSlot != null ? iconSlot.x + TITLE_TEXT_INSET_X : TITLE_TEXT_ICON_ONLY_X;
            int textY = (iconSlot != null ? iconSlot.y : rowY) + TITLE_TEXT_INSET_Y;
            boolean selected = title.id().equals(getMenu().selectedTier);
            if (selected) {
                // 选中的组整条带压一层选择色：快速移动只会往这一组里落，得让人一眼看出选的是哪一组。
                // 右边界用「一条带自己的右缘」（最后一个组件格 + 组件列宽），不能用 imageWidth：
                // 面板宽 249，而带的有效区到 237，再往右是滚动条轨道（229 起）。
                int highlight = (SELECTION_ALPHA << 24) | (this.selectionColor & 0xFFFFFF);
                guiGraphics.fill(2, rowY + 1, BAND_RIGHT_EDGE, rowY + this.bandHeight - 1,
                        highlight);
            }
            // 文字位置相对「等级图标槽」算，不跟带几何挂钩：图标由 AE2 自己画（那是槽里的物品）。
            // 三段拼成一行：等级名、该档模块数、已装配存储组件——最宽的一档会顶穿面板，所以交给 drawTitleLine 缩。
            int color = selected ? this.selectionColor : this.textColor;
            var line = Component.empty()
                    .append(tierName(title))
                    .append(TITLE_TEXT_GAP_TEXT)
                    .append(Component.translatable("gui.ae2modularcells.tier_group_count", this.bandCounts[band]))
                    .append(TITLE_TEXT_GAP_TEXT)
                    .append(Component.translatable("gui.ae2modularcells.tier_assembly_count",
                            this.bandAssembled[band], this.bandCapacity[band]));
            drawTitleLine(guiGraphics, line, textX, textY, color);
        }

        drawComponentCounts(guiGraphics);
        drawBarValues(guiGraphics);
    }

    /**
     * 组件格里的件数。
     *
     * <p><b>这些数字全部由本屏幕画</b>：原版那份已经被 {@code AppEngSlot#setHideAmount} 关掉了（见菜单的
     * {@code componentSlot}）。原版只画得到一叠以内的数——超过就不再增长，而这里的一格可以是成千件（16³ 的
     * 一格能装几十件的同种组件），它根本表达不了。</p>
     *
     * <p>曾经的分工是「≤64 归原版、>64 归我们」，而两处用的坐标完全相同（都被右下角对齐陷阱锁在
     * {@code x + 17 - 宽度, y + 9}），于是超过 64 的格子显示成两个数字叠在一起——看上去就像数字被一叠的 64
     * 盖住了。现在只有一个出处，也就不存在谁盖谁。</p>
     *
     * <p>画法：右下角对齐、白字带黑描边（跟原版装饰渲染同一个位置与观感），但<b>大小自己管</b>——数字先过
     * 左侧那枚「显示方式」按钮的档位（字母 / 科学 / 工程），放不下格子就按比例缩到格子宽度以内，不再像原版
     * 那样固定尺寸地溢出到隔壁格子。</p>
     */
    private void drawComponentCounts(GuiGraphics guiGraphics) {
        // 档位一帧只取一次：格数最多百来个，每次都问一遍枚举没必要。
        var mode = NumberDisplayMode.current();
        for (int band = 0; band < this.bands; band++) {
            if (bandIsTitle(band) || bandMark(band).equals(EMPTY_MARK)) {
                continue;
            }
            for (var slot : getMenu().getSlots(EnergyModuleSlotSemantics.componentSemantic(band))) {
                int count = slot.index >= 0 && slot.index < this.slotCounts.length ? this.slotCounts[slot.index] : 0;
                if (count <= 0) {
                    // 空格子：原版会画一个“没有物品”的淡图标，那里不该再叠一个 0。
                    continue;
                }
                drawSlotCount(guiGraphics, mode.format(count), slot.x, slot.y);
            }
        }
    }

    /**
     * 一格里的件数：右下角对齐，超出可用宽就整体缩到格子宽度以内。
     *
     * <p>缩放后要按缩过的尺寸反推落点，否则缩小的数字会从原地往右下飘、反而压到格线外——偏移量都得乘
     * {@code scale}。</p>
     */
    private void drawSlotCount(GuiGraphics guiGraphics, String text, int slotX, int slotY) {
        int available = SLOT_SIZE - SLOT_COUNT_INSET * 2;
        int width = font.width(text);
        float scale = width <= available ? 1.0F : Math.max(SLOT_COUNT_MIN_SCALE, (float) available / width);
        int drawX = slotX + SLOT_SIZE - SLOT_COUNT_INSET - Math.round(width * scale);
        int drawY = slotY + SLOT_SIZE - SLOT_COUNT_INSET - Math.round(font.lineHeight * scale);
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(drawX, drawY, 0F);
        guiGraphics.pose().scale(scale, scale, 1F);
        guiGraphics.drawString(font, text, 0, 0, 0xFFFFFF, true);
        guiGraphics.pose().popPose();
    }

    /**
     * 标题行那一行文字（等级名 / 模块数量 / 已装配存储组件）。
     *
     * <p>三段拼成一行画：超密档的名字最长，再叠上后两段就可能顶穿面板，所以放不下时整行按比例缩小——与右下
     * 两条数值同一套办法，宁可小一点也不要溢出或被裁掉。</p>
     */
    private void drawTitleLine(GuiGraphics guiGraphics, Component line, int x, int y, int color) {
        String text = line.getString();
        int available = Math.max(1, BAND_RIGHT_EDGE - x);
        int width = font.width(text);
        float scale = width <= available ? 1.0F : Math.max(TITLE_MIN_SCALE, (float) available / width);
        if (scale >= 1.0F) {
            guiGraphics.drawString(font, line, x, y, color, false);
            return;
        }
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(x, y, 0F);
        guiGraphics.pose().scale(scale, scale, 1F);
        guiGraphics.drawString(font, line, 0, 0, color, false);
        guiGraphics.pose().popPose();
    }

    /** 第 {@code band} 条带的等级图标槽（标题行才有）；拿不到给 {@code null}。 */
    @Nullable
    private Slot titleIconSlot(int band) {
        var slots = getMenu().getSlots(EnergyModuleSlotSemantics.TITLE_ICON[band]);
        return slots.isEmpty() ? null : slots.get(0);
    }

    /**
     * 右击标题行的等级图标 = 选中 / 取消选中该等级组。
     *
     * <p>只发意图，选中状态由服务端持有并回传（它要拿去筛快速移动的目标）。图标槽本身是只读的，所以这里不需要
     * 考虑「点到物品」的语义；命中判定用槽位自己的 16×16 区域。</p>
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 1) {
            String tierId = titleTierAt(mouseX, mouseY);
            if (tierId != null) {
                getMenu().selectGroup(tierId);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 鼠标点到哪个标题行的图标槽上；没点到给 {@code null}。 */
    @Nullable
    private String titleTierAt(double mouseX, double mouseY) {
        for (int band = 0; band < this.bands; band++) {
            if (!bandIsTitle(band)) {
                continue;
            }
            var title = bandTiers[band];
            if (title == null) {
                continue;
            }
            var slots = getMenu().getSlots(EnergyModuleSlotSemantics.TITLE_ICON[band]);
            if (slots.isEmpty()) {
                continue;
            }
            var slot = slots.get(0);
            if (isHovering(slot.x, slot.y, SLOT_SIZE, SLOT_SIZE, mouseX, mouseY)) {
                return title.id();
            }
        }
        return null;
    }

    /** 这条带是不是标题行。 */
    private boolean bandIsTitle(int band) {
        return band >= 0 && band < MAX_ROWS && bandTitleFlags[band];
    }

    /** 第 {@code band} 条带的标记，本帧已在 {@link #updateBeforeRender} 解好；越界或还没解给空带。 */
    private String bandMark(int band) {
        if (band < 0 || band >= this.bandMarks.length) {
            return EMPTY_MARK;
        }
        String mark = this.bandMarks[band];
        return mark == null ? EMPTY_MARK : mark;
    }

    /**
     * 解析同步过来的 {@code rowLayout} 到本帧的数组。
     *
     * <p>解析失败就当成空带（{@code -}）：同步字符串要是被截断或版本错位，屏幕上少一行远比抛异常把玩家踢下线好。
     * 两侧本就同源，真不同步时会在界面上看出来，而不是崩掉。</p>
     */
    /**
     * 用三份快照在本地排出整张行表（照 AE2 样板访问终端：行表是客户端的事）。
     *
     * <p>快照是三份：{@code structureSnapshot}（按遍历序的等级短 id 序列）、{@code kindSnapshot}（每档组件种类数）、
     * {@code assemblySnapshot}（每档件数）。解析后交给纯函数 {@link TerminalRows#build}——分组、标题行、该档几行
     * 全在本地算，不再解析服务端拼好的行表字符串。</p>
     *
     * <p>快照还没到的那一帧行表是空的（没有带可显示），{@code updateBeforeRender} 会替它要一次重同步。</p>
     */
    private void refreshLocalRows() {
        String structure = getMenu().structureSnapshot;
        if (structure == null) {
            structure = "";
        }
        String kindsSnapshot = getMenu().kindSnapshot;
        if (kindsSnapshot == null) {
            kindsSnapshot = "";
        }
        String assembledSnapshot = getMenu().assemblySnapshot;
        if (assembledSnapshot == null) {
            assembledSnapshot = "";
        }
        String slotCountSnapshot = getMenu().slotCountSnapshot;
        if (slotCountSnapshot == null) {
            slotCountSnapshot = "";
        }
        // 快照任意一份变了就要重排：种类数变了行数就变，件数变了标题与格内数字就变。
        String marker = structure + '|' + kindsSnapshot + '|' + assembledSnapshot + '|' + slotCountSnapshot;
        if (!marker.equals(this.lastSnapshot)) {
            this.lastSnapshot = marker;
            this.localRows.clear();
            var tiers = ModularCellTiers.all();
            // 与 buildStructureSnapshot 对应的一字符制：a + 固定位号，? 表示这一块拿不到等级。
            int[] moduleCounts = new int[tiers.size()];
            for (int index = 0; index < structure.length(); index++) {
                int code = structure.charAt(index) - 97;
                if (code >= 0 && code < moduleCounts.length) {
                    moduleCounts[code]++;
                }
            }
            this.tierModuleCounts = moduleCounts;
            this.tierAssembled = parseCounts(assembledSnapshot, tiers.size());
            int[] kinds = parseCounts(kindsSnapshot, tiers.size());
            TerminalRows.build(index -> moduleCounts[index], index -> kinds[index], this.localRows);
            this.slotCounts = parseCounts(slotCountSnapshot, EnergyModuleSlotSemantics.MAX_SLOT_COUNT);
            this.appliedHidden.clear();
        }
        // 本地行表 → 这一屏的带标记与三段数值。下游（绘制、槽位可见性、标题文字、覆盖层）读的就是这几组数组，
        // 口径与服务端行表一致。
        var tiers = ModularCellTiers.all();
        for (int band = 0; band < MAX_ROWS; band++) {
            int rowIndex = getMenu().rowOffset + band;
            var row = rowIndex >= 0 && rowIndex < this.localRows.size() ? this.localRows.get(rowIndex) : null;
            boolean title = row != null && row.title();
            this.bandTitleFlags[band] = title;
            this.bandTiers[band] = row == null ? null : row.tier();
            int slotIndex = row == null ? -1 : ModularCellTiers.indexOf(row.tier());
            int modules = slotIndex >= 0 && slotIndex < this.tierModuleCounts.length
                    ? this.tierModuleCounts[slotIndex]
                    : 0;
            this.bandCounts[band] = modules;
            this.bandAssembled[band] = slotIndex >= 0 && slotIndex < this.tierAssembled.length
                    ? this.tierAssembled[slotIndex]
                    : 0;
            // 名额 = 该档模块数 × 该档每块槽位数。
            this.bandCapacity[band] = slotIndex >= 0 && slotIndex < tiers.size()
                    ? modules * tiers.get(slotIndex).componentSlots()
                    : 0;
            if (row == null) {
                this.bandMarks[band] = EMPTY_MARK;
            } else if (title) {
                this.bandMarks[band] = BAND_TITLE_PREFIX + row.tier().id();
            } else {
                this.bandMarks[band] = BAND_SLOTS_PREFIX + row.tier().id();
            }
        }
    }

    /**
     * 解析「逗号分隔的非负整数」快照。
     *
     * <p>长度不足或某段不是数字就当 0：同步字符串被截断或版本错位时，屏幕上少显示一个数字，远比抛异常把玩家
     * 踢下线好。两侧本就同源，真不同步时在界面上看得出来，而不至于崩掉。</p>
     */
    private static int[] parseCounts(String snapshot, int size) {
        int[] result = new int[size];
        if (snapshot.isEmpty()) {
            return result;
        }
        var parts = snapshot.split(",");
        for (int index = 0; index < Math.min(parts.length, size); index++) {
            try {
                result[index] = Math.max(0, Integer.parseInt(parts[index].trim()));
            } catch (NumberFormatException ignored) {
                result[index] = 0;
            }
        }
        return result;
    }

    /**
     * 两条进度条的固定标签与数值：{@code 当前 / 上限}。
     *
     * <p>标签是固定文字、数值每帧刷新，两者都在表尾里，都跟着表尾挪。数值从 {@code barValueX} 起画到面板右缘，
     * 字符太长就整体缩小（下限 {@link #VALUE_MIN_SCALE}）——数字格式切到「科学/工程」或档位大时很容易顶穿面板。</p>
     */
    private void drawBarValues(GuiGraphics guiGraphics) {
        var menu = getMenu();
        int delta = this.footerDelta;
        this.playerInventoryTitle.draw(guiGraphics, font, delta, this.textColor);
        this.storageLabel.draw(guiGraphics, font, delta, this.textColor);
        this.inputLabel.draw(guiGraphics, font, delta, this.textColor);

        drawValue(guiGraphics,
                barValue("gui.ae2modularcells.storage.value", menu.currentEnergy, menu.totalCapacity),
                this.barValueX, this.storageValueBaseY + delta);
        drawValue(guiGraphics,
                barValue("gui.ae2modularcells.input.value", menu.currentInputRate, menu.totalInputRate),
                this.barValueX, this.inputValueBaseY + delta);
    }

    /** 一行数值：放得下就原尺寸，放不下就整体缩小到贴着面板右缘（不换行、不裁切）。 */
    private void drawValue(GuiGraphics guiGraphics, Component text, int x, int y) {
        String line = text.getString();
        int available = Math.max(1, imageWidth - BAR_VALUE_RIGHT_MARGIN - x);
        int width = font.width(line);
        float scale = width <= available ? 1.0F : Math.max(VALUE_MIN_SCALE, (float) available / width);
        if (scale >= 1.0F) {
            guiGraphics.drawString(font, text, x, y, this.textColor, false);
            return;
        }
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(x, y, 0F);
        guiGraphics.pose().scale(scale, scale, 1F);
        guiGraphics.drawString(font, text, 0, 0, this.textColor, false);
        guiGraphics.pose().popPose();
    }

    /**
     * 数值 + 单位。单位写在语言文件的模板里（储能是 {@code AE}、输入速率是 {@code AE/t}），不在代码里拼字符串
     * ——不同语言里单位的位置与写法可能不同。
     *
     * <p>数字格式跟随左侧那枚「显示方式」按钮（字母 / 科学 / 工程）。</p>
     */
    private static Component barValue(String key, long current, long max) {
        var mode = NumberDisplayMode.current();
        return Component.translatable(key, mode.format(Math.max(0L, current)), mode.format(Math.max(0L, max)));
    }

    /**
     * 左侧三枚按钮：指南（基类在构造期加的）→ 终端样式 → 显示方式。
     *
     * <p><b>加在构造器里而不是 {@code init()} 里</b>：VerticalButtonBar 的按钮列表不会被清空，而窗口缩放 / 改 GUI
     * 缩放会重新走一次 {@code init()}——写在 {@code init()} 里就会每重排一次多出两枚按钮、一路往下堆。AE2 自己的
     * 屏幕也全部写在构造期。</p>
     */
    private void addToolbarButtons() {
        this.addToLeftToolbar(new SettingToggleButton<>(Settings.TERMINAL_STYLE,
                AEConfig.instance().getTerminalStyle(), this::toggleTerminalStyle));
        this.addToLeftToolbar(new DisplayModeButton(NumberDisplayMode::cycle));
    }

    /**
     * 终端样式：写进 AE2 的客户端配置（别的终端也跟着变，与 AE2 自己的行为一致），然后重排本屏。
     *
     * <p>样式决定显示几条带，所以换档必须重排——不重排的话面板高度与条数会停在旧档，按钮就成了摆设。
     * 重排走 AE2 自己的路子（清掉控件再 {@code init()}），与 MEStorageScreen 的做法一致。</p>
     */
    private void toggleTerminalStyle(SettingToggleButton<appeng.api.config.TerminalStyle> button,
            boolean backwards) {
        var next = button.getNextValue(backwards);
        AEConfig.instance().setTerminalStyle(next);
        button.set(next);
        reinitalize();
    }

    /** 清掉控件再走一次 {@code init()}：条数、面板高度与槽位可见性都在那里重算。 */
    private void reinitalize() {
        new java.util.ArrayList<>(this.children()).forEach(this::removeWidget);
        this.appliedHidden.clear();
        init();
    }

    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender();

        // 行表在 applySlotVisibility() 里每次进都先解一遍；绘制/标题/覆盖层/选组四处共用它解出来的结果。
        applySlotVisibility();

        // 本地行表（重新）排好时向服务端要一次重同步：开屏那一瞬间服务端刚填完窗口，而客户端的槽位
        // 可能还没摆好——不主动要一次，就会是「打开是空的，拖一下滚动条才出现」（拖滚动条之所以能修好，
        // 正是因为它走了这条同步）。判据从「服务端行表字符串」改成「本地行表行数」，不再依赖 rowLayout。
        int localRowCount = this.localRows.size();
        if (localRowCount != this.syncedRows) {
            this.syncedRows = localRowCount;
            getMenu().setRowOffset(getMenu().rowOffset);
        }

        // 条数报给服务端：它得知道填几行内容、也给滚动范围留出正确的窗口大小。
        if (getMenu().visibleRows != this.bands) {
            getMenu().setVisibleRows(this.bands);
        }

        // 滚动条只负责产生整数行号，绘制与命中都由屏幕自己按行号算；这里每帧把范围与当前位置喂给它，
        // 再把它的值当成意图推给服务端（服务端夹取后回传，两侧才一致）。
        int visible = Math.max(1, getMenu().visibleRows);
        int maxScroll = Math.max(0, getMenu().totalRows - visible);
        this.tableScrollbar.setHeight(Math.max(1, this.bands * this.bandHeight - 2));
        this.tableScrollbar.setRange(0, maxScroll, visible);
        if (this.tableScrollbar.getCurrentScroll() > maxScroll) {
            this.tableScrollbar.setCurrentScroll(maxScroll);
        }
        int wanted = this.tableScrollbar.getCurrentScroll();
        if (wanted != getMenu().rowOffset) {
            getMenu().setRowOffset(wanted);
        }
    }

    /**
     * 悬停在组件格上时补一行精确件数。
     *
     * <p>格子里画的是缩略写法（{@code 12.2k}），而「这一格到底装了多少件」是玩家算装配量的依据，不该只是
     * 一个近似。</p>
     *
     * <p>走这个钩子、而不走 AE2 的 {@code AppEngSlot#getCustomTooltip}：后者是<b>替换</b>语义，而
     * {@code AEBaseScreen#renderTooltips} 画完自定义那份后并不 return，紧接着还会画一遍悬停槽的标准 tooltip
     * ——两处一起出现，新加的那行还会被后画的框盖住。上游自己的附加行（如 {@code CellWorkbenchScreen}）也都
     * 走这里。</p>
     */
    @Override
    protected List<Component> getTooltipFromContainerItem(ItemStack stack) {
        var lines = new ArrayList<>(super.getTooltipFromContainerItem(stack));
        var slot = this.hoveredSlot;
        if (slot != null && isComponentSlot(slot)) {
            int count = slot.index >= 0 && slot.index < this.slotCounts.length ? this.slotCounts[slot.index] : 0;
            if (count > 0) {
                lines.add(Component.translatable("gui.ae2modularcells.slot_installed", count));
            }
        }
        return lines;
    }

    /** 这个槽是不是某条带上的组件格（标题行的图标格也是 {@code AppEngSlot}，不能只按类型认）。 */
    private boolean isComponentSlot(Slot slot) {
        var semantic = getMenu().getSlotSemantic(slot);
        if (semantic == null) {
            return false;
        }
        for (int band = 0; band < MAX_ROWS; band++) {
            if (semantic == EnergyModuleSlotSemantics.componentSemantic(band)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 等级短 id 换成可显示的名字。
     *
     * <p><b>向该档的方块要名字，而不是自己拼语言键</b>：方块名本来就是本地化过的，而且与物品栏里看到的是
     * 同一个。另一头为什么不行——语言键得按 {@code block.<命名空间>.<短 id>_energy_cell_module} 拼，而这套
     * 命名规则是本模组内置三档的约定：换了实现（例如 KubeJS 脚本注册的等级与方块）就会拼出一个不存在的键，
     * 界面上直接显示成一串裸键。</p>
     *
     * <p>查不到方块（那一档没有注册方块套装）或等级为空时给空文字：界面不因为一个陌生 id 崩掉，也不显示
     * 半句话。</p>
     */
    private static Component tierName(@Nullable EnergyCellTier tier) {
        if (tier == null) {
            return Component.empty();
        }
        var block = AEModularCellsRegistries.moduleBlock(tier);
        return block == null ? Component.empty() : block.getName();
    }
}
