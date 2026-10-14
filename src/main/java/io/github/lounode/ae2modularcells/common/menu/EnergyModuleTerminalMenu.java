package io.github.lounode.ae2modularcells.common.menu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
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
import io.github.lounode.ae2modularcells.common.ModularCellTiers;
import io.github.lounode.ae2modularcells.common.StorageComponents;
import io.github.lounode.ae2modularcells.common.block.EnergyCellModuleBlock;
import io.github.lounode.ae2modularcells.common.block.entity.EnergyCellModuleBlockEntity;
import io.github.lounode.ae2modularcells.common.logic.StructureStats;
import io.github.lounode.ae2modularcells.common.logic.TerminalRows;

/**
 * 模块管理终端：右键任意一块模块打开，显示<b>它所在那一整段结构</b>里的模块。
 *
 * <p>面板只有 4~12 条 18px 带，而结构最大 4096 块，所以窗口是滑动的。滑动粒度是<b>行</b>：行表按等级分组
 * （每档一条标题行 + 若干组件行），见 {@link TerminalRows}。窗口里最多
 * {@link EnergyModuleSlotSemantics#MAX_SLOT_COUNT} 个组件格与每条带一个等级图标格，滑动只改「窗口对准哪几行」，
 * 不动槽位坐标——AE2 的槽位坐标在 {@code init()} 期只解析一次，每帧改 {@code Slot.x/y} 会与可选槽的背景绘制
 * 错位（见 {@code docs/ARCHITECTURE.md} 指出的那条口径）。</p>
 *
 * <p><b>组件格是「档 × 组件类型」的聚合视图</b>：底层数据没有变（组件仍逐个存在各模块自己的 1/2/3 个槽里，
 * 容量仍逐块计），变的是玩家看到的样子——一档共用一行 12 格，一种组件占一格（同种可堆叠，格内显示件数），
 * 12 格占满就往下一行延伸，一档总共能装多少件仍由「该档模块数 × 该档每块槽位数」封顶。放入时不必挑是哪一块，
 * 本类自己把新组件摊到该档各模块的空槽上（与手持组件右键方块走的 {@code installComponent} 同一套优先序）。</p>
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

    private static final int SLOTS_PER_ROW = EnergyModuleSlotSemantics.SLOTS_PER_ROW;
    private static final int MAX_ROWS = EnergyModuleSlotSemantics.MAX_ROWS;
    private static final int MAX_SLOT_COUNT = EnergyModuleSlotSemantics.MAX_SLOT_COUNT;

    /** 报给原版的单格件数上限：见 {@link EnergyModuleSlotSemantics#MAX_REPORTED_COUNT}。 */
    private static final int MAX_REPORTED_COUNT = EnergyModuleSlotSemantics.MAX_REPORTED_COUNT;

    /** 客户端 → 服务端：把窗口滑到第几行。名字即协议，不许改。 */
    private static final String ACTION_SET_VISIBLE_ROWS = "setVisibleRows";
    /** 客户端 → 服务端：选中哪个等级组（空串 = 取消选中）。 */
    private static final String ACTION_SELECT_GROUP = "selectGroup";

    private final EnergyCellModuleBlockEntity host;

    /** 当前窗口里每条带对应的行；空带是 {@code null}。组件槽的读路径、标题文字、选中高亮全靠它。 */
    private final TerminalRows.Row[] bandRows = new TerminalRows.Row[MAX_ROWS];

    /**
     * 每档已装组件的聚合：类型表（按量级升序）+ 每种的件数，键是等级 id。
     *
     * <p>随行表重建（见 {@link #fillWindow}），本拍内供组件槽的读路径反复使用。不能每次问都扫一遍结构：
     * 144 个槽每拍都会被广播问到，而一段 16³ 的结构有 4096 块。</p>
     */
    private final Map<String, List<ComponentKind>> kindsByTier = new HashMap<>();

    private final ModuleComponentInventory moduleComponents = new ModuleComponentInventory();
    private final ModuleTitleInventory moduleTitles = new ModuleTitleInventory();

    /** 一种已装组件：代表栈（件数恒为 1，真实件数由屏幕画）+ 件数。 */
    private record ComponentKind(ItemStack stack, int count) {
    }

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
     * 窗口里每条带各自是什么，逗号分隔，一条一个标记：
     *
     * <pre>
     * T:basic   标题行：这一档的短 id
     * S:basic   组件行：它属于哪一档
     * -         行表到头了，或超出当前可见条数（这一条带空着）
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

    /**
     * 每档已装的组件种类数，逗号分隔、按固定位号顺序（与 {@link ModularCellTiers#all()} 同序）。
     *
     * <p>客户端拿它——而不是从槽内容反推——就能排出整张行表。从槽反推会自相矛盾：格子摆在哪里由行表决定，
     * 而行表又要先知道有几格。</p>
     */
    @GuiSync(75)
    public String kindSnapshot = "";

    /**
     * 每档已装组件的<b>件数</b>（同种算多件），逗号分隔、与 {@link #kindSnapshot} 同序。
     *
     * <p>标题行要写「已装配 a / b」：a 是件数，不是种类数，所以不能从 {@link #kindSnapshot} 反推。</p>
     */
    @GuiSync(76)
    public String assemblySnapshot = "";

    /**
     * 每个组件格已装的件数，逗号分隔（下标 = {@code 带 × 12 + 列}，与槽位线性下标同序）。
     *
     * <p>屏幕用它画格内数字。原版只会把栈的数量画到 {@link #MAX_REPORTED_COUNT}，超过的得自己补上，否则玩家
     * 看到的装配量是错的。</p>
     */
    @GuiSync(77)
    public String slotCountSnapshot = "";

    public EnergyModuleTerminalMenu(int id, Inventory playerInventory, EnergyCellModuleBlockEntity host) {
        super(TYPE, id, playerInventory, host);
        this.host = host;

        // 槽位按最大条数一次性建完（不够时由屏幕隐藏）：槽位坐标写死在样式文档里、运行期不改，
        // 而「终端样式」只改「哪几条带在用」——所以不能按当前条数建槽，否则切档又要重建菜单。
        for (int band = 0; band < MAX_ROWS; band++) {
            for (int column = 0; column < SLOTS_PER_ROW; column++) {
                addSlot(componentSlot(band, column), EnergyModuleSlotSemantics.componentSemantic(band));
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

    /**
     * 建一个组件槽。
     *
     * <p>关掉原版那份数量（{@code setHideAmount}）：这一格的栈数量只是「报给原版的上限」，给左键取整格、右键
     * 取一半这些搬运量用（见 {@link EnergyModuleSlotSemantics#MAX_REPORTED_COUNT}），而真实件数由屏幕画。
     * 两处都画的结果是两个数字叠在同一个角落（原版装饰渲染与屏幕用的是同一个坐标），超过一叠的格子看上去
     * 就是一团糊；关掉原版那份之后数字只有一个出处。</p>
     *
     * <p>作用机制在 AE2 侧：它用 {@code AbstractContainerScreenMixin} 把原版 {@code renderSlot} 里的栈换成
     * {@code AppEngSlot#getDisplayStack()}，只对 {@code AppEngSlot} 生效——所以这个开关必须在槽上，而不是在
     * 取数时。</p>
     */
    private AppEngSlot componentSlot(int band, int column) {
        return new ComponentBucketSlot(band * SLOTS_PER_ROW + column);
    }

    /**
     * 组件桶的槽：一格 = 一种组件的一个桶，而不是一个物品栈。
     *
     * <p><b>为何必须自己建槽类</b>：原版那套「读这一格 → 算能放多少 → 写回这一格」的搬运模型假定格子背后是
     * 一个物品栈，而桶的底料是「该档各模块的真实槽」（件数可能远超一叠，也可能散在几十块模块上）。
     * AE2 的 shift 点击走的是 {@code AEBaseMenu#quickMoveToOtherSlots} → {@code Slot#safeInsert}，而
     * {@code AppEngSlot} 没有覆写 {@code safeInsert}：它默认的实现会拿 {@code getMaxStackSize(stack)} 去夹那
     * 个封顶到 {@value EnergyModuleSlotSemantics#MAX_REPORTED_COUNT} 的代表栈，最后经
     * {@code setByPlayer → set → setItemDirect} 写回——而 {@code setItemDirect} 在服务端是空实现（桶没有可整体
     * 替换的底料）。结果是物品被从手上扣掉、却哪儿都没进去：玩家看到的是「组件从背包消失了，GUI 里也没多出
     * 来」。</p>
     *
     * <p>所以这里把 {@code safeInsert} 接到聚合库存自己的 {@code insertItem} 上——它按「该档真实剩余名额」算能
     * 放多少、并把组件摊到各模块的空槽（见 {@code installInto}）。</p>
     */
    private final class ComponentBucketSlot extends AppEngSlot {

        /**
         * 这一格在聚合库存里的下标。不复用 {@code AppEngSlot} 的字段：那是它的私有不变量，换个版本就可能换个
         * 名字或可见性，而这里只依赖「建槽时给了哪一格」。
         */
        private final int bucketIndex;

        private ComponentBucketSlot(int bucketIndex) {
            super(moduleComponents, bucketIndex);
            this.bucketIndex = bucketIndex;
            // 格内数字由屏幕画（理由同外层那处），原版那份关掉。
            setHideAmount(true);
        }

        @Override
        public ItemStack safeInsert(ItemStack stack) {
            return safeInsert(stack, stack.getCount());
        }

        /**
         * 往桶里放：直接落到聚合库存的插入路径。
         *
         * <p>返回值是「没能放进去的那部分」（原版契约），调用方拿它决定还多少给玩家。所以这里绝不能像默认实现
         * 那样「先按手上扣、再把容器写丢弃」——那就是物品凭空消失。</p>
         *
         * <p>源槽自己会不会成为目的地，不在这里管：那条由 {@code quickMoveSource} 在筛选阶段就排除了，比在这里
         * 启发式地猜「入参是不是本格报出的那份」可靠得多（后者在玩家侧先吸走一截后就会失效）。</p>
         */
        @Override
        public ItemStack safeInsert(ItemStack stack, int amount) {
            if (stack.isEmpty() || amount <= 0) {
                return stack;
            }
            var toInsert = stack.copyWithCount(Math.min(amount, stack.getCount()));
            var leftOver = moduleComponents.insertItem(this.bucketIndex, toInsert, false);
            int moved = toInsert.getCount() - leftOver.getCount();
            if (moved <= 0) {
                return stack;
            }
            setChanged();
            return stack.copyWithCount(stack.getCount() - moved);
        }

        /**
         * 对账式写入。
         *
         * <p>原版这几条路径（搬运后的回写、拖拽分发、取空后的清格）都以「这一格现在应该长这样」的口吻写回来，
         * 而它们眼里的这一格就是 {@code getStackInSlot} 报出去的那个<b>封顶值</b>——所以差值也在同一个域里算，
         * 得到的就是该动多少件底料。</p>
         *
         * <p><b>为何不能忽略写入</b>：从桶 shift 点击时，物品进的是玩家槽（那是原版槽、真的放下了），而桶这一
         * 侧唯一的扣量钩子就是这次回写——忽略它等于凭空复制。三种情形都对得上：整批搬走（写成空 ⇒ 差值 = 减去
         * 封顶值）、只搬走一部分（写成剩下那几件 ⇒ 差值 = 搬走的量）、整格原样写回（差值 = 0）。</p>
         */
        @Override
        public void set(ItemStack stack) {
            if (getMenu().getPlayer().level().isClientSide()) {
                // 客户端的槽内容来自同步包，底下没有底料：这里只把镜像改成同步过来的那一份
                // （AppEngSlot#set 原来的落点就是它）。不这么分，客户端会照着下面的对帐逻辑去改它那份
                // 方块实体副本，而镜像反而不更新。
                moduleComponents.setItemDirect(this.bucketIndex, stack);
                setChanged();
                return;
            }
            var current = moduleComponents.kindAt(this.bucketIndex);
            int currentCount = current == null ? 0 : Math.min(current.count(), MAX_REPORTED_COUNT);
            int desired = stack.isEmpty() ? 0 : stack.getCount();
            if (!stack.isEmpty() && current != null && !ItemStack.isSameItemSameComponents(current.stack(), stack)) {
                // 原版不会把另一种组件写进同一格（同种的叠放、空的另放），真遇到了只当「不动」：
                // 照差值硬塞会把两种组件搅进同一格，而这一格只能是一种组件的桶。
                setChanged();
                return;
            }
            int delta = desired - currentCount;
            if (delta < 0) {
                moduleComponents.removeKind(this.bucketIndex, -delta);
            } else if (delta > 0) {
                // 原版的放格/拖拽若走 set 而不走 safeInsert，这一支就是常态（放一件就 +1）。装得下多少算多少，
                // 装不下的退回玩家——宁可退回，也不要静默丢件。客户端不该走到这里（它会造出幽灵掉落物）。
                var leftOver = moduleComponents.insertItem(this.bucketIndex, stack.copyWithCount(delta), false);
                var player = getMenu().getPlayer();
                if (!leftOver.isEmpty() && !player.level().isClientSide()) {
                    if (!player.getInventory().add(leftOver)) {
                        player.drop(leftOver, false);
                    }
                }
            }
            // 差值为 0 也要通知：客户端上那一格的内容可能变过。
            setChanged();
        }
    }

    /** 供屏幕显示状态用；组件槽见 {@link #moduleComponents()}，标题行图标见 {@link #moduleTitles()}。 */
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
        // 条数变了，但去抖条件只看 rowOffset 与账目快照，看不出这次变化——不显式作废，syncFromHost 会跳过
        // fillWindow，新露出的那几条带就只有底图没内容（同时客户端按 bandMark 把它们的槽隐藏，表现为「多出来
        // 的行是空格子、还装不进东西」）。置空快照 = 按本类首拍的老惯例，强制下一次 syncFromHost 真填一次。
        this.lastRefreshedStats = null;
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
            var tier = ModularCellTiers.byId(wanted);
            if (tier == null || (this.tierMask & StructureStats.tierBit(tier)) == 0) {
                // 没这一档就不认；这也顺手挡住了「客户端乱报一个 id」的情形。
                return;
            }
        }
        this.selectedTier = this.selectedTier.equals(wanted) ? "" : wanted;
    }

    /**
     * 正在被搬运的那个槽（{@link #quickMoveStack} 期间有效，其余时候为 {@code null}）。
     *
     * <p>AE2 挑搬运目的地时<b>不排除源槽</b>，而源桶恰好是可放的（该档还有名额）。玩家背包收不下时，兜底就会
     * 轮到源槽自己——对桶而言那是实打实的错账：它先把这一格报出的数量插回底料，上层再拿「剩下的」回写这一格
     * （见 {@code ComponentBucketSlot#set}），同一批件数会被扣两遍。把栈搬进它自己本来就无意义，所以显式禁掉。</p>
     *
     * <p><b>实际上这条是冗余的</b>：上游的 {@code AEBaseMenu#isValidQuickMoveDestination} 里已有
     * {@code isPlayerSideSlot(candidateSlot) != fromPlayerSide}，同侧槽本来就进不了目标集合，而组件槽都是非玩家侧
     * （{@code EnergyModuleSlotSemantics} 注册时给的 {@code false}）——所以源桶从来不会被选中。留着它是因为这个
     * 结论依赖上游那一行；哪天那一行放宽了，这里不至于跟着错帐。</p>
     */
    @Nullable
    private Slot quickMoveSource;

    /**
     * 搬运的主入口：借它把「被点的槽」记下来，供 {@link #isValidQuickMoveDestination} 排除。
     *
     * <p>其余行为（叠放、空位优先、剩余量回写）仍留在上游那一份里，不重写。</p>
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        var previous = this.quickMoveSource;
        this.quickMoveSource = index >= 0 && index < this.slots.size() ? this.slots.get(index) : null;
        try {
            return super.quickMoveStack(player, index);
        } finally {
            // 搬运动里可能嵌套调用（上游就是循环调），所以按栈恢复而不是清空。
            this.quickMoveSource = previous;
        }
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
        if (candidateSlot == this.quickMoveSource) {
            // 源槽不当自己的目的地（理由见 quickMoveSource；上游本来也排除同侧槽，这里是显式兜底）。
            return false;
        }
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
        int band = bandOfSemantic(semantic);
        if (band < 0) {
            // 不是组件槽（例如玩家背包、标题行的图标槽）：不归这条规则管。
            return true;
        }
        var tier = bandTierAt(band);
        return tier != null && tier.id().equals(this.selectedTier);
    }

    /** 这个语义在第几条带上；不是本屏的组件槽给 -1。 */
    private static int bandOfSemantic(SlotSemantic semantic) {
        for (int band = 0; band < MAX_ROWS; band++) {
            if (semantic == EnergyModuleSlotSemantics.componentSemantic(band)) {
                return band;
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

        // 行表依赖内容（一行 12 格，一档装几种组件就要几行），而 StructureStats 已经把各块的组件增益算进
        // 账目，组件一变它就变——所以这份失效判断仍然够用，不必另加一条「组件内容」的比对。
        if (this.rowOffset != this.lastRefreshedOffset || !stats.equals(this.lastRefreshedStats)) {
            fillWindow(rows);
            this.lastRefreshedOffset = this.rowOffset;
            this.lastRefreshedStats = stats;
        }
    }

    /**
     * 按窗口顶端把 {@link #visibleRows} 条带填上：行表里该条带是哪一行，以及它属于哪一档。
     *
     * <p>同时把 {@link #kindsByTier} 重建一次——组件槽的读路径（144 个格）全靠它，不能每次问都扫一遍结构。
     * 然后把行号与种类数快照拼出来给客户端排行表。</p>
     */
    private void fillWindow(List<TerminalRows.Row> rows) {
        var layout = new StringBuilder();
        for (int band = 0; band < MAX_ROWS; band++) {
            if (band > 0) {
                layout.append(',');
            }
            int rowIndex = this.rowOffset + band;
            var row = rowIndex >= 0 && rowIndex < rows.size() ? rows.get(rowIndex) : null;
            // 超出可见条数的带不填内容：屏幕上根本不显示这几行，填进去的东西照样能被快速移动选中
            // （服务端筛选只看槽位能不能放，不看屏幕藏没藏），玩家会看到组件「凭空消失」。
            this.bandRows[band] = band < this.visibleRows ? row : null;
            var effective = this.bandRows[band];
            if (effective == null) {
                layout.append('-');
            } else {
                // 行号第一段就是「标题还是组件」（T/S），第二段是哪一档；屏幕据此选带图与标题文字。
                layout.append(effective.title() ? "T:" : "S:").append(effective.tier().id());
            }
        }
        // 聚合要覆盖整张行表而不只是窗口：标题行的「已装配 a / b」得拿得出任何一档的数，哪怕它此刻不在窗口里。
        this.kindsByTier.clear();
        for (var row : rows) {
            if (!row.title()) {
                this.kindsByTier.computeIfAbsent(row.tier().id(), id -> aggregateKinds(row.tier()));
            }
        }
        this.moduleTitles.refresh(this.bandRows);
        this.structureSnapshot = buildStructureSnapshot();
        this.kindSnapshot = buildKindSnapshot();
        this.assemblySnapshot = buildAssemblySnapshot();
        this.slotCountSnapshot = buildSlotCountSnapshot();
    }

    /**
     * 该档已装组件的聚合：每种一个条目（代表栈 + 件数）。
     *
     * <p>顺序必须稳：按组件的量级（{@link StorageComponents#magnitudeOf}）升序，同量级再按注册名——否则格子里的
     * 东西会随结构遍历顺序跳，玩家看到的就是「每次开屏都换个位置」。</p>
     */
    private List<ComponentKind> aggregateKinds(EnergyCellTier tier) {
        var counts = new LinkedHashMap<Item, Integer>();
        var representatives = new LinkedHashMap<Item, ItemStack>();
        for (var module : this.host.structureModules()) {
            if (module.tier() != tier) {
                continue;
            }
            var inventory = module.getInternalInventory();
            for (int slot = 0; slot < inventory.size(); slot++) {
                var stack = inventory.getStackInSlot(slot);
                if (stack.isEmpty()) {
                    continue;
                }
                var item = stack.getItem();
                counts.merge(item, 1, Integer::sum);
                representatives.putIfAbsent(item, stack.copyWithCount(1));
            }
        }
        var list = new ArrayList<ComponentKind>(counts.size());
        for (var entry : counts.entrySet()) {
            list.add(new ComponentKind(representatives.get(entry.getKey()), entry.getValue()));
        }
        list.sort(Comparator
                .comparingLong((ComponentKind kind) -> StorageComponents.magnitudeOf(kind.stack()))
                .thenComparing(kind -> kind.stack().getItem().toString()));
        return list;
    }

    /** 每档的种类数，按固定位号顺序拼给客户端排行表用（两边同一张位号表）。 */
    private String buildKindSnapshot() {
        var tiers = ModularCellTiers.all();
        var snapshot = new StringBuilder();
        for (int index = 0; index < tiers.size(); index++) {
            if (index > 0) {
                snapshot.append(',');
            }
            var kinds = this.kindsByTier.get(tiers.get(index).id());
            snapshot.append(kinds == null ? 0 : kinds.size());
        }
        return snapshot.toString();
    }

    /**
     * 每个组件格的件数，按下标顺序拼（{@code 带 × 12 + 列}）。屏幕画格内数字用。
     */
    private String buildSlotCountSnapshot() {
        var snapshot = new StringBuilder();
        for (int band = 0; band < MAX_ROWS; band++) {
            var row = this.bandRows[band];
            var kinds = row == null || row.title() ? null : this.kindsByTier.get(row.tier().id());
            for (int column = 0; column < SLOTS_PER_ROW; column++) {
                if (band > 0 || column > 0) {
                    snapshot.append(',');
                }
                int count = 0;
                if (kinds != null) {
                    int index = row.firstKind() + column;
                    if (index >= 0 && index < kinds.size()) {
                        count = kinds.get(index).count();
                    }
                }
                snapshot.append(count);
            }
        }
        return snapshot.toString();
    }

    /** 每档已装件数，按固定位号顺序拼给客户端（标题行的「已装配 a / b」用它）。 */
    private String buildAssemblySnapshot() {
        var tiers = ModularCellTiers.all();
        var snapshot = new StringBuilder();
        for (int index = 0; index < tiers.size(); index++) {
            if (index > 0) {
                snapshot.append(',');
            }
            var kinds = this.kindsByTier.get(tiers.get(index).id());
            if (kinds == null) {
                snapshot.append(0);
                continue;
            }
            int used = 0;
            for (var kind : kinds) {
                used += kind.count();
            }
            snapshot.append(used);
        }
        return snapshot.toString();
    }

    /** 第 {@code band} 条带属于哪一档；空带给 {@code null}。 */
    @Nullable
    private EnergyCellTier bandTierAt(int band) {
        var row = band >= 0 && band < MAX_ROWS ? this.bandRows[band] : null;
        return row == null ? null : row.tier();
    }

    /** 该档的装配名额与已装件数：{@code [名额, 已装]}。名额 = 该档模块数 × 该档每块槽位数。 */
    private int[] tierUsage(EnergyCellTier tier) {
        int modules = 0;
        int used = 0;
        for (var module : this.host.structureModules()) {
            if (module.tier() != tier) {
                continue;
            }
            modules++;
            var inventory = module.getInternalInventory();
            for (int slot = 0; slot < inventory.size(); slot++) {
                if (!inventory.getStackInSlot(slot).isEmpty()) {
                    used++;
                }
            }
        }
        return new int[] { modules * tier.componentSlots(), used };
    }

    /**
     * 把若干个同种组件摊到该档各模块的空槽上（放入路径的唯一落点）。
     *
     * <p><b>优先填「当前组件数最少」的那一块</b>，于是同种组件会被摊开而不是堆在前几块上——这正是需求里的「均摊」。
     * 容量公式按块计数量加成（{@code b × C_cap}），摊开与堆在一起的账不同，所以这个次序是数值口径的一部分，
     * 不是实现细节，不能随手改。</p>
     */
    private void installInto(EnergyCellTier tier, ItemStack one, int count) {
        var modules = new ArrayList<EnergyCellModuleBlockEntity>();
        for (var module : this.host.structureModules()) {
            if (module.tier() == tier) {
                modules.add(module);
            }
        }
        for (int placed = 0; placed < count; placed++) {
            EnergyCellModuleBlockEntity target = null;
            int fewest = Integer.MAX_VALUE;
            for (var module : modules) {
                var inventory = module.getInternalInventory();
                int filled = 0;
                boolean free = false;
                for (int slot = 0; slot < inventory.size(); slot++) {
                    if (inventory.getStackInSlot(slot).isEmpty()) {
                        free = true;
                    } else {
                        filled++;
                    }
                }
                if (free && filled < fewest) {
                    fewest = filled;
                    target = module;
                }
            }
            if (target == null) {
                // 名额算错了也不会多塞：没空槽就停手，剩下的交给调用方当「放不进去」。
                return;
            }
            var inventory = target.getInternalInventory();
            for (int slot = 0; slot < inventory.size(); slot++) {
                if (inventory.getStackInSlot(slot).isEmpty()) {
                    inventory.setItemDirect(slot, one.copy());
                    break;
                }
            }
        }
    }

    /** 从该档取出若干个该种组件（按结构顺序取，取完为止）。 */
    private void uninstallFrom(EnergyCellTier tier, Item item, int count) {
        int removed = 0;
        for (var module : this.host.structureModules()) {
            if (removed >= count) {
                break;
            }
            if (module.tier() != tier) {
                continue;
            }
            var inventory = module.getInternalInventory();
            for (int slot = 0; slot < inventory.size() && removed < count; slot++) {
                var stack = inventory.getStackInSlot(slot);
                if (!stack.isEmpty() && stack.getItem() == item) {
                    inventory.setItemDirect(slot, ItemStack.EMPTY);
                    removed++;
                }
            }
        }
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
            // 一个等级一个字符（a + 固定位号）：两端按同一张位号表配对。位号不依赖注册顺序，所以本模组三档
            // 仍是 a/b/c。
            snapshot.append(tier == null ? (char) 63 : (char) (97 + ModularCellTiers.indexOf(tier)));
        }
        return snapshot.toString();
    }

    /**
     * 组件槽用的库存：按「档 × 组件类型」聚合的视图。
     *
     * <p>底层没有第二份数据：读的时候扫一遍该档各模块的真实组件槽，把同种组件并成一格（件数是它们的和）；
     * 写的时候不动那一格的「位置」，而是把组件摊到该档各模块的空槽上（{@link #installInto}）——所以玩家放进
     * 哪一格不影响结果，一格只是「一种组件的一个桶」。</p>
     *
     * <p>表示栈的件数按 {@link EnergyModuleSlotSemantics#MAX_REPORTED_COUNT} 封顶：真实件数常常远超一叠，
     * 而栈本身只是个桶（一格 = 一种组件），给它个数只为搬运量服务；显示值由屏幕画。</p>
     */
    private final class ModuleComponentInventory implements InternalInventory {

        private final ItemStack[] clientMirror = new ItemStack[MAX_SLOT_COUNT];

        private ModuleComponentInventory() {
            Arrays.fill(this.clientMirror, ItemStack.EMPTY);
        }

        @Override
        public int size() {
            return MAX_SLOT_COUNT;
        }

        /**
         * 从这一格代表的桶里抽走若干件（对帐写入用，见 {@code ComponentBucketSlot#set}）。
         */
        private void removeKind(int slotIndex, int count) {
            var kind = kindAt(slotIndex);
            var row = rowAt(slotIndex);
            if (kind == null || row == null) {
                return;
            }
            uninstallFrom(row.tier(), kind.stack().getItem(), count);
        }

        /** 该格对应的聚合条目；客户端、空带、标题行、或该格还没用到时给 {@code null}。 */
        @Nullable
        private ComponentKind kindAt(int slot) {
            var row = rowAt(slot);
            if (row == null || row.title()) {
                return null;
            }
            var kinds = kindsByTier.get(row.tier().id());
            if (kinds == null) {
                return null;
            }
            int index = row.firstKind() + columnAt(slot);
            return index >= 0 && index < kinds.size() ? kinds.get(index) : null;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            if (slot < 0 || slot >= MAX_SLOT_COUNT) {
                return ItemStack.EMPTY;
            }
            if (!isServerSide()) {
                return this.clientMirror[slot];
            }
            var kind = kindAt(slot);
            if (kind == null) {
                return ItemStack.EMPTY;
            }
            // 件数要报出来，原版才知道「这一格有几个」（见 MAX_REPORTED_COUNT）；超过上限的部分由屏幕画。
            return kind.stack().copyWithCount(Math.min(kind.count(), MAX_REPORTED_COUNT));
        }

        @Override
        public void setItemDirect(int slot, ItemStack stack) {
            // 镜像只在客户端有意义（服务端读路径不问它），服务端写进来只会白占一格引用。
            if (!isServerSide() && slot >= 0 && slot < MAX_SLOT_COUNT) {
                this.clientMirror[slot] = stack;
            }
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            if (!StorageComponents.isComponent(stack)) {
                return false;
            }
            var tier = bandTierAt(slot / SLOTS_PER_ROW);
            if (tier == null) {
                return false;
            }
            // 一次只扫一遍结构：这个方法会被 144 个槽逐个问，扫两遍就是白白翻倍。
            var usage = tierUsage(tier);
            return usage[1] < usage[0];
        }

        @Override
        public int getSlotLimit(int slot) {
            // 一格能放多少由「该档还剩多少名额」封顶，而那名额随时在变。这里报一个不限额，真正拦在 insertItem 里
            // 按当时的名额算；报一个固定值会让 UI 把多余的量默默丢掉，玩家看到的是「放了却少了」。
            return Integer.MAX_VALUE;
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (stack.isEmpty() || !StorageComponents.isComponent(stack)) {
                return stack;
            }
            var row = rowAt(slot);
            if (row == null || row.title()) {
                return stack;
            }
            var usage = tierUsage(row.tier());
            int room = usage[0] - usage[1];
            if (room <= 0) {
                return stack;
            }
            int count = Math.min(stack.getCount(), room);
            if (!simulate) {
                installInto(row.tier(), stack.copyWithCount(1), count);
            }
            return count >= stack.getCount() ? ItemStack.EMPTY : stack.copyWithCount(stack.getCount() - count);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            var kind = kindAt(slot);
            if (kind == null || amount <= 0) {
                return ItemStack.EMPTY;
            }
            int count = Math.min(amount, kind.count());
            if (count <= 0) {
                return ItemStack.EMPTY;
            }
            if (!simulate) {
                uninstallFrom(rowAt(slot).tier(), kind.stack().getItem(), count);
            }
            return kind.stack().copyWithCount(count);
        }

        @Nullable
        private TerminalRows.Row rowAt(int slot) {
            if (slot < 0 || slot >= MAX_SLOT_COUNT) {
                return null;
            }
            return bandRows[slot / SLOTS_PER_ROW];
        }

        private int columnAt(int slot) {
            return slot % SLOTS_PER_ROW;
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

        /** 行表定型后重填：每条带是不是标题行、是哪一档。 */
        private void refresh(TerminalRows.Row[] rows) {
            for (int band = 0; band < MAX_ROWS; band++) {
                var row = rows[band];
                this.tiers[band] = row != null && row.title() ? row.tier() : null;
            }
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
            if (tier == null) {
                return ItemStack.EMPTY;
            }
            // 按等级查方块也可能查不到（那一档没有注册方块套装）：与上面的 null 一样当作「这里没东西」。
            var block = AEModularCellsRegistries.moduleBlock(tier);
            return block == null ? ItemStack.EMPTY : new ItemStack(block.asItem());
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
