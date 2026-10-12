package io.github.lounode.ae2modularcells.common.block.entity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.client.model.data.ModelData;

import appeng.api.config.Actionable;
import appeng.api.networking.IGridNode;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.ticking.TickingRequest;
import appeng.api.inventories.InternalInventory;
import appeng.blockentity.grid.AENetworkedPoweredBlockEntity;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;

import io.github.lounode.ae2modularcells.common.EnergyCellTier;
import io.github.lounode.ae2modularcells.common.ModularCellConstants;
import io.github.lounode.ae2modularcells.common.StorageComponents;
import io.github.lounode.ae2modularcells.common.block.EnergyCellModuleBlock;
import io.github.lounode.ae2modularcells.common.block.StructurePart;
import io.github.lounode.ae2modularcells.common.block.entity.MonitorBlockEntity;
import io.github.lounode.ae2modularcells.common.StorageComponentTier;
import io.github.lounode.ae2modularcells.common.StorageComponents;
import io.github.lounode.ae2modularcells.common.energy.InsertOnlyEnergyStorage;
import io.github.lounode.ae2modularcells.common.inventory.StructureComponentItemHandler;
import io.github.lounode.ae2modularcells.common.logic.CellMath;
import io.github.lounode.ae2modularcells.common.logic.MultiblockScanner;
import io.github.lounode.ae2modularcells.common.logic.StructureStats;
import io.github.lounode.ae2modularcells.common.logic.TerminalRows;

/**
 * 能源模块方块实体：组件槽、结构引用、连接状态三件事都在这里。
 *
 * <p>角色是动态的：<b>坐标最小的那一块</b>是整段结构的核心，它持有结构范围与整段账目；其余块只记「我的
 * 核心在哪」。未成型的单块自己就是核心，账目就是它自己那一块。</p>
 *
 * <p>三条纪律写在这里免得后来者踩：</p>
 * <ul>
 * <li><b>不注册 ticker</b>：这个类没有 tick 方法，结构检测只在放置/破坏/组件变化时触发。</li>
 * <li><b>能量池只在核心</b>：储能存在核心身上；非核心从任何面收到 FE，同一 tick 转交给核心的池子
 * （{@link #funnelPowerIntoStorage}），自己不存——否则一个电池就变成了 4096 个小电池。</li>
 * <li><b>连接状态是推导值</b>：从世界方块状态现算（{@link #getConnections()}），不持久化、不做增量维护。</li>
 * </ul>
 */
public class EnergyCellModuleBlockEntity extends AENetworkedPoweredBlockEntity
        implements InternalInventoryHost, IGridTickable {

    /** 组件槽里放的东西（§2.2）。尺寸按等级 1 / 2 / 3。 */
    private final AppEngInternalInventory components;

    /** 等级在构造时钉住：它由方块决定，方块实体一旦建成就不会再变。 */
    private final EnergyCellTier tier;

    /**
     * 我所属结构的核心位置。<b>非核心才写</b>：核心用 {@link #structureOrigin} 表明身份，两者都为 null
     * 表示这一块是独立元件。
     */
    @Nullable
    private BlockPos corePos;

    /** 我这一段的立方体最小角，也就是核心自己的位置。<b>只在核心上非 null</b>。 */
    @Nullable
    private BlockPos structureOrigin;

    /** 本块持有的账目：核心上是整段，非核心上是它自己那块（对外读请走 {@link #stats()}）。 */
    private StructureStats ownStats;

    /** 行表缓存：只有核心会填，失效条件是账目快照变了（见 {@link #rows()}）。 */
    @Nullable
    private List<TerminalRows.Row> rowCache;
    @Nullable
    private StructureStats rowCacheStats;

    /**
     * 整段成员的缓存（与 {@link #rowCache} 同寿命）。
     *
     * <p>为什么需要：整段组件槽视图（{@link StructureComponentItemHandler}）会被导出总线在**同一次推送里**
     * 反复问「第几个槽在哪」，而成员列表要逐块 {@code getBlockEntity}（上界 4096）。成员表与行表一样是
     * 结构级稳定的量，所以在核心上缓一份、结构一变就作废，代价从「每次推送扫一遍」降到「每次结构变更扫一遍」。</p>
     */
    @Nullable
    private List<EnergyCellModuleBlockEntity> memberCache;

    /** 逐块汇总时复用的暂存数组（容量侧：存储组件值），见 {@link #componentCapacitiesScratch()}。 */
    private final long[] capacityScratch = new long[ModularCellConstants.maxComponentSlots()];
    /** 逐块汇总时复用的暂存数组（速率侧：能量存储组件值），见 {@link #energyComponentsScratch()}。 */
    private final long[] energyScratch = new long[ModularCellConstants.maxComponentSlots()];

    /**
     * 只进不出的 FE 面（懒建）。能力注册拿到的是它，不是 AE2 自己那份适配器。
     *
     * @see io.github.lounode.ae2modularcells.common.energy.InsertOnlyEnergyStorage
     */
    @Nullable
    private IEnergyStorage feInput;

    /**
     * 本 tick 已经收进来多少 AE（<b>入力额度</b>用），配 {@link #inputSpentTick} 判断是否过期。
     *
     * <p>它是「严格本 tick」的计数：限流看的是这个数，所以不能被显示用的峰值/衰减影响。</p>
     */
    private long inputSpentThisTick;
    private long inputSpentTick = Long.MIN_VALUE;

    /** 显示用的峰值：窗口内每 tick 收得最多的一次；配 {@link #lastInputTick} 做线性衰减。 */
    private long inputRatePeak;

    /**
     * 显示窗口与额度重置的时长（tick）。
     *
     * <p>界面上那根「输入速率」条读的是一个脉冲式的量（本 tick 收了多少）：没有 ticker，下一 tick 没能量进来
     * 就回 0了，而界面隔一会儿才采样一次——采样点大多落在没输入的 tick 上，看起来就是「条子不动」。
     * 所以给实测值一个窗口：<b>窗口内峰值保持 + 线性衰减</b>，持续供能时显示满值，停供后 0.5 秒内线性回落。
     * 硬保持到点再归零会让数字一跳一跳，衰减看上去更像仪表。</p>
     */
    private static final int INPUT_RATE_TTL_TICKS = 10;

    /** 最近一次真实（非模拟）插入记下的时间：上面那个「本 tick」是按游戏时间懒重置的，不需要 ticker。 */
    private long lastInputTick = Long.MIN_VALUE;

    /**
     * 正在从 NBT 读档。
     *
     * <p>读槽位走的是 {@code setItemDirect}，而它会把「组件变了」当成一次结构变更——一段 4096 块的
     * 结构读档时就是上万次扫描。读档期间置位，读完就落地：读档之后的第一次真实触发（放置/破坏/组件变化）
     * 会自然把该算的算出来。</p>
     */
    private boolean loading;

    /** 六向的固定数组：{@link Direction#values()} 每次调用都会新建一个数组，而结构汇总与连接判定都在逐块循环里。 */
    private static final Direction[] DIRECTIONS = Direction.values();

    public EnergyCellModuleBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        // 把自己登记成网格的可 tick 服务：AE2 只会 tick 登记过的节点服务，不是实现了接口就算数。
        // 用它的原因：本模组不给方块实体注册 ticker（红线），周期工作走网格这一条路。
        getMainNode().addService(IGridTickable.class, this);
        this.tier = requireTier(state);
        // host 不能省：AppEngInternalInventory 只有在有 host 时才会调 saveChangedInventory，
        // 而「方块实体被打上脏标记」就是这么来的。不传 host 时，组件槽里的改动会不会被存档取决于
        // rebuildStructure() 是否碰巧改了账目——改了才 setChanged，没改就静默丢掉（退游戏后组件消失）。
        // 组件槽一个槽只放一件：容量折扣按「插了几件」算，堆叠会让公式失去意义。
        this.components = new AppEngInternalInventory(this, tier.componentSlots(), 1);
        this.ownStats = StructureStats.single(tier, new long[0], new long[0]);
        // 公开能量存储：网格的 IEnergyService 会把它计入全网格的能量平衡（IAEPowerStorage）。
        setInternalPublicPowerStorage(true);
    }

    // ---- 组件槽：库存事件 ----

    /** 组件变了就是一次结构变更（三个触发时机之一），同时把方块实体标脏。 */
    @Override
    public void onChangeInventory(AppEngInternalInventory inv, int slot) {
        if (!this.loading) {
            rebuildStructure();
        }
    }

    /** 库存的任何改动都要落盘：这一条以前是暗的（靠重建结构顺带 setChanged），现在钉死在接口上。 */
    @Override
    public void saveChangedInventory(AppEngInternalInventory inv) {
        setChanged();
    }

    @Override
    public boolean isClientSide() {
        return this.level != null && this.level.isClientSide();
    }

    // ---- 能量池 ----

    /**
     * 整段结构的容量上限（AE）。
     *
     * <p>上限是算出来的而不是存下来的：它随组件与结构变化，存一份就会与 {@link #stats()} 脱节。</p>
     */
    @Override
    public double getInternalMaxPower() {
        return (double) stats().totalCapacity();
    }

    /**
     * 外部（FE）送进来的能量往哪去。<b>非核心一律转交核心</b>：能量只存在核心那一个池子里。
     *
     * <p>这是「任何模块的面都能输入 FE」的落点——每个模块都暴露能力，但收到的能量都写成同一个池子。
     * 核心不在场时退回写自己那份，不至于把送进来的能量直接丢掉。</p>
     *
     * <p><b>入力额度</b>：整段每 tick 能收的量就是 {@code T_in = min(T_v × f_in, Σ V_in_max_j)}。以前这里不设限，
     * 只要池子没满就照收——于是 FE（以及任何走这条路的外部注入）能直接越过那个上限，界面上却还报一个被夹死的值。
     * 现在超出额度的部分原样退回，上限对这条路是真的。</p>
     *
     * <p><b>这条路盖住了谁</b>：外部电源（FE，经 AE2 的 {@code injectExternalPower}）与外力均落在这里。
     * 但 <b>AE 网格的注入不经过本方法</b>——AE2 的 {@code EnergyService} 直接调 {@code injectAEPower}，
     * 而那个方法在 {@code AEBasePoweredBlockEntity} 上是 {@code final}，子类无法限速。
     * 要让网格侧也受 {@code T_in} 约束，得不再继承 AE 的 powered 基类、自己实现 {@code IAEPowerStorage}。</p>
     */
    @Override
    protected double funnelPowerIntoStorage(double power, Actionable mode) {
        var core = resolveCore();
        if (core != null && core != this) {
            return core.funnelPowerIntoStorage(power, mode);
        }
        // 输入速率对这条路是真限制（见上方 javadoc：AE 网格那条走的是上游 final 的 injectAEPower，盖不到）。
        double allowed = Math.max(0.0, Math.min(power, remainingInputBudget()));
        double leftover = super.funnelPowerIntoStorage(allowed, mode);
        if (mode == Actionable.MODULATE) {
            recordInput(allowed - leftover, mode);
        }
        // 超出额度的部分也算「没收下」，一并还给调用方（FE 侧会把它当成拒收量报回上游）。
        return leftover + (power - allowed);
    }

    /**
     * 本 tick 还能收多少 AE：{@code T_in} 减去本 tick 已收。
     *
     * <p>没有 ticker：额度按「游戏时间对一下是不是同一 tick」现算——{@link #recordInput} 写的就是本 tick
     * 已收量，两边共用同一个计数，所以界面上的「输入速率」永远不会超过 {@code T_in}。</p>
     */
    private double remainingInputBudget() {
        long limit = stats().totalInputRate();
        if (this.level == null || this.inputSpentTick != this.level.getGameTime()) {
            return Math.max(0L, limit);
        }
        return Math.max(0L, limit - this.inputSpentThisTick);
    }

    /**
     * 记一笔「本 tick 收进来多少 AE」。
     *
     * <p>界面上那行「输入速率」的数据源。没有 ticker：读的时候拿游戏时间对一下，过期（超过
     * {@link #INPUT_RATE_TTL_TICKS}）的就当 0。</p>
     *
     * <p><b>只记真动作</b>：AE2 的 {@code ForgeEnergyAdapter} 会先拿 {@code Actionable.SIMULATE} 探一下
     * 能收多少（试探不入账），那一次也得走 {@code funnelPowerIntoStorage}。把模拟也记进去会报出虚高值
     * ——「看着收了一大笔，其实一滴没进」。</p>
     */
    private void recordInput(double ae, Actionable mode) {
        if (mode == Actionable.SIMULATE) {
            return;
        }
        long amount = ae > 0.0 ? Math.round(ae) : 0L;
        if (amount <= 0L || this.level == null) {
            return;
        }
        long now = this.level.getGameTime();
        // 额度计数：严格按 tick 累加。
        if (now == this.inputSpentTick) {
            this.inputSpentThisTick += amount;
        } else {
            this.inputSpentTick = now;
            this.inputSpentThisTick = amount;
        }
        // 显示峰值：上一次输入在半秒之前就开新窗口，否则取窗口内最大值。
        if (this.lastInputTick == Long.MIN_VALUE || now - this.lastInputTick >= INPUT_RATE_TTL_TICKS) {
            this.inputRatePeak = amount;
        } else {
            this.inputRatePeak = Math.max(this.inputRatePeak, amount);
        }
        this.lastInputTick = now;
    }

    /**
     * 本 tick 收进来的 AE（输入速率）。
     *
     * <p>不是速率上限，是**实测值**：显示口径是「窗口内峰值 + 线性衰减」——持续供能时保持满值，
     * 停供后 0.5 秒内线性回落（这正是界面上那条输入进度条要的效果：没人供电时它自己降下去）。</p>
     */
    public long inputRate() {
        var core = resolveCore();
        if (core != null && core != this) {
            return core.inputRate();
        }
        if (this.level == null || this.lastInputTick == Long.MIN_VALUE) {
            return 0L;
        }
        long age = this.level.getGameTime() - this.lastInputTick;
        if (age < 0L || age > INPUT_RATE_TTL_TICKS) {
            // 上一次真收进能量已超过一个窗口：归零（负 age 防时钟回拨/跨维度）。
            return 0L;
        }
        // age <= 1 视为满值：写入（recordInput）与读取（菜单采样）不一定落在同一个 tick，
        // 晚一拍就衰减会让持续供能的读数在 peak 与 0.9×peak 之间抖，像仪表坏了。
        double decay = age <= 1L ? 1.0D : 1.0D - (double) (age - 1L) / INPUT_RATE_TTL_TICKS;
        return Math.max(0L, Math.round(this.inputRatePeak * decay));
    }

    /**
     * 只进不出的 FE 面。
     *
     * <p>能力注册拿到的是它：接收照常（走 AE2 自己的适配器，比例跟随整合包配置），但存量与抽取一律为 0
     * ——本模组不存 FE，FE 进来当场就变成 AE（见 {@link InsertOnlyEnergyStorage}）。</p>
     */
    @Nullable
    public IEnergyStorage getFeInput(@Nullable Direction side) {
        var delegate = getEnergyStorage(side);
        if (delegate == null) {
            return null;
        }
        if (this.feInput == null) {
            this.feInput = new InsertOnlyEnergyStorage(delegate);
        }
        return this.feInput;
    }

    /** 还差多少才满；非核心问的是核心的池子（见 {@link #funnelPowerIntoStorage}）。 */
    @Override
    protected double getFunnelPowerDemand(double maxRequired) {
        var core = resolveCore();
        if (core != null && core != this) {
            return core.getFunnelPowerDemand(maxRequired);
        }
        return super.getFunnelPowerDemand(maxRequired);
    }

    /**
     * 当前储能（AE）。
     *
     * <p>不论问到哪一块，答的都是**整段结构**的那一份：能量物理上只存在核心，但界面、Jade 与物品提示
     * 都是「看着哪一块问整段」，所以这里统一向核心取。核心不在场时退回自己那份。</p>
     */
    @Override
    public double getInternalCurrentPower() {
        var core = resolveCore();
        if (core != null && core != this) {
            return core.ownStoredEnergy();
        }
        return ownStoredEnergy();
    }

    /**
     * 当前储能（AE）：追核心，界面与 Jade 都读它。
     *
     * <p>存在的原因：{@link #getInternalCurrentPower()} 是个会被继承链反复调用的入口，追核心那一步只能写在它
     * 里面；真正读自己值时需要一个不会再追一次的出口，否则就是递归。</p>
     */
    private double ownStoredEnergy() {
        return super.getInternalCurrentPower();
    }

    /**
     * 把整段结构的储能收拢到核心（第一次成型时用）。
     *
     * <p><b>读的是本机值，不是 {@link #getInternalCurrentPower()}</b>：那个访问器会追到核心，已成型结构重扫时
     * 逐块求和会变成 {@code E × N}，池子被凭空抬高 N 倍。触发很常见——成型后在结构里改一个组件就会重扫。</p>
     */
    private long localStoredEnergy() {
        double local = ownStoredEnergy();
        return local > 0.0 ? Math.round(local) : 0L;
    }

    /** 当前储能取整（AE），给界面与 Jade 用。 */
    public long storedEnergy() {
        return Math.max(0L, Math.round(getInternalCurrentPower()));
    }

    /**
     * 能量状态变了（从满变不满、或从空变不空）时告诉网格。
     *
     * <p>不发这条的后遗症是上游注释里写的那句：这个存储会「不充也不放」——网格把它当成了永远满（或永远空），
     * 于是不再向它做能量平衡。</p>
     */
    @Override
    protected void emitPowerStateEvent(appeng.api.networking.events.GridPowerStorageStateChanged.PowerEventType type) {
        getMainNode().ifPresent(grid -> grid.postEvent(
                new appeng.api.networking.events.GridPowerStorageStateChanged(this, type)));
    }

    /**
     * 网格接入面：<b>六面都允许连接</b>，但结构内部之间与结构外拿到的是不同的节点——见 {@link #getGridNode}。
     * <p>结构一变就调 {@code onGridConnectableSidesChanged()} 让父类把新的可连接面推给节点。</p>
     */
    @Override
    public java.util.Set<Direction> getGridConnectableSides(appeng.api.orientation.BlockOrientation orientation) {
        // 六面全开：结构里每一块都可以是玩家接线的那一块（实测：「成型的结构无法从任何一个模块连接」）。
        // 注意这只是「允许连」，真正给出哪个节点由 {@link #getGridNode} 决定——结构内部之间会被那里挡掉。
        return java.util.EnumSet.allOf(Direction.class);
    }

    /**
     * 网格接入面的节点来源。两个口径合在一起：
     *
     * <p>① 面朝<b>结构内部</b>（邻块也是结构件）时不透连接。否则核心节点会经成员拿到自己，AE2 建连接时会以
     * {@code Cannot connect node to itself} 断言失败（那是 IllegalArgumentException，会打断服务端网格重算）。</p>
     *
     * <p>② 面朝<b>结构外</b>（线缆等）时把自己那一段唯一的节点交出去：是核心就给自己的，是成员就给核心的。
     * 于是玩家在任何一块上接线都能连进网络，而网格里始终只有核心一个节点、一份存储账目。</p>
     */
    @Override
    public appeng.api.networking.IGridNode getGridNode(Direction direction) {
        var level = this.level;
        if (level == null) {
            return null;
        }
        var neighbour = worldPosition.relative(direction);
        if (!level.isLoaded(neighbour) || MultiblockScanner.isStructurePart(level.getBlockState(neighbour))) {
            return null;
        }
        var core = resolveCore();
        if (core != null && core != this) {
            var coreNode = core.getMainNode().getNode();
            if (coreNode != null) {
                return coreNode;
            }
        }
        return super.getGridNode(direction);
    }

    /**
     * 客户端同步走存档那份写入代码。
     *
     * <p>AE2 默认的 update tag 只发 {@code writeToStream} 的那几个字段，<b>不包含</b> {@code saveAdditional}
     * ——而 {@code corePos} / {@code structureOrigin} / 账目全写在那里。不覆写这一条，客户端的 {@code corePos}
     * 恒为 null，于是 {@code isFormed()} 恒 false（成型结构在客户端永远画未成型外观），{@code lightRatio()}
     * 也拿不到值。监控器方块实体已经这么做了（同一套写法）。</p>
     */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    /** 我是不是这一段的核心（或未成型的独立元件）。 */
    public boolean isCore() {
        return this.corePos == null;
    }

    private static EnergyCellTier requireTier(BlockState state) {
        if (state.getBlock() instanceof EnergyCellModuleBlock module) {
            return module.tier();
        }
        // 方块实体类型与方块是绑死的，走到这里说明注册表被改乱了。宁可当场炸，也不要静默按某一档算账。
        throw new IllegalStateException("能源模块方块实体挂在了非模块方块上：" + state);
    }

    public EnergyCellTier tier() {
        return this.tier;
    }

    /**
     * 组件槽对自动化（导出/输出总线、ME 接口）的可写面：<b>整段结构</b>的视图。
     *
     * <p><b>只进不出</b>，且只在成型后开口：组件槽是装上去就算账的配置位，不是库存；未成型时 GUI 打不开，
     * 此时还能被塞东西就会出现「东西进去了但玩家管不了」。门禁与 GUI 一致（{@link StorageComponents#isComponent}）。</p>
     *
     * <p>为什么是整段而不是「这一块自己的槽」：AE2 的导出/输出总线把目标钉在贴着的那一块上且长期缓存，
     * 只暴露一块会让「这块满了」变成「怎么都塞不进去」。详见 {@link StructureComponentItemHandler}。</p>
     */
    public IItemHandler componentItemHandler() {
        return new StructureComponentItemHandler(this);
    }

    /**
     * 这一块的组件槽数（1 / 2 / 3，由等级决定）。整段视图要用它摊平下标。
     */
    public int componentSlotCount() {
        return this.components.size();
    }

    /** 这一块第 {@code localSlot} 个组件槽里的东西；越界给空。 */
    public ItemStack componentStack(int localSlot) {
        return localSlot >= 0 && localSlot < this.components.size() ? this.components.getStackInSlot(localSlot)
                : ItemStack.EMPTY;
    }

    /**
     * 往这一块的第 {@code localSlot} 个组件槽插一件（自动化用）。
     *
     * <p>门禁与 GUI、与 Shift 手势一致：只收存储组件、且这一段得是成型的结构。返回剩余量（插不进就原样退回）。</p>
     */
    public ItemStack insertComponent(int localSlot, ItemStack stack, boolean simulate) {
        if (stack.isEmpty() || localSlot < 0 || localSlot >= this.components.size()) {
            return stack;
        }
        if (!isPartOfFormedStructure() || !StorageComponents.isComponent(stack)) {
            return stack;
        }
        return this.components.insertItem(localSlot, stack, simulate);
    }

    /** 这一块的某个组件槽收不收这件东西（总线会用 {@code isItemValid} 做预判）。 */
    public boolean isComponentSlotAcceptable(int localSlot, ItemStack stack) {
        if (stack.isEmpty() || localSlot < 0 || localSlot >= this.components.size()) {
            return false;
        }
        return isPartOfFormedStructure() && StorageComponents.isComponent(stack);
    }

    /**
     * 这一块属于一段<b>成型结构</b>吗。
     *
     * <p>与 {@link #isFormed()} 分开：后者要求「核心当下拿得到」（会走核心区块），用于开屏与手势；
     * 自动化这条路上，非核心块在核心区块未加载时不该因此被判定为「没成型」——它照样是自己那一段的一员，
     * 写的是本块的槽。（否则总线会时灵时不灵，而玩家看不出为什么。）</p>
     */
    private boolean isPartOfFormedStructure() {
        if (this.corePos != null) {
            return true;
        }
        return this.structureOrigin != null && this.ownStats.side() > 1;
    }

    /**
     * 组件槽交给父类保管：
     *
     * <p>{@code AEBaseInvBlockEntity} 已经用这个方法把库存的存、读、掉落、清空都包了（NBT 键是
     * {@code inv}）。自己再写一遍就是两份实现——而两份实现迟早会分叉。</p>
     *
     * <p>取名与 {@code BlockEntity#components}（数据组件表）冲突，方法名只能叫这个。</p>
     */
    @Override
    public InternalInventory getInternalInventory() {
        return this.components;
    }

    /**
     * 这一块的 GUI 该显示的账目。
     *
     * <p>核心不在场（区块未加载、或它已被拆）时退化为「单块」——显示一个偏小的数总好过整屏报错。</p>
     */
    public StructureStats stats() {
        if (this.corePos == null) {
            return this.ownStats;
        }
        var core = resolveCore();
        return core != null ? core.ownStats : StructureStats.single(tier, componentCapacitiesScratch(), energyComponentsScratch());
    }

    /**
     * 这一块所在的段是不是已经成型。
     *
     * <p>成型 = 结构成立（实心立方体、边长落在合法区间；等级可混拼，见 {@code StructureStats.tierMask}）。单块与
     * 「拼不成立方体的一堆」都不算——界面只在成型后才有东西可管（见 {@code docs/ARCHITECTURE.md} §3.2），
     * 未成型右键不开屏。</p>
     *
     * <p>判据取核心手里的结构范围，而不是另加一个方块状态属性：成型与否不换模型，这里读的就是结构检测的结果，
     * 与界面上的账目同源。</p>
     */
    public boolean isFormed() {
        var core = resolveCore();
        return core != null && core.structureOrigin != null && core.ownStats.side() > 1;
    }

    // ---- 渲染：自发光层（供客户端每帧调用）----

    /**
     * 发光强度：整段储能占整段容量的比例（0~1）。
     *
     * <p>给模块方块的自发光层用（口径：按储能百分比线性，0% 不发光 → 100% 满亮）。读的是<b>已同步的值</b>：
     * 核心的储能由 AE2 的动力方块实体走 NBT 同步，容量取自核心手里的结构账目；两项任何一项拿不到（核心不在、
     * 区块未加载、结构未成型）就返回 0——渲染路径宁可少画一层，不能因为缺数把玩家卡死。</p>
     */
    public float lightRatio() {
        var core = resolveCore();
        if (core == null) {
            return 0.0F;
        }
        long capacity = core.ownStats.totalCapacity();
        if (capacity <= 0L) {
            return 0.0F;
        }
        double ratio = core.storedEnergy() / (double) capacity;
        return (float) Math.max(0.0, Math.min(1.0, ratio));
    }

    /**
     * 某一面是否贴着同类结构件。发光层只画<b>露在外面</b>的面：贴着邻块的那一面在立方体内部，画了也看不见，
     * 白白多六倍顶点。
     */
    public boolean faceConnected(Direction direction) {
        return getConnections().contains(direction);
    }

    // ---- 自发光档位（服务端）----

    /** 档位数：{@code 0~4} 对应 0% / 25% / 50% / 75% / 100% 的发光强度（见 {@code EnergyCellModuleBlock#FILL}）。 */
    private static final int FILL_LEVELS = 4;

    /** 档位检查周期（tick）：一秒一次。方块状态变化要走网络同步，比这更频繁没有意义。 */
    private static final int FILL_CHECK_INTERVAL = 20;

    @Override
    public TickingRequest getTickingRequest(IGridNode node) {
        return new TickingRequest(FILL_CHECK_INTERVAL, FILL_CHECK_INTERVAL, false);
    }

    @Override
    public TickRateModulation tickingRequest(IGridNode node, int ticksSinceLast) {
        // 只有核心做：整段只有一个账目，比例也是整段共用；其余模块只是被写方块状态的一方。
        if (isCore()) {
            updateFillLevels();
        }
        return TickRateModulation.SAME;
    }

    /**
     * 把整段的储能比例折算成档位，写进每一块结构件的方块状态。
     *
     * <p>只写真的变了的那几块：{@code setBlock} 会发网络包，一整段 16³ 的立方体不该每秒重写一遍。
     * 这一口径与「结构检测不在渲染路径上跑」是同一个思路——贵的操作要有人把关。</p>
     */
    private void updateFillLevels() {
        var level = this.level;
        if (level == null || level.isClientSide()) {
            return;
        }
        int fill = fillLevel(lightRatio());
        if (this.structureOrigin == null || this.ownStats.side() <= 1) {
            // 未成型：只有自己这一块，也得跟单块账目一致。
            applyFill(level, this.worldPosition, fill);
            return;
        }
        var layout = new MultiblockScanner.Layout(this.structureOrigin, this.ownStats.side());
        var cursor = new BlockPos.MutableBlockPos();
        int count = layout.moduleCount();
        for (int index = 0; index < count; index++) {
            layout.modulePos(index, cursor);
            applyFill(level, cursor, fill);
        }
    }

    /** 比例 → 档位：0% 不发光，其余四舍五入到最近的档。 */
    private static int fillLevel(float ratio) {
        return Math.max(0, Math.min(FILL_LEVELS, Math.round(ratio * FILL_LEVELS)));
    }

    /** 给一块结构件写档位。监控器没有这个属性，会被 {@code hasProperty} 挡下。 */
    private static void applyFill(Level level, BlockPos pos, int fill) {
        var state = level.getBlockState(pos);
        if (!state.hasProperty(EnergyCellModuleBlock.FILL) || state.getValue(EnergyCellModuleBlock.FILL) == fill) {
            return;
        }
        // 纯视觉：不改变任何账目，所以只发客户端，不必让服务端再跑一次邻块更新。
        level.setBlock(pos, state.setValue(EnergyCellModuleBlock.FILL, fill),
                net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
    }

    // ---- 结构生命周期 ----

    /**
     * 从这一块出发重算结构。
     *
     * <p>三种结论各走一路：成立就落到整段，确定不成立就回退成独立元件，<b>判不了（邻居没加载完）就什么都不做</b>
     * ——保留上一次算出来的状态。把「看不到」当成「不成型」，一次跨区块的区块加载就能把一段好结构判没。</p>
     */
    public void rebuildStructure() {
        var level = this.level;
        if (level == null || level.isClientSide()) {
            return;
        }
        var result = MultiblockScanner.scan(level, worldPosition);
        switch (result.status()) {
            case FORMED -> {
                // 核心必须是**储能模块**（立方体最小角那块）：监控器不存能量、也没有账目，让它当核心会让整段
                // 没有能量池、终端也打不开。口径：最小角不是模块的立方体不算成型——玩家把监控器摆在最低角时，
                // 这一段退化为各自的单块（挪开监控器后再触发一次结构变更即恢复）。
                if (level.getBlockEntity(result.layout().origin()) instanceof EnergyCellModuleBlockEntity) {
                    applyStructure(result.layout());
                } else {
                    clearStructure();
                }
            }
            case NOT_FORMED -> clearStructure();
            case INDETERMINATE -> {
                // 有意留空：等下一次触发（放置 / 破坏 / 组件变化 / 玩家右键）再算。
            }
        }
    }

    /** 放置路径：从新方块出发检测。由方块调用。 */
    public static void rebuildAt(Level level, BlockPos pos) {
        if (level.isClientSide()) {
            return;
        }
        if (level.getBlockEntity(pos) instanceof EnergyCellModuleBlockEntity module) {
            module.rebuildStructure();
        }
    }

    /**
     * 破坏路径：先让被拆的那一块所属整段失效，再从六个邻居各自重算。
     *
     * <p>「先整段失效」不能省：一段 3³ 被拆掉一块之后，剩下 26 块里的任何一块单独重扫都会失败，但失败只会
     * 让它自己回退——其余 25 块还留着指向已消失结构的核心引用。所以这里拿被拆那一块手里的范围，把整段一起
     * 清掉，再由邻居们各自回到「独立元件」。</p>
     */
    public static void onModuleRemoved(Level level, BlockPos pos) {
        if (level.isClientSide()) {
            return;
        }
        if (level.getBlockEntity(pos) instanceof EnergyCellModuleBlockEntity module) {
            module.clearStructure();
        }
        var cursor = new BlockPos.MutableBlockPos();
        for (var direction : DIRECTIONS) {
            cursor.setWithOffset(pos, direction);
            rebuildAt(level, cursor);
        }
    }

    /** 把自己所参与的那一整段清成独立元件；不属于任何成型结构时只清自己。 */
    private void clearStructure() {
        var core = resolveCore();
        if (core == null) {
            becomeStandalone();
            return;
        }
        var origin = core.structureOrigin;
        int side = core.ownStats.side();
        if (origin == null || side <= 1 || core.level == null) {
            core.becomeStandalone();
            if (core != this) {
                becomeStandalone();
            }
            return;
        }
        var layout = new MultiblockScanner.Layout(origin, side);
        var cursor = new BlockPos.MutableBlockPos();
        int count = layout.moduleCount();
        for (int index = 0; index < count; index++) {
            layout.modulePos(index, cursor);
            if (core.level.getBlockEntity(cursor) instanceof EnergyCellModuleBlockEntity module) {
                module.becomeStandalone();
            } else if (core.level.getBlockEntity(cursor) instanceof MonitorBlockEntity monitor) {
                // 监控器也要抹掉核心引用：不抹的话结构解散后它还存着一个已被拆的坐标，
                // 而那个坐标会跟着存档一起落盘（下一轮它要拿这个引用读整段账目）。
                monitor.setCorePos(null);
            }
        }
        becomeStandalone();
    }

    /** 退化为独立元件：自己就是核心，账目按自己槽里的组件算。 */
    private void becomeStandalone() {
        var single = StructureStats.single(tier, componentCapacitiesScratch(), energyComponentsScratch());
        boolean wasCore = isCore();
        boolean changed = this.corePos != null || this.structureOrigin != null || !this.ownStats.equals(single);
        this.corePos = null;
        this.structureOrigin = null;
        this.ownStats = single;
        this.rowCache = null;
        this.memberCache = null;
        // 池子的上限跟着账目走；能量不搬（它本来就在核心里，而「核心」现在就是自己）。
        setInternalMaxPower((double) single.totalCapacity());
        afterStructureReferenceChanged(wasCore);
        if (changed) {
            setChanged();
        }
    }

    /**
     * 结构引用变完之后的收尾：核心身份可能变了，要让网格节点重新拿一次「暴露在哪些面」。
     *
     * <p>为什么不在身份真的变了时才调：{@code setExposedOnSides} 是幂等的，而判断「真变了」需要跟上一轮的
     * 集合比；调用次数与结构变更次数同量级，不值当为此多存一份状态。</p>
     */
    private void afterStructureReferenceChanged(boolean wasCore) {
        // 模型要重画：成型与否（以及连接位）都在模型数据里，结构一变外观就得跟上。
        // 用 AE2 的 markForClientUpdate（刷模型数据 + 方块更新包）而不是只 requestModelDataUpdate()：
        // 客户端也得拿到新的核对账目，而不是只把旧数据重画一遍。
        markForClientUpdate();
        if (wasCore != isCore()) {
            onGridConnectableSidesChanged();
        }
    }

    /**
     * 把一次成功的检测结果落到整段上。
     *
     * <p>分两趟：第一趟算总量（逐块求和，见 {@link CellMath}），第二趟写引用与账目。第一趟发现任何一块已经
     * 不在了就整轮回作废——结构正在变，等下一次触发重算更安全，也能避免写出半截账目。</p>
     */
    private void applyStructure(MultiblockScanner.Layout layout) {
        var level = this.level;
        if (level == null) {
            return;
        }
        int count = layout.moduleCount();
        var cursor = new BlockPos.MutableBlockPos();

        long capacity = 0L;
        long rate = 0L;
        long rateCap = 0L;
        long inputCap = 0L;
        // 混等级：每块按自己那一档算 D、D_v 与自己的两个上限，整段就是逐块求和（上限也是提供量之和，
        // 不是「某一档的固定值」）。整段的「代表等级」取出现过的最高档，只用于界面文案。
        var capTier = tier;
        int tierMask = 0;
        for (int index = 0; index < count; index++) {
            layout.modulePos(index, cursor);
            if (!(level.getBlockEntity(cursor) instanceof EnergyCellModuleBlockEntity module)) {
                // 不是储能模块的两种情形要分开处理：
                // ① 监控器等结构件：合法成员，不贡献容量与速率（不求和），继续往下看；
                // ② 本该是模块的方块却拿不到方块实体（区块刚卸、正在变化）：整轮回作废，
                //    否则会写出一份「少了某一块」的账目。
                if (!(level.getBlockState(cursor).getBlock() instanceof StructurePart)
                        || level.getBlockState(cursor).getBlock() instanceof EnergyCellModuleBlock) {
                    return;
                }
                continue;
            }
            var moduleTier = module.tier();
            // 两侧吃的是不同的组件：容量看存储组件，速率看能量存储组件（各管一条公式）。
            capacity += CellMath.moduleCapacity(moduleTier, module.componentCapacitiesScratch());
            rate += CellMath.moduleTransferRate(moduleTier, module.energyComponentsScratch());
            rateCap += moduleTier.maxTransferRate();
            inputCap += moduleTier.maxInputRate();
            tierMask |= StructureStats.tierBit(moduleTier);
            if (moduleTier.ordinal() > capTier.ordinal()) {
                capTier = moduleTier;
            }
        }
        var stats = StructureStats.of(capTier, tierMask, layout.side(), capacity, rate, rateCap, inputCap);

        // 单一能量池：整段只留一份能量，落在核心（待定项 3：拆结构时能量留在核心方块）。
        //
        // 只在「刚进入这段结构」时搬运：已经在结构里的块重扫一次不该再搬一次，否则同一份能量会被反复相加、
        // 池子随重扫次数放大。（成型后在结构里改一个组件就会重扫，这条路径很常走。）
        long carried = 0L;
        int newcomers = 0;
        for (int index = 0; index < count; index++) {
            layout.modulePos(index, cursor);
            if (level.getBlockEntity(cursor) instanceof EnergyCellModuleBlockEntity module
                    && !isAlreadyPartOf(module, layout.origin())) {
                carried += module.localStoredEnergy();
                newcomers++;
            }
        }

        for (int index = 0; index < count; index++) {
            layout.modulePos(index, cursor);
            if (level.getBlockEntity(cursor) instanceof EnergyCellModuleBlockEntity module) {
                double before = module.localStoredEnergy();
                boolean core = module.worldPosition.equals(layout.origin());
                module.applyStructureRef(layout.origin(), stats);
                if (core) {
                    // 核心自己那份已经在 before 里了，只补新进场的量；夹到整段容量上限内。
                    module.setInternalCurrentPower(Math.min(before + carried, (double) capacity));
                } else if (!isAlreadyPartOf(module, layout.origin())) {
                    module.setInternalCurrentPower(0.0);
                }
            } else if (level.getBlockEntity(cursor) instanceof MonitorBlockEntity monitor) {
                // 监控器也是结构成员（参与成型与几何），但不储能、不分担账目，只需要知道「我的核心在哪」
                // 以便正面读到整段的储能与流量。
                monitor.setCorePos(layout.origin());
            }
        }
    }

    /** 这一块在本次落到该结构之前，是否已经在同一段结构里（是就不该再动它的能量）。 */
    private static boolean isAlreadyPartOf(EnergyCellModuleBlockEntity module, BlockPos origin) {
        return module.worldPosition.equals(origin) || origin.equals(module.corePos);
    }

    /** 写入结构引用。核心记范围与整段账目，其余块记核心位置与「自己那块」的账目（作为核心不在场时的退路）。 */
    private void applyStructureRef(BlockPos origin, StructureStats stats) {
        boolean isCore = worldPosition.equals(origin);
        boolean wasCore = isCore();
        BlockPos newCorePos = isCore ? null : origin;
        BlockPos newOrigin = isCore ? origin : null;
        var newStats = isCore ? stats : StructureStats.single(tier, componentCapacitiesScratch(), energyComponentsScratch());

        // 行表缓存无条件作废（包括下面提前 return 的那条路径）：
        // 缓存键是账目快照，而“把两块方块的等级互换”这类改动可能让总容量/速率/边长/掩码恰好不变，
        // 分组却变了。作废只花一次重建（下一次查询时），漏作废则会把模块挂到错的标题下。
        this.rowCache = null;
        this.memberCache = null;

        // 上限无条件跟着账目走，包括下面提前 return 的那条路径：
        // AE2 的内部池默认上限是 10 000 AE，漏设一次，存量就会被 setStored 静默夹到 1 万——
        // 表现是「界面报得出真实容量、FE 却灌不进去」。
        setInternalMaxPower((double) newStats.totalCapacity());

        if (Objects.equals(this.corePos, newCorePos)
                && Objects.equals(this.structureOrigin, newOrigin)
                && this.ownStats.equals(newStats)) {
            return;
        }
        this.corePos = newCorePos;
        this.structureOrigin = newOrigin;
        this.ownStats = newStats;
        afterStructureReferenceChanged(wasCore);
        setChanged();
    }

    @Nullable
    private EnergyCellModuleBlockEntity resolveCore() {
        if (this.corePos == null) {
            return this;
        }
        // isLoaded 是必须的，不是「顺手加一道」：Level#getBlockEntity 会经 getChunkAt 同步加载区块，
        // 而这条方法在终端菜单每拍的同步里（isFormed/stats/storedEnergy/rows）都会被调到。
        // 核心所在区块未加载时（玩家走远、区块被卸），未加守卫的版本会在主线程上反复发起区块加载
        // ——系统就会在「保存世界」那一步停在原地不再返回（日志停在 Saving worlds，无崩溃产物）。
        if (this.level == null || !this.level.isLoaded(this.corePos)) {
            return null;
        }
        if (this.level.getBlockEntity(this.corePos) instanceof EnergyCellModuleBlockEntity core) {
            return core;
        }
        return null;
    }

    /**
     * 把窗口内的模块收进 {@code out}。
     *
     * <p>遍历顺序与 {@link MultiblockScanner.Layout#modulePos} 一致，且只跟坐标有关——窗口滑动、结构重算之后
     * 同一个下标必须还是同一块，否则菜单里的槽位会指到别的方块上。</p>
     *
     * @param startIndex 起始下标（模块序号，不是行号）
     * @param limit      最多收几块
     */
    /**
     * 结构里第 {@code index} 块模块的方块实体（按 {@link MultiblockScanner.Layout#modulePos} 的顺序）；
     * 未成型、越界或那块不在场时给 {@code null}。
     */
    @Nullable
    public EnergyCellModuleBlockEntity moduleAt(int index) {
        var level = this.level;
        if (level == null || index < 0) {
            return null;
        }
        var core = resolveCore();
        if (core == null) {
            // 配了核心却拿不到（区块未加载、或已被拆）：不能把自己当下标 0 的模块交出去——
            // 那会把槽位指到非核心块身上（组件有可能被装进别的一块）。未配核心时 resolveCore 返回自己，
            // 走不到这里。
            return null;
        }
        var origin = core.structureOrigin;
        int side = core.ownStats.side();
        if (origin == null || side <= 1) {
            return index == 0 ? core : null;
        }
        var layout = new MultiblockScanner.Layout(origin, side);
        if (index >= layout.moduleCount()) {
            return null;
        }
        var cursor = new BlockPos.MutableBlockPos();
        layout.modulePos(index, cursor);
        // 与 resolveCore 同口径：查方块实体前先问区块在不在场，不让查询变成一次区块加载。
        if (!level.isLoaded(cursor)) {
            return null;
        }
        return level.getBlockEntity(cursor) instanceof EnergyCellModuleBlockEntity module ? module : null;
    }

    /**
     * 整段里每一块的方块实体（按 {@link MultiblockScanner.Layout#modulePos} 的顺序，含核心自己）。
     *
     * <p>核心不在场（未加载、已被拆）时返回空表：调用方（手势）宁可不做事，也不要在半知半解的结构上动手。</p>
     */
    public List<EnergyCellModuleBlockEntity> structureModules() {
        var core = resolveCore();
        if (core == null) {
            // 核心区块未加载 / 核心已被拆，而我自己是它的非核心：
            // 这时整段视图不能给空表——导出总线早就把目标钉在这一块上了，给空表就变成「能塞却塞不进」。
            // 退回「只有我一块」：插进本块自己的槽（这正是该做的事，组件本来就按块计）。
            return List.of(this);
        }
        if (core.memberCache != null) {
            return core.memberCache;
        }
        int count = Math.max(1, core.ownStats.moduleCount());
        var out = new ArrayList<EnergyCellModuleBlockEntity>(count);
        for (int index = 0; index < count; index++) {
            var module = core.moduleAt(index);
            if (module != null) {
                out.add(module);
            }
        }
        core.memberCache = List.copyOf(out);
        return core.memberCache;
    }

    /** 一次组件装入/替换的结果：有没有动手，以及被换下来的那一件（装入时为空）。 */
    public record InstallResult(boolean changed, ItemStack replaced) {

        static InstallResult inserted() {
            return new InstallResult(true, ItemStack.EMPTY);
        }

        static InstallResult replaced(ItemStack old) {
            return new InstallResult(true, old);
        }
    }

    /**
     * 整段里找一个能下手的组件槽，把手上的组件装进去或换出来。
     *
     * <p>优先级（与需求一致）：<b>先填空槽</b>，没有空槽再找<b>同种、更低档</b>的组件换掉，最后才找
     * <b>另一种、同档</b>的组件。前两轮都按 {@link #structureModules()} 的顺序（从核心起），所以同一段结构里
     * 反复用这个手势，换掉的是「排在最前面」的那一件，行为可预测。</p>
     *
     * <p>要求结构成型：未成型时组件按块各算各的，整段扫描本就无意义（与 GUI 打不开同口径）。
     * 返回 {@code null} 表示「没动手」，调用方据此给玩家一句提示。</p>
     */
    @Nullable
    public InstallResult installComponent(ItemStack held) {
        if (held.isEmpty() || !isFormed()) {
            return null;
        }
        var heldTier = StorageComponents.tierOf(held);
        if (heldTier == null) {
            // 认不出档位就不动：没有标尺就无法判断「更低档」与「同档另一种」。
            return null;
        }
        boolean heldIsCapacity = StorageComponents.isCapacityComponent(held);
        var modules = structureModules();

        // 第一轮：空槽直接装。
        for (var module : modules) {
            int slots = module.components.size();
            for (int slot = 0; slot < slots; slot++) {
                if (module.components.getStackInSlot(slot).isEmpty()) {
                    module.components.setItemDirect(slot, held.copyWithCount(1));
                    return InstallResult.inserted();
                }
            }
        }

        // 第二轮：同种、更低档（多件低档时取排在最前面的那一件）。
        var lower = findSlot(modules, heldIsCapacity, currentTier -> currentTier != null
                && currentTier.ordinal() < heldTier.ordinal());
        if (lower != null) {
            var old = lower.module().components.getStackInSlot(lower.slot());
            lower.module().components.setItemDirect(lower.slot(), held.copyWithCount(1));
            return InstallResult.replaced(old);
        }

        // 第三轮：另一种类、同档（容量组件换能量组件，反过来也一样）。
        var otherKindSameTier = findSlot(modules, !heldIsCapacity, heldTier::equals);
        if (otherKindSameTier != null) {
            var old = otherKindSameTier.module().components.getStackInSlot(otherKindSameTier.slot());
            otherKindSameTier.module().components.setItemDirect(otherKindSameTier.slot(), held.copyWithCount(1));
            return InstallResult.replaced(old);
        }

        return null;
    }

    /** 找到的槽：哪一块的哪一个槽。 */
    private record FoundSlot(EnergyCellModuleBlockEntity module, int slot) {
    }

    /**
     * 在整段里找第一个满足条件的槽。
     *
     * @param capacityKind true 表示只看容量类组件
     * @param tierMatches  该槽里现有组件的档位要满足的条件
     */
    @Nullable
    private static FoundSlot findSlot(List<EnergyCellModuleBlockEntity> modules, boolean capacityKind,
            java.util.function.Predicate<StorageComponentTier> tierMatches) {
        for (var module : modules) {
            int slots = module.components.size();
            for (int slot = 0; slot < slots; slot++) {
                var current = module.components.getStackInSlot(slot);
                if (current.isEmpty() || StorageComponents.isCapacityComponent(current) != capacityKind) {
                    continue;
                }
                var currentTier = StorageComponents.tierOf(current);
                if (currentTier != null && tierMatches.test(currentTier)) {
                    return new FoundSlot(module, slot);
                }
            }
        }
        return null;
    }

    /**
     * 整段的行表（标题行按等级分组、模块行每行 3 个），见 {@link TerminalRows}。
     *
     * <p>缓存到「账目快照」上：行表只取决于哪一块是哪一档，而那段信息不会比账目变得更频——结构或组件一变，
     * 账目快照就换了一份新的（{@link StructureStats#equals}），缓存自然失效。重算一次要逐块读方块状态，
     * 而滚动窗口每帧都可能问它，所以不能每次都现算。</p>
     */
    public List<TerminalRows.Row> rows() {
        var core = resolveCore();
        // 「配了核心却拿不到」（区块未加载）时拿自己顶替算出来的一张临时表：不写缓存——
        // 写进去就会把自己那两行当成整段的表，等核心回来之前谁都拿不到真表。
        boolean degraded = core == null;
        if (core == null) {
            core = this;
        }
        if (core.rowCache != null && core.rowCacheStats != null && core.rowCacheStats.equals(core.ownStats)) {
            return core.rowCache;
        }
        var level = core.level;
        var rows = new ArrayList<TerminalRows.Row>();
        var origin = core.structureOrigin;
        int side = core.ownStats.side();
        if (level == null || origin == null || side <= 1) {
            // 未成型（或核心不在场）：只有自己一块，仍然给它一条标题 + 一条模块行，界面不至于没头没尾。
            rows.add(TerminalRows.Row.title(core.tier));
            rows.add(new TerminalRows.Row(null, 0, 1, new int[] { 0 }));
        } else {
            var layout = new MultiblockScanner.Layout(origin, side);
            int count = layout.moduleCount();
            TerminalRows.build(count, index -> tierOfModuleAt(level, layout, index), rows);
        }
        if (!degraded) {
            core.rowCache = rows;
            core.rowCacheStats = core.ownStats;
        }
        return rows;
    }

    /** 结构里第 {@code index} 块是哪个等级；那块不在场时给 {@code null}（不排进任何组）。 */
    @Nullable
    private static EnergyCellTier tierOfModuleAt(Level level, MultiblockScanner.Layout layout, int index) {
        var cursor = new BlockPos.MutableBlockPos();
        layout.modulePos(index, cursor);
        // 先问区块在不在：getBlockEntity/getBlockState 都会经 getChunkAt 把区块同步加载进来，
        // 而这条链会出现在终端每拍的行表构建里。
        if (!level.isLoaded(cursor)) {
            return null;
        }
        if (level.getBlockEntity(cursor) instanceof EnergyCellModuleBlockEntity module) {
            return module.tier();
        }
        return level.getBlockState(cursor).getBlock() instanceof EnergyCellModuleBlock block ? block.tier() : null;
    }

    /**
     * 本块槽里组件的容量值，直接喂给 {@link CellMath}。
     *
     * <p>复用同一块数组是为了不在逐块汇总时分配 4096 个小数组。返回值<b>只读且随时会被下一次调用覆盖</b>，
     * 调用方要么当场用完，要么自己复制一份。</p>
     */
    private long[] componentCapacitiesScratch() {
        Arrays.fill(capacityScratch, 0L);
        int slots = Math.min(components.size(), capacityScratch.length);
        for (int slot = 0; slot < slots; slot++) {
            capacityScratch[slot] = StorageComponents.capacityOf(components.getStackInSlot(slot));
        }
        return capacityScratch;
    }

    /**
     * 同一批槽位里<b>能量存储组件</b>的值，喂给速率侧公式（见 {@code StorageComponents#energyOf}）。
     *
     * <p>与 {@link #componentCapacitiesScratch()} 同一个契约：同一块数组、只读到下一次调用为止。</p>
     */
    private long[] energyComponentsScratch() {
        Arrays.fill(energyScratch, 0L);
        int slots = Math.min(components.size(), energyScratch.length);
        for (int slot = 0; slot < slots; slot++) {
            energyScratch[slot] = StorageComponents.energyOf(components.getStackInSlot(slot));
        }
        return energyScratch;
    }

    // ---- 渲染：连接状态 ----

    /** 六向连接位。空集合 = 孤零零一块，四周画满自己的边框。 */
    private EnumSet<Direction> getConnections() {
        var connections = EnumSet.noneOf(Direction.class);
        var level = this.level;
        if (level == null) {
            return connections;
        }
        var cursor = new BlockPos.MutableBlockPos();
        for (var direction : DIRECTIONS) {
            cursor.setWithOffset(worldPosition, direction);
            // 邻块所在区块未加载时不能问：那会变成一次同步区块加载，而这里是渲染路径（每帧算模型数据）。
            if (level.isLoaded(cursor) && MultiblockScanner.isStructurePart(level.getBlockState(cursor))) {
                connections.add(direction);
            }
        }
        return connections;
    }

    /**
     * 核心位置的区块是否已加载。
     *
     * <p>与「核心还在不在」是两件事：前者是区块没加载（等它回来就好），后者是方块已被拆（结构没了、
     * 该关屏）。未加载时读不了核心，也不该去读——那会变成一次同步区块加载。</p>
     */
    public boolean isCoreChunkLoaded() {
        return this.corePos == null || (this.level != null && this.level.isLoaded(this.corePos));
    }

    @Override
    public ModelData getModelData() {
        return EnergyCellModuleModelData.create(getConnections(), isFormed());
    }

    // ---- 存档 ----

    @Override
    public void saveAdditional(CompoundTag data, HolderLookup.Provider registries) {
        super.saveAdditional(data, registries);
        // 组件槽由父类写（NBT 键 inv），这里只管结构引用。
        // 能量不单独存：它跟着方块实体自己的能量字段（父类的 internalCurrentPower）。

        if (this.corePos != null) {
            // 非核心：只记「我的核心在哪」。账目就是自己那块，读档时现算，存了反而多一份会对不上的数。
            data.putLong("core", this.corePos.asLong());
            return;
        }
        if (this.structureOrigin != null) {
            // 核心：记范围与整段账目。
            data.putLong("origin", this.structureOrigin.asLong());
            data.putInt("side", this.ownStats.side());
            data.putLong("capacity", this.ownStats.totalCapacity());
            data.putLong("rate", this.ownStats.totalTransferRate());
            data.putLong("input", this.ownStats.totalInputRate());
            // 上限是逐块提供量之和，读档时拿不到那一段方块，所以把两个和也存下——否则 `rate` 重新收口时
            // 只能退回去拿「代表等级」的上限夹，读档后的账目会与存档前不一致。
            data.putLong("rateCap", this.ownStats.rateCapSum());
            data.putLong("inputCap", this.ownStats.inputCapSum());
            // 混等级：整段出现过哪几档要跟着走。代表等级（取最高档）由掩码现推，不另存一份会对不上的数。
            data.putInt("tierMask", this.ownStats.tierMask());
        }
    }

    /**
     * 存档里的等级掩码 → 代表等级（最高的那一档）。
     *
     * <p>掩码缺失（旧存档）或一个位都对不上时，退到「这一块自己的档」；旧存档必然是整段同档，此时自身档恰好
     * 就是正确档。</p>
     *
     * <p>推错的影响：容量直接读存档里的数，不受影响；但两个上限的和（{@code Σ V_max_j} / {@code Σ V_in_max_j}）
     * 老存档里没有，只能退用「代表等级」的上限来收口 {@code T_v = min(Σ D_vj, ·)} 与
     * {@code T_in = min(T_v × f_in, ·)}，推低会把两个上限都取小。所以调用方拿到它之后必须拿它把 {@code rate}
     * 重新夹一次（见 {@link #loadTag}）——不能直接把存档里的 rate 当结果。混拼结构的这个偏差会持续到下一次
     * 结构变更（那时会重算并按新口径存下两个和）。</p>
     */
    private EnergyCellTier highestTier(int tierMask) {
        var highest = tier;
        for (var candidate : EnergyCellTier.values()) {
            if ((tierMask & StructureStats.tierBit(candidate)) != 0 && candidate.ordinal() > highest.ordinal()) {
                highest = candidate;
            }
        }
        return highest;
    }

    @Override
    public void loadTag(CompoundTag data, HolderLookup.Provider registries) {
        // 先置位再交给父类：父类读库存用的是 setItemDirect，会走到 onContentsChanged。
        this.loading = true;
        try {
            super.loadTag(data, registries);
        } finally {
            this.loading = false;
        }
        this.corePos = data.contains("core") ? BlockPos.of(data.getLong("core")) : null;
        this.structureOrigin = data.contains("origin") ? BlockPos.of(data.getLong("origin")) : null;

        // 引用与账目分开处理：非核心的账目本来就是「自己那块」，不能因为它的 side 恒为 1 而把有效的核心引用丢掉
        // ——丢了会让重载世界之后的非核心方块在界面上退化为单块，直到下一次放置/破坏/组件变化才自愈。
        if (this.corePos != null) {
            this.structureOrigin = null;
            this.ownStats = StructureStats.single(tier, componentCapacitiesScratch(), energyComponentsScratch());
            setInternalMaxPower((double) this.ownStats.totalCapacity());
            return;
        }

        int side = data.getInt("side");
        long capacity = data.getLong("capacity");
        long rate = data.getLong("rate");
        // 存档里的边长可能来自别的版本、或被改坏：落在合法区间外就当独立元件，不拿它当结构范围用。
        if (this.structureOrigin == null || side <= 1 || side > ModularCellConstants.MAX_SIDE
                || capacity < 0L || rate < 0L) {
            this.structureOrigin = null;
            this.ownStats = StructureStats.single(tier, componentCapacitiesScratch(), energyComponentsScratch());
            setInternalMaxPower((double) this.ownStats.totalCapacity());
            return;
        }
        int tierMask = data.getInt("tierMask");
        var capTier = highestTier(tierMask);
        // 上限之和跟着存档走；老存档（没有这两个键）退回「按代表等级的上限」这条旧口径，
        // 下一次结构变更就会重算成新口径——存档不会因为口径升级而读出违反公式的账目。
        long rateCap = data.contains("rateCap") ? data.getLong("rateCap") : capTier.maxTransferRate();
        long inputCap = data.contains("inputCap") ? data.getLong("inputCap") : capTier.maxInputRate();
        // rate 必须重新收口，不能直接把存档里的数当结果：
        // 上一版（上限还没接上时）写下的存档里 rate 是未收口的和，掩码丢失时代表等级也可能取小——
        // 两者都会得出一份违反 T_v = min(Σ D_vj, Σ V_max_j) 的账目，而这份账目正是能量面的口径。
        this.ownStats = StructureStats.of(capTier, tierMask, side, capacity, rate, rateCap, inputCap);

        // 池子上限跟着账目走。
        //
        // 这一行不能省：AE2 的父类构造时把内部上限置为默认的 10 000，而读档写能量走的是
        // setStored → 按上限夹；真实上限来自 stats（结构容量）。漏了它，一份 200 kAE 的存档读进来会被
        // 静默削到 10 000，而 getAEMaxPower 报的仍是真实容量——「报得出、灌不进」。
        setInternalMaxPower((double) this.ownStats.totalCapacity());
    }

    /**
     * 延迟初始化里把「暴露面」按当前身份重算一次。
     *
     * <p>不能只靠结构变更时那一次：构造阶段 AE2 自己先调过一次
     * {@code onGridConnectableSidesChanged()}，那一刻还是「核心」（{@code corePos} 为 null），暴露面
     * 被存进了节点的 initData；读档把 {@code corePos} 填上后若不再改，重载出来的非核心会**六面全开**
     * 且带着 {@code IAEPowerStorage} 接入网格——网格里会出现与方块数同规模的存储节点，
     * 直接违背「只有一个网络节点」。这个方法幂等（见 {@code afterStructureReferenceChanged}），
     * 重复调不花代价。</p>
     */
    @Override
    public void onReady() {
        super.onReady();
        onGridConnectableSidesChanged();
    }

    /**
     * 组件槽里的东西也算「内部内容」，拆卸时要一并交出来。
     *
    /**
     * 组件槽的掉落与清空都交给父类。
     *
     * <p>{@code AEBaseInvBlockEntity} 已经把 {@code getInternalInventory()} 返回的库存接进了
     * {@code addAdditionalDrops} / {@code clearContent}（它自己那套两方法契约）——这里再写一遍就是第二份
     * 实现，而两份实现迟早会分叉（例如重复掉落、或某条路径漏清）。</p>
     */
}
