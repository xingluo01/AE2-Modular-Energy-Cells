package io.github.lounode.ae2modularcells.common.menu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;

import appeng.api.inventories.InternalInventory;
import appeng.menu.AEBaseMenu;
import appeng.menu.implementations.MenuTypeBuilder;
import appeng.menu.guisync.GuiSync;
import appeng.menu.SlotSemantic;
import appeng.menu.slot.AppEngSlot;

import io.github.lounode.ae2modularcells.AE2ModularCells;
import io.github.lounode.ae2modularcells.AEModularCellsRegistries;
import io.github.lounode.ae2modularcells.common.EnergyCellTier;
import io.github.lounode.ae2modularcells.common.StorageComponents;
import io.github.lounode.ae2modularcells.common.block.EnergyCellModuleBlock;
import io.github.lounode.ae2modularcells.common.block.entity.EnergyCellModuleBlockEntity;
import io.github.lounode.ae2modularcells.common.logic.StructureStats;
import io.github.lounode.ae2modularcells.common.logic.TerminalRows;

/**
 * 模块管理终端：右键任意一块模块打开，显示<b>它所在那一整段结构</b>里的模块。
 *
 * <p>面板只有 6 条 18px 带，而结构最大 4096 块，所以窗口是滑动的。滑动粒度是<b>行</b>：行表按等级分组
 * （每档一条标题行 + 若干模块行，每行 3 个模块），见 {@link TerminalRows}。窗口里只有
 * 最多 {@link EnergyModuleSlotSemantics#MAX_MODULE_SLOTS} 个模块槽与它们的组件槽，滑动只改「窗口对准哪几行」，
 * 不动槽位坐标——AE2 的槽位坐标在 {@code init()} 期只解析一次，每帧改 {@code Slot.x/y} 会与可选槽的背景绘制
 * 错位（见 {@code docs/ARCHITECTURE.md} 指出的那条口径）。</p>
 *
 * <p>两侧的数据来源不同：服务端从方块实体现取（权威），客户端只有菜单同步过来的那份。所以两个槽位用的库存
 * 在客户端各自留一份镜像——同步写进来的栈会落到镜像里，读出来的也是镜像；服务端一侧则直连方块实体。</p>
 */
public class EnergyModuleTerminalMenu extends AEBaseMenu {

    /** 客户端 → 服务端：把窗口滑到第几行。名字即协议，不许改。 */
    private static final String ACTION_SET_ROW_OFFSET = "setRowOffset";

    public static final MenuType<EnergyModuleTerminalMenu> TYPE = MenuTypeBuilder
            .create(EnergyModuleTerminalMenu::new, EnergyCellModuleBlockEntity.class)
            // 用 buildUnregistered：菜单类型由本模组的 DeferredRegister 注册，走 build() 会与 AE2 自己的
            // 注册队列形成同实例双通道注册。
            .buildUnregistered(
                    ResourceLocation.fromNamespaceAndPath(AE2ModularCells.MOD_ID, "energy_module_terminal"));

    private static final int MODULES_PER_ROW = EnergyModuleSlotSemantics.MODULES_PER_ROW;
    private static final int MAX_ROWS = EnergyModuleSlotSemantics.MAX_ROWS;
    private static final int MAX_MODULE_SLOTS = EnergyModuleSlotSemantics.MAX_MODULE_SLOTS;
    private static final int COMPONENTS_PER_MODULE = EnergyModuleSlotSemantics.COMPONENT_SLOTS_PER_MODULE;

    /** 客户端 → 服务端：把窗口滑到第几行。名字即协议，不许改。 */
    private static final String ACTION_SET_VISIBLE_ROWS = "setVisibleRows";
    /** 客户端 → 服务端：选中哪个等级组（空串 = 取消选中）。 */
    private static final String ACTION_SELECT_GROUP = "selectGroup";

    private final EnergyCellModuleBlockEntity host;

    /**
     * 窗口里当前可见的模块，下标 = {@code 行 * 3 + 列}；标题行或组末尾的空位是 {@code null}。
     *
     * <p>允许 {@code null} 是刻意的：窗口是一条带一条带对齐的，空位必须占住下标，否则模块会随着前面空位
     * 多少在整个面板里左右漂浮。</p>
     */
    private final List<EnergyCellModuleBlockEntity> visible = new ArrayList<>();

    private final ModuleDisplayInventory moduleDisplay = new ModuleDisplayInventory();
    private final ModuleComponentInventory moduleComponents = new ModuleComponentInventory();
    private final ModuleTitleInventory moduleTitles = new ModuleTitleInventory();

    private int lastRefreshedOffset = Integer.MIN_VALUE;
    @Nullable
    private StructureStats lastRefreshedStats;

    /** 等级短 id（{@code basic} / {@code dense} / {@code superdense}），客户端用 {@link EnergyCellTier#byId} 还原。 */
    @GuiSync(60)
    public String tierId = "";

    /** 结构边长；未成型单块是 1。 */
    @GuiSync(61)
    public int structureSide = 1;

    /** 结构里的模块数（边长³）。 */
    @GuiSync(62)
    public int totalModules = 1;

    @GuiSync(63)
    public long totalCapacity;

    @GuiSync(64)
    public long totalTransferRate;

    @GuiSync(65)
    public long totalInputRate;

    /**
     * 当前能量（AE）。M4（能量池与网格接入）之前恒为 0——储能进度条按它填，界面上是空条。
     */
    @GuiSync(66)
    public long currentEnergy;

    /**
     * 当前输入速率（AE/t）。同样是 M4 之前恒为 0。
     */
    @GuiSync(67)
    public long currentInputRate;

    /** 窗口顶端对应的行号（一行 = 3 个模块）。 */
    @GuiSync(68)
    public int rowOffset;

    /** 总行数（行表长度），滚动条用它算范围。 */
    @GuiSync(69)
    public int totalRows = 1;

    /**
     * 窗口里 6 条带各自是什么，逗号分隔，一条一个标记：
     *
     * <pre>
     * T:basic:27  标题行：这一档的短 id + 这一组里的模块数
     * M           模块行（内容看对应的模块槽）
     * -           行表到头了，或超出当前可见条数（这一条带空着）
     * </pre>
     *
     * <p>名字即协议：屏幕靠它决定每条带贴哪一张图、要不要写标题。状态跟着行表走，不改名字、不复用。</p>
     */
    /** 整段出现过哪几档模块（位掩码）；多于一位就是混拼，顶栏据此换文案。 */
    @GuiSync(71)
    public int tierMask;

    /**
     * 窗口里实际显示几条带（4~12）。
     *
     * <p>由客户端根据自己的「终端样式」档位与窗口高度算出来再报给服务端：条数决定服务端要填几行内容、
     * 也决定滚动范围。服务端只做区间夹取，不信客户端给的任意值。</p>
     */
    @GuiSync(72)
    public int visibleRows = EnergyModuleSlotSemantics.MIN_ROWS;

    /**
     * 被选中的等级组（短 id，空串 = 未选）。
     *
     * <p>存在服务端：它影响的是服务端的快速移动（只往选中的那一组里塞），不能由客户端单方面决定。
     * 客户端点标题图标时只发意图，服务端校验「这一档确实在这段里出现过」后才认。</p>
     */
    @GuiSync(73)
    public String selectedTier = "";

    /**
     * 结构快照：按 {@code host.moduleAt} 遍历序、每模块一个等级短 id，逗号分隔。
     *
     * <p>客户端拿它跑 {@code common/logic/TerminalRows.build}（纯函数，不碰世界）在本地得出整张行表——
     * 分组、标题带、模块带的行数全在客户端算，不再由服务端下发「哪一条带是什么」的字符串。
     * 上限是 16³=4096 个字符，且只在结构或账目变化时更新（不是每 tick）。</p>
     */
    @GuiSync(74)
    public String structureSnapshot = "";

    public EnergyModuleTerminalMenu(int id, Inventory playerInventory, EnergyCellModuleBlockEntity host) {
        super(TYPE, id, playerInventory, host);
        this.host = host;

        // 槽位按最大条数一次性建完（不够时由屏幕隐藏）：槽位坐标写死在样式文档里、运行期不改，
        // 而「终端样式」只改「哪几条带在用」——所以不能按当前条数建槽，否则切档又要重建菜单。
        for (int module = 0; module < MAX_MODULE_SLOTS; module++) {
            addSlot(new AppEngSlot(moduleDisplay, module), EnergyModuleSlotSemantics.MODULE[module]);
            for (int part = 0; part < COMPONENTS_PER_MODULE; part++) {
                addSlot(new AppEngSlot(moduleComponents, module * COMPONENTS_PER_MODULE + part),
                        EnergyModuleSlotSemantics.COMPONENTS[module]);
            }
        }
        for (int row = 0; row < MAX_ROWS; row++) {
            addSlot(new AppEngSlot(moduleTitles, row), EnergyModuleSlotSemantics.TITLE_ICON[row]);
        }
        createPlayerInventorySlots(playerInventory);

        registerClientAction(ACTION_SET_ROW_OFFSET, Integer.class, this::setRowOffset);
        registerClientAction(ACTION_SET_VISIBLE_ROWS, Integer.class, this::setVisibleRows);
        registerClientAction(ACTION_SELECT_GROUP, String.class, this::selectGroup);

        if (isServerSide()) {
            syncFromHost();
        }
    }

    /** 供屏幕显示状态用；组件槽与模块槽见 {@link #moduleComponents()} / {@link #moduleDisplay()}。 */
    public EnergyCellModuleBlockEntity host() {
        return this.host;
    }

    public InternalInventory moduleComponents() {
        return this.moduleComponents;
    }

    /** 标题行的等级图标槽用的库存（只读展示，见 {@link ModuleTitleInventory}）。 */
    public InternalInventory moduleTitles() {
        return this.moduleTitles;
    }

    public InternalInventory moduleDisplay() {
        return this.moduleDisplay;
    }

    /**
     * 窗口滑动。客户端只发意图，真正的夹取在服务端做——两侧各滚各的就会出现「屏幕滑了、槽位没动」。
     */
    public void setRowOffset(int row) {
        if (!isServerSide()) {
            sendClientAction(ACTION_SET_ROW_OFFSET, row);
            return;
        }
        int clamped = Math.max(0, Math.min(row, Math.max(0, this.totalRows - 1)));
        if (clamped == this.rowOffset) {
            return;
        }
        this.rowOffset = clamped;
        if (!this.host.isCoreChunkLoaded()) {
            // 核心区块未加载：先把意图记下，等它回来再同步（现在去同步就是去碰未加载的区块）。
            return;
        }
        // 立刻按新窗口重算，别让玩家等下一拍才看到内容。
        syncFromHost();
    }

    /**
     * 客户端报「这一屏显示几条带」。服务端只夹区间，并在条数真变了时重填窗口。
     *
     * <p>条数在客户端是靠「终端样式 + 窗口高度」算出来的，服务端算不出同一个值（它不知道玩家窗口多大），
     * 所以只能由客户端报——但报上来之后仍由服务端夹到 [MIN, MAX]，免得一个改过的客户端让服务端去填
     * 12 行以外的内容或把槽位对错。</p>
     */
    public void setVisibleRows(int rows) {
        if (!isServerSide()) {
            sendClientAction(ACTION_SET_VISIBLE_ROWS, rows);
            return;
        }
        int clamped = Math.max(EnergyModuleSlotSemantics.MIN_ROWS,
                Math.min(rows, EnergyModuleSlotSemantics.MAX_ROWS));
        if (clamped == this.visibleRows) {
            return;
        }
        this.visibleRows = clamped;
        this.rowOffset = Math.max(0, Math.min(this.rowOffset, Math.max(0, this.totalRows - 1)));
        if (!this.host.isCoreChunkLoaded()) {
            // 同上：条数先记下，核心区块回来后再重填窗口。
            return;
        }
        syncFromHost();
    }

    /**
     * 选中 / 取消选中一个等级组。
     *
     * <p>客户端只发意图（右击标题行的等级图标）；服务端校验「这一档确实在这段结构里出现过」之后才认。
     * 空串表示取消选中。选中状态影响快速移动的目标筛选，见 {@link #isValidQuickMoveDestination}。</p>
     */
    public void selectGroup(String tierId) {
        if (!isServerSide()) {
            sendClientAction(ACTION_SELECT_GROUP, tierId == null ? "" : tierId);
            return;
        }
        String wanted = tierId == null ? "" : tierId;
        if (!wanted.isEmpty()) {
            var tier = EnergyCellTier.byId(wanted);
            if (tier == null || (this.tierMask & StructureStats.tierBit(tier)) == 0) {
                // 没这一档就不认；这也顺手挡住了「客户端乱报一个 id」的情形。
                return;
            }
        }
        this.selectedTier = this.selectedTier.equals(wanted) ? "" : wanted;
    }

    /**
     * 快速移动的目标筛选。
     *
     * <p>选中了某个等级组时，组件只会被塞进<b>那一组的模块槽</b>里——一个 4096 块的段里想给某一档换组件，
     * 靠它在滚来滚去的列表里点很累；选中之后 shift+左键就只往那一组落。没选中时不加任何限制。</p>
     *
     * <p>实现方式是覆盖 AE2 的目标筛选钩子（{@code AEBaseMenu#quickMoveToOtherSlots} 就是拿它收集目标的），
     * 而不是自己重写一遍 {@code quickMoveStack}：叠放、空位优先、剩余量回写这些行为都留在上游那一份里。</p>
     */
    @Override
    protected boolean isValidQuickMoveDestination(Slot candidateSlot, ItemStack stackToMove,
            boolean fromPlayerSide) {
        if (!super.isValidQuickMoveDestination(candidateSlot, stackToMove, fromPlayerSide)) {
            return false;
        }
        if (this.selectedTier.isEmpty()) {
            return true;
        }
        var semantic = getSlotSemantic(candidateSlot);
        if (semantic == null) {
            return true;
        }
        int windowIndex = moduleIndexOfSemantic(semantic);
        if (windowIndex < 0) {
            // 不是模块/组件槽（例如玩家背包）：不归这条规则管。
            return true;
        }
        var tier = moduleTierAt(windowIndex);
        return tier != null && tier.id().equals(this.selectedTier);
    }

    /** 这个语义是不是本屏的模块/组件槽；不是给 -1，是就给对应的窗口下标。 */
    private static int moduleIndexOfSemantic(SlotSemantic semantic) {
        for (int index = 0; index < MAX_MODULE_SLOTS; index++) {
            if (semantic == EnergyModuleSlotSemantics.MODULE[index]
                    || semantic == EnergyModuleSlotSemantics.COMPONENTS[index]) {
                return index;
            }
        }
        return -1;
    }

    @Override
    public void broadcastChanges() {
        if (isServerSide()) {
            if (!this.host.isCoreChunkLoaded()) {
                // 核心所在区块被卸（玩家走远）：这一拍不碰它——尤其是不能让它走下面的「结构没了」分支，
                // 那会把玩家的屏关掉（区块没加载 ≠ 结构被拆）。关屏留给真正拆掉核心那条路径。
                // 注意这一判断必须在 isFormed() 之前：isFormed() 自己就要问核心。
                super.broadcastChanges();
                return;
            }
            if (!this.host.isFormed()) {
                // 结构没了就把玩家退出去（需求 3.2）：屏幕留着只会显示一份不成立的账目。
                // 主动关屏走 AE2 自己的写法（getPlayer().closeContainer()），不自己发关屏包。
                if (getPlayer().containerMenu == this) {
                    getPlayer().closeContainer();
                }
                return;
            }
            syncFromHost();
        }
        super.broadcastChanges();
    }

    /** 服务端每一拍把结构账目与窗口对齐一次。 */
    private void syncFromHost() {
        var stats = this.host.stats();
        this.tierId = stats.tier().id();
        this.tierMask = stats.tierMask();
        this.structureSide = stats.side();
        this.totalModules = stats.moduleCount();
        this.totalCapacity = stats.totalCapacity();
        this.totalTransferRate = stats.totalTransferRate();
        this.totalInputRate = stats.totalInputRate();
        // 两个「当前值」是实测的：储能取整段池子里的数，输入速率取本 tick 收进来的量（没人供电时就是 0）。
        this.currentEnergy = this.host.storedEnergy();
        // 与监控器同源：近 1 秒均值（不是本 tick 峰值 + 线性衰减的那个口径）。
        this.currentInputRate = this.host.averageInputRate();

        var rows = this.host.rows();
        this.totalRows = Math.max(1, rows.size());
        this.rowOffset = Math.max(0, Math.min(this.rowOffset, Math.max(0, this.totalRows - 1)));

        // 结构账目没变、窗口也没挪时不必重填：添窗口要逐块取方块实体（默认行表则由宿主缓存），而窗口每拍都
        // 可能问到。但 rowLayout / tierMask 这两个同步字段要在第一拍就填上，所以首次（lastRefreshedStats
        // 为 null）必须走一次。
        if (this.rowOffset != this.lastRefreshedOffset || !stats.equals(this.lastRefreshedStats)) {
            fillWindow(rows);
            this.lastRefreshedOffset = this.rowOffset;
            this.lastRefreshedStats = stats;
        }
    }

    /**
     * 按窗口顶端把 {@link #visibleRows} 条带填上：模块行交出最多 3 个模块，标题行交出一个等级图标，
     * 行表末尾留空位。剩下的槽位（超过可见条数的、以及每条带上用不到的那一套）由屏幕隐藏。
     *
     * <p>同时把 {@link #rowLayout} 拼出来——屏幕只知道「哪一条带是什么」，不自己重新分组（那是服务端的事，
     * 两侧各排一次就可能不一致）。标题行还带上了这一组的模块数，供标题文字用。</p>
     */
    private void fillWindow(List<TerminalRows.Row> rows) {
        this.visible.clear();
        var layout = new StringBuilder();
        for (int band = 0; band < MAX_ROWS; band++) {
            if (band > 0) {
                layout.append(',');
            }
            int rowIndex = this.rowOffset + band;
            var row = rowIndex >= 0 && rowIndex < rows.size() ? rows.get(rowIndex) : null;
            if (band >= this.visibleRows) {
                // 超出可见条数的带：界面上根本不显示，标记成空，内容也不填。
                layout.append('-');
            } else if (row == null) {
                layout.append('-');
            } else if (row.isTitle()) {
                layout.append("T:").append(row.title().id()).append(':').append(groupModuleCount(rows, rowIndex));
            } else {
                layout.append('M');
            }
            for (int column = 0; column < MODULES_PER_ROW; column++) {
                // 超出可见条数的带不填内容：屏幕上根本不显示这几行，填进去的模块照样能被快速移动选中
                // （服务端筛选只看槽位能不能放，不看屏幕藏没藏），玩家会看到组件“凭空消失”。
                // 取本行第 column 个模块要用行表给的**下标表**：同一档的模块在遍历序里通常不连续，
                // 拿 firstModule + column 会把别档的模块当成本档的填进来。
                boolean inWindow = band < this.visibleRows;
                int moduleIndex = row == null || row.isTitle() ? -1 : row.moduleIndex(column);
                this.visible.add(inWindow && moduleIndex >= 0 ? this.host.moduleAt(moduleIndex) : null);
            }
            this.moduleTitles.setRow(band, row, band < this.visibleRows);
        }
        // 行表定型后重算服务端镜像：这一句是「模块本体能不能同步到客户端」的唯一开关。
        this.moduleDisplay.refreshServerStacks();
        this.structureSnapshot = buildStructureSnapshot();
    }

    /**
     * 按遍历序拼结构快照（见 {@link #structureSnapshot}）。
     *
     * <p>这里用 {@code totalModules}（边长³）而不是窗口内容：快照描述的是**整段结构**，与当前窗口落在哪儿无关——
     * 客户端本地排完行表之后再按窗口取显示用的那几条带。</p>
     */
    private String buildStructureSnapshot() {
        var tiers = this.host.moduleTiersInOrder();
        // **一模块一字符**：等级在枚举里的序号（a + ordinal），拿不到等级的给 ?。
        //
        // 长度是硬约束，不是省流量：@GuiSync 的字符串字段走 FriendlyByteBuf.writeUtf，上限 32767 字符，
        // 而 16³ 结构有 4096 块。早期版本写的是 tier.id()（"basic," / "advanced," 这类），一条 11 字符左右，
        // 4096 块拼出 45 055 字符 ⇒ 服务端发同步包时抛 EncoderException: String too big，
        // GUI 直接打不开（客户端随后断线，看起来像「打开 GUI 保存就崩」）。
        // 一字符制下上界 = 4096 字符，离上限有 8 倍余量。
        var snapshot = new StringBuilder(tiers.length);
        for (var tier : tiers) {
            snapshot.append(tier == null ? (char) 63 : (char) (97 + tier.ordinal()));
        }
        return snapshot.toString();
    }

    /** 第 {@code titleRowIndex} 条标题行所对应的等级组里有多少个模块（标题后面那行文字要用）。 */
    private static int groupModuleCount(List<TerminalRows.Row> rows, int titleRowIndex) {
        int count = 0;
        for (int i = titleRowIndex + 1; i < rows.size() && !rows.get(i).isTitle(); i++) {
            count += rows.get(i).moduleCount();
        }
        return count;
    }

    /** 窗口第 {@code windowIndex} 个模块；窗口外或不在服务端返回 null。 */
    @Nullable
    private EnergyCellModuleBlockEntity windowModuleAt(int windowIndex) {
        if (!isServerSide() || windowIndex < 0 || windowIndex >= this.visible.size()) {
            return null;
        }
        return this.visible.get(windowIndex);
    }

    /**
     * 窗口第 {@code windowIndex} 个模块的等级。
     *
     * <p>服务端问方块实体；客户端没有那份列表，就从展示槽里那张方块物品反推——展示槽的内容是同步过来的，
     * 于是客户端能独立判断「这一格该不该收组件」，不必再多加一条协议。</p>
     */
    @Nullable
    private EnergyCellTier moduleTierAt(int windowIndex) {
        if (windowIndex < 0 || windowIndex >= MAX_MODULE_SLOTS) {
            return null;
        }
        if (isServerSide()) {
            var module = windowModuleAt(windowIndex);
            return module == null ? null : module.tier();
        }
        var display = this.moduleDisplay.getStackInSlot(windowIndex);
        if (display.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof EnergyCellModuleBlock module) {
            return module.tier();
        }
        return null;
    }

    /**
     * 模块展示槽用的库存：一格一个模块，放的是该模块的方块物品并把坐标写进物品名——屏幕的文本带直接取这个
     * 名字，于是模块的身份跟着槽位同步走，不需要另开一条「窗口数据」协议。
     */
    private final class ModuleDisplayInventory implements InternalInventory {

        private final ItemStack[] clientMirror = new ItemStack[MAX_MODULE_SLOTS];

        /**
         * 服务端镜像：窗口里每一格该显示的东西。
         *
         * <p>必须**存下来**，不能每次现算。菜单槽位的同步只推「跟上一次不一样」的格子，而现算的值每次调用
         * 都是新造的 ItemStack，永远翻不出「变了」这个结论——于是客户端镜像一直空着：模块本体不显示，
         * 屏幕又据「这一列取不到等级」把组件格覆盖层整列跳过，看起来就是「组件槽位置全空、不可交互」。</p>
         */
        private final ItemStack[] serverStacks = new ItemStack[MAX_MODULE_SLOTS];

        private ModuleDisplayInventory() {
            Arrays.fill(this.clientMirror, ItemStack.EMPTY);
            Arrays.fill(this.serverStacks, ItemStack.EMPTY);
        }

        /** 窗口内容变了（滚动、结构变化、开屏）之后重算服务端镜像；同步据此把变化推给客户端。 */
        private void refreshServerStacks() {
            for (int slot = 0; slot < MAX_MODULE_SLOTS; slot++) {
                var module = windowModuleAt(slot);
                this.serverStacks[slot] = module == null ? ItemStack.EMPTY : labelOf(module);
            }
        }

        @Override
        public int size() {
            return MAX_MODULE_SLOTS;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            if (slot < 0 || slot >= MAX_MODULE_SLOTS) {
                return ItemStack.EMPTY;
            }
            return isServerSide() ? this.serverStacks[slot] : this.clientMirror[slot];
        }

        /** 只读槽：服务端不接受写入，客户端把同步过来的栈收进镜像。 */
        @Override
        public void setItemDirect(int slot, ItemStack stack) {
            // 镜像只在客户端有意义（服务端读路径不问它），服务端写进来只会白占一格引用。
            if (!isServerSide() && slot >= 0 && slot < MAX_MODULE_SLOTS) {
                this.clientMirror[slot] = stack;
            }
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return false;
        }

        @Override
        public int getSlotLimit(int slot) {
            return 1;
        }

        /** 取不走：{@code AppEngSlot#mayPickup} 靠它模拟抽取，返回空即「不可取」。 */
        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return ItemStack.EMPTY;
        }

        private ItemStack labelOf(EnergyCellModuleBlockEntity module) {
            var stack = new ItemStack(module.getBlockState().getBlock().asItem());
            var pos = module.getBlockPos();
            stack.set(DataComponents.CUSTOM_NAME, Component.translatable(
                    "gui.ae2modularcells.module_label", pos.getX(), pos.getY(), pos.getZ()));
            return stack;
        }
    }

    /**
     * 组件槽用的库存：{@code 9 个模块 × 3 格}。每格直连对应模块的组件库存，写进去就触发那一块的结构重算。
     *
     * <p>模块不存在、或该模块的等级只有 1~2 个槽时，多出来的格一律「不存在」：读是空、写被拒、不收物品——
     * 屏幕据此盖上「不可用槽」覆盖层。</p>
     */
    private final class ModuleComponentInventory implements InternalInventory {

        private final ItemStack[] clientMirror = new ItemStack[MAX_MODULE_SLOTS * COMPONENTS_PER_MODULE];

        private ModuleComponentInventory() {
            Arrays.fill(this.clientMirror, ItemStack.EMPTY);
        }

        @Override
        public int size() {
            return MAX_MODULE_SLOTS * COMPONENTS_PER_MODULE;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            if (slot < 0 || slot >= size()) {
                return ItemStack.EMPTY;
            }
            if (!isServerSide()) {
                return this.clientMirror[slot];
            }
            var module = windowModuleAt(moduleIndex(slot));
            int part = componentIndex(slot);
            if (module == null || part >= module.tier().componentSlots()) {
                return ItemStack.EMPTY;
            }
            return module.getInternalInventory().getStackInSlot(part);
        }

        @Override
        public void setItemDirect(int slot, ItemStack stack) {
            if (slot < 0 || slot >= size()) {
                return;
            }
            this.clientMirror[slot] = stack;
            if (!isServerSide()) {
                return;
            }
            var module = windowModuleAt(moduleIndex(slot));
            int part = componentIndex(slot);
            if (module == null || part >= module.tier().componentSlots()) {
                return;
            }
            module.getInternalInventory().setItemDirect(part, stack);
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            int part = componentIndex(slot);
            var tier = moduleTierAt(moduleIndex(slot));
            return tier != null && part < tier.componentSlots() && StorageComponents.isComponent(stack);
        }

        @Override
        public int getSlotLimit(int slot) {
            // 一个组件槽放一件：容量折扣按「插了几件」算，堆叠会让公式失去意义。
            return 1;
        }

        private int moduleIndex(int slot) {
            return slot / COMPONENTS_PER_MODULE;
        }

        private int componentIndex(int slot) {
            return slot % COMPONENTS_PER_MODULE;
        }
    }

    /**
     * 标题行的等级图标槽用的库存：每条带一格，内容是该档模块的方块物品（不可取出、不可放入）。
     *
     * <p>放的是「假物品」：只是拿这一档的方块物品当图标，不带坐标也不代表某一台机器——标题行讲的是「这一组是
     * 哪一档」，而不是某一块。玩家看不到它的内部数据，拿也拿不走（抽取恒空、放入恒拒）。</p>
     */
    private final class ModuleTitleInventory implements InternalInventory {

        /** 每格对应的等级；{@code null} = 这一条带不是标题行（服务端填，客户端用同步过来的栈）。 */
        private final EnergyCellTier[] tiers = new EnergyCellTier[MAX_ROWS];
        private final ItemStack[] clientMirror = new ItemStack[MAX_ROWS];

        private ModuleTitleInventory() {
            Arrays.fill(this.clientMirror, ItemStack.EMPTY);
        }

        /** 服务端按行表重填一条带：不是标题行、超出可见条数、或行表到头了就清空。 */
        private void setRow(int band, @Nullable TerminalRows.Row row, boolean withinVisibleRows) {
            if (band < 0 || band >= MAX_ROWS) {
                return;
            }
            this.tiers[band] = row != null && row.isTitle() && withinVisibleRows ? row.title() : null;
        }

        @Override
        public int size() {
            return MAX_ROWS;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            if (slot < 0 || slot >= MAX_ROWS) {
                return ItemStack.EMPTY;
            }
            if (!isServerSide()) {
                return this.clientMirror[slot];
            }
            var tier = this.tiers[slot];
            return tier == null ? ItemStack.EMPTY : new ItemStack(AEModularCellsRegistries.moduleBlock(tier).asItem());
        }

        @Override
        public void setItemDirect(int slot, ItemStack stack) {
            if (!isServerSide() && slot >= 0 && slot < MAX_ROWS) {
                this.clientMirror[slot] = stack;
            }
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return false;
        }

        @Override
        public int getSlotLimit(int slot) {
            return 1;
        }

        /** 假物品：拿不走（{@code AppEngSlot#mayPickup} 靠它模拟抽取）。 */
        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return ItemStack.EMPTY;
        }
    }
}
