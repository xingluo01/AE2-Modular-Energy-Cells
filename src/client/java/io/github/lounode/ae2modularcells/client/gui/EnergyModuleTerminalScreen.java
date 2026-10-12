package io.github.lounode.ae2modularcells.client.gui;

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

import io.github.lounode.ae2modularcells.common.EnergyCellTier;
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
 * <p><b>行分割与拼接</b>：每条带一张图，逐条贴。每条带是什么由服务端同步过来的行表（{@code menu.rowLayout}）
 * 决定：{@code T:<等级>:<模块数>} 是等级标题行（占整行的平面带 + 一个等级图标槽 + 「模块数量」文字）、
 * {@code M} 是模块行（带 12 格的槽带）、{@code -} 是行表到头（或被样式档位裁掉）。带图的选取是「行角色 × 位置」
 * 两个维度：角色决定用平面带还是槽带，位置（首/中/末）决定用哪一条边线变体——两者互不隶属，所以是 2×3 的可用
 * 组合，而不是固定搭配。</p>
 *
 * <p>每条带上有三套槽（模块槽 + 组件槽 / 标题图标槽），同一时刻只有一套在用，另一套由
 * {@link #applySlotVisibility()} 隐藏——把用不到的那一套留在界面上，玩家会看到空模块格或空标题格，也可能点到
 * 它们。</p>
 */
public class EnergyModuleTerminalScreen extends AEBaseScreen<EnergyModuleTerminalMenu> {

    private static final int MODULES_PER_ROW = EnergyModuleSlotSemantics.MODULES_PER_ROW;
    private static final int MIN_ROWS = EnergyModuleSlotSemantics.MIN_ROWS;
    private static final int MAX_ROWS = EnergyModuleSlotSemantics.MAX_ROWS;

    /** 行表到头了（这条带空着）的标记，与菜单侧的拼装约好。 */
    private static final char BAND_EMPTY = '-';
    /** 空标记的字符串形式：每帧要用好几次，定成常量免得反复 String.valueOf。 */
    private static final String EMPTY_MARK = String.valueOf(BAND_EMPTY);
    /** 标题行的前缀：{@code T:<等级短 id>:<该组模块数>}。 */
    private static final String BAND_TITLE_PREFIX = "T:";
    /** 标题标记里各段的间隔符。 */
    private static final String MARK_SEPARATOR = ":";
    /** 模块行。 */
    private static final char BAND_MODULES = 'M';

    /** 标题文字距图标槽左缘的缩进：图标 16px + 2px 间隔，文字就落在组件列的位置上。 */
    private static final int TITLE_TEXT_INSET_X = 18;
    /** 图标槽查不到时的退路：从带左缘算，8（图标槽在文档里的 left）+ 18。 */
    private static final int TITLE_TEXT_ICON_ONLY_X = 26;
    /** 一条带的高亮右边界：最后一个组件格（left 170 + 16）再留 2px，不碰滚动条轨道（229 起）。
     * 188 = 组件列 top/left 的文档坐标（170）+ 组件格宽（16）+ 2px 余量；滚动条轨道从 229 起。 */
    private static final int TITLE_ROW_RIGHT_EDGE = 188;
    /** 标题文字距图标槽顶的下移：槽高 18、字高 9，落下 5 上下就均了。 */
    private static final int TITLE_TEXT_INSET_Y = 5;
    /** 等级名与后面的「模块数量」之间的间隔。 */
    private static final int TITLE_TEXT_GAP = 6;

    /** 数值右缘留白：留一点，别贴着面板边框。 */
    private static final int BAR_VALUE_RIGHT_MARGIN = 12;

    /** 数值文字的最大缩放：右下两条数值用它的宽反推缩放，小于这个值就不再缩（再小就看不清了）。 */
    private static final float VALUE_MIN_SCALE = 0.4F;

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
    private final Blitter unavailableSlot;
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

    /** 快照未到时的过渡态：这一帧按「全是模块带」铺满，不隐藏任何带（见 refreshLocalRows）。 */
    private boolean snapshotPending;

    /** 上一次同步给服务端的本地行数；变了才要一次内容重同步（见 updateBeforeRender）。 */
    private int syncedRows = -1;

    private final String[] bandMarks = new String[MAX_ROWS];

    /** 已经为哪一份行表要过一次服务端重同步；行表变了就要一次（开屏那次是必须的，见 updateBeforeRender）。 */
    private String resyncedLayout = "";
    private final EnergyCellTier[] bandTiers = new EnergyCellTier[MAX_ROWS];
    private final int[] bandCounts = new int[MAX_ROWS];

    /** 上一次应用过的隐藏状态，按语义记：没变就不调 AE2（它会触发一次全量重摆）。 */
    private final Map<SlotSemantic, Boolean> appliedHidden = new HashMap<>();

    /** 行表是否已到手。首帧可能先渲染、后收到同步包；那时不能动槽位（见 applySlotVisibility）。 */
    private boolean layoutReadyApplied;

    /** 行表就绪后是否已补过一次槽位可见性重算（只补一次，之后走每帧的增量路径）。 */
    private boolean visibilityReapplied;
    private boolean layoutReady;

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
        this.unavailableSlot = style.getImage("unavailableSlot");
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
     * <p>规则只有三条：超出可见条数的带全部藏；标题行藏「模块槽 + 组件槽」、放「标题图标槽」；模块行反过来。
     * 每帧算一次期望状态，但只有真变了才调 AE2 的 {@code setSlotsHidden}（它会触发一次全量重摆）。</p>
     *
     * <p><b>行表没到手时什么都不动</b>：首帧可能先渲染、后收同步包，那时若当成「一条带都没有」把整面板的槽
     * 搬到屏外，就只有在条数或行表再次变化时才会恢复——表现出来正是「开屏是空的，拖一下滚动条才出现」。</p>
     */
    private void applySlotVisibility() {
        // 行表每帧只解一次（绘制/标题/覆盖层/选组都读它）；这里先保证它已解——本方法在 init() 里也会被调到，
        // 而那时这一帧的 updateBeforeRender 还没跑过。
        refreshLocalRows();
        if (!this.layoutReady) {
            shiftFooterAnchors();
            return;
        }

        for (int band = 0; band < MAX_ROWS; band++) {
            boolean visible = band < this.bands && !EMPTY_MARK.equals(bandMark(band));
            boolean title = visible && bandIsTitle(band);
            for (int column = 0; column < MODULES_PER_ROW; column++) {
                int index = band * MODULES_PER_ROW + column;
                applyHidden(EnergyModuleSlotSemantics.MODULE[index], !visible || title);
                applyHidden(EnergyModuleSlotSemantics.COMPONENTS[index], !visible || title);
            }
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
     * 组件槽的两层状态贴图。
     *
     * <p>可用槽盖「存储组件槽」、模块给不出的那几格盖「不可用槽」——基础模块只有 1 个组件槽，它右边两格就是
     * 摆样子给玩家看「这里本来可以有两格」的。落点直接用 {@code slot.x/slot.y}：那本来就是 16×16 内容区的
     * 左上角，覆盖层切片也是 16×16。</p>
     */
    private void drawComponentSlotOverlays(GuiGraphics guiGraphics, int offsetX, int offsetY) {
        var display = getMenu().moduleDisplay();
        for (int band = 0; band < this.bands; band++) {
            // 标题行（与行表到头的那条空带）上没有槽位：一条覆盖层也不铺，否则整条带上会浮出 12 个「不可用」暗格。
            if (bandIsTitle(band) || bandMark(band).equals(EMPTY_MARK)) {
                continue;
            }
            for (int column = 0; column < MODULES_PER_ROW; column++) {
                int module = band * MODULES_PER_ROW + column;
                var tier = tierOfDisplay(display.getStackInSlot(module));
                if (tier == null) {
                    // 组末尾不满 3 个模块的那几列：没有模块就没槽，不铺任何覆盖层。
                    continue;
                }
                var slots = getMenu().getSlots(EnergyModuleSlotSemantics.COMPONENTS[module]);
                for (int part = 0; part < slots.size(); part++) {
                    var slot = slots.get(part);
                    boolean usable = part < tier.componentSlots();
                    var overlay = usable ? this.componentSlot : this.unavailableSlot;
                    overlay.copy().dest(offsetX + slot.x, offsetY + slot.y).blit(guiGraphics);
                }
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
                guiGraphics.fill(2, rowY + 1, TITLE_ROW_RIGHT_EDGE, rowY + this.bandHeight - 1,
                        highlight);
            }
            // 文字位置相对「等级图标槽」算，不跟带几何挂钩：图标由 AE2 自己画（那是槽里的物品），
            // 名字从图标右侧起，名字后面接该组的模块数量。
            int color = selected ? this.selectionColor : this.textColor;
            Component name = tierName(title);
            guiGraphics.drawString(font, name, textX, textY, color, false);
            guiGraphics.drawString(font,
                    Component.translatable("gui.ae2modularcells.tier_group_count", this.bandCounts[band]),
                    textX + font.width(name) + TITLE_TEXT_GAP, textY, color, false);
        }

        drawBarValues(guiGraphics);
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
            var title = bandTiers[band];
            if (title == null) {
                continue;
            }
            var slots = getMenu().getSlots(EnergyModuleSlotSemantics.TITLE_ICON[band]);
            if (slots.isEmpty()) {
                continue;
            }
            var slot = slots.get(0);
            if (isHovering(slot.x, slot.y, 16, 16, mouseX, mouseY)) {
                return title.id();
            }
        }
        return null;
    }

    /** 这条带是不是标题行。 */
    private boolean bandIsTitle(int band) {
        return bandTiers[band] != null;
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
     * 用服务端下发的**结构快照**在本地排出整张行表（照 AE2 样板访问终端：行表是客户端的事）。
     *
     * <p>快照是「按遍历序的等级短 id 序列」（{@code EnergyModuleTerminalMenu#structureSnapshot}），解析后交给
     * 纯函数 {@link TerminalRows#build}——分组、标题行、每行几个模块全在本地算，不再解析服务端拼好的行表字符串，
     * 也就不再有「等包那一帧」的补丁。</p>
     *
     * <p>快照还没到的那一帧走兜底：**先按全模块行排**（不带标题带），到了再重排一次。这样开屏第一帧就有正确的
     * 模块格，不会出现「空屏、要等一次画面更新」。</p>
     */
    private void refreshLocalRows() {
        String snapshot = getMenu().structureSnapshot;
        if (snapshot == null) {
            snapshot = "";
        }
        if (!snapshot.equals(this.lastSnapshot)) {
            this.lastSnapshot = snapshot;
            this.localRows.clear();
            if (snapshot.isEmpty()) {
                int modules = Math.max(0, getMenu().totalModules);
                for (int first = 0; first < modules; first += MODULES_PER_ROW) {
                    int count = Math.min(MODULES_PER_ROW, modules - first);
                    var indices = new int[count];
                    for (int column = 0; column < count; column++) {
                        indices[column] = first + column;
                    }
                    this.localRows.add(new TerminalRows.Row(null, first, count, indices));
                }
            } else {
                var ids = snapshot.split(",", -1);
                var tiers = new EnergyCellTier[ids.length];
                for (int index = 0; index < ids.length; index++) {
                    tiers[index] = EnergyCellTier.byId(ids[index]);
                }
                TerminalRows.build(tiers.length, index -> tiers[index], this.localRows);
            }
            this.appliedHidden.clear();
        }
        // 快照还没到（且结构非空）：这一帧**不隐藏任何带**——服务端已经按它自己的行表往窗口里填了内容，
        // 本地若按「每行 3 个模块」少算了几条带、把尾部带判成空带隐藏，就会出现「看不见但能写入」的组件格。
        // 铺满模块带只是过渡态，快照到达后立刻重排（appliedHidden 已在上面清掉）。
        this.snapshotPending = this.lastSnapshot.isEmpty() && getMenu().totalModules > 0;
        if (this.snapshotPending) {
            for (int band = 0; band < MAX_ROWS; band++) {
                this.bandTiers[band] = null;
                this.bandCounts[band] = MODULES_PER_ROW;
                this.bandMarks[band] = String.valueOf(BAND_MODULES);
            }
            return;
        }
        // 本地行表 → 这一屏三组带标记。下游（绘制、槽位可见性、标题文字）读的就是这三组数组，口径与服务端行表一致。
        for (int band = 0; band < MAX_ROWS; band++) {
            int rowIndex = getMenu().rowOffset + band;
            var row = rowIndex >= 0 && rowIndex < this.localRows.size() ? this.localRows.get(rowIndex) : null;
            this.bandTiers[band] = row == null ? null : row.title();
            this.bandCounts[band] = row == null ? 0 : row.moduleCount();
            if (row == null) {
                this.bandMarks[band] = EMPTY_MARK;
            } else if (row.isTitle()) {
                this.bandMarks[band] = BAND_TITLE_PREFIX + row.title().id() + MARK_SEPARATOR + groupModuleCount(rowIndex);
            } else {
                this.bandMarks[band] = String.valueOf(BAND_MODULES);
            }
        }
    }

    /** 本地行表里第 {@code titleRowIndex} 条标题行所属组的模块数（标题后面那几条模块行之和）。 */
    private int groupModuleCount(int titleRowIndex) {
        int count = 0;
        for (int index = titleRowIndex + 1; index < this.localRows.size(); index++) {
            var row = this.localRows.get(index);
            if (row.isTitle()) {
                break;
            }
            count += row.moduleCount();
        }
        return count;
    }

    /** 标题标记里那一段模块数；解析不出来给 0（界面少一个数字，不崩）。 */
    private static int parseCount(String text) {
        try {
            return Math.max(0, Integer.parseInt(text));
        } catch (NumberFormatException e) {
            return 0;
        }
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

    /** 展示槽里那张方块物品反推等级；空槽或不是模块方块时给 null。 */
    @Nullable
    private static EnergyCellTier tierOfDisplay(ItemStack displayStack) {
        if (displayStack.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof EnergyCellModuleBlock module) {
            return module.tier();
        }
        return null;
    }

    /** 等级短 id（同步过来的那个）换成可翻译的名字；认不出来就给空，界面不因为一个陌生 id 崩掉。 */
    private static Component tierName(@Nullable EnergyCellTier tier) {
        if (tier == null) {
            return Component.empty();
        }
        return Component.translatable("block.ae2modularcells." + tier.id() + "_energy_cell_module");
    }
}
