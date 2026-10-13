package io.github.lounode.ae2modularcells.common.block.entity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import io.github.lounode.ae2modularcells.common.block.MonitorBlock;
import io.github.lounode.ae2modularcells.common.block.entity.MonitorBlockEntity;
import io.github.lounode.ae2modularcells.common.ComponentGain;
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
 * <p>四条纪律写在这里免得后来者踩：</p>
 * <ul>
 * <li><b>不注册 ticker</b>：这个类没有 tick 方法，结构检测只在放置/破坏/组件变化时触发。</li>
 * <li><b>能量池只在核心</b>：储能存在核心身上；非核心从任何面收到 FE，同一 tick 转交给核心的池子
 * （{@link #funnelPowerIntoStorage}），自己不存——否则一个电池就变成了 4096 个小电池。</li>
 * <li><b>连接状态是推导值</b>：从世界方块状态现算（{@link #getConnections()}），不持久化、不做增量维护。</li>
 * <li><b>结构副作用过 {@link #guardStructure}</b>：重扫、换档、多块枚举、读档自愈都跑在服务端最要命的路径上
 * （方块 tick、以及 AE2 不吞异常的网格 tick），漏出去的异常就是崩服 + 丢未落盘改动。往这些方法里加可抛
 * 逻辑之前，先想清楚要不要把它包进防线。</li>
 * </ul>
 */
public class EnergyCellModuleBlockEntity extends AENetworkedPoweredBlockEntity
        implements InternalInventoryHost, IGridTickable {

    private static final Logger LOG = LoggerFactory.getLogger(EnergyCellModuleBlockEntity.class);

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

    /** 这一段的监控器位置（只在核心上缓存，结构重算时作废）：网格 tick 只给它们推读数。 */
    @Nullable
    private List<BlockPos> monitorCache;

    /** 结构刚重算过、整段的 FILL 需要补写一遍；档位没变的那些 tick 就不必再逐块遍历。 */
    private boolean fillDirty = true;

    /**
     * 这一块正在被拆除（见 {@code onModuleRemoved}）。
     *
     * <p>拆除路径上它还会被 {@code clearStructure} 扫到、再走一遍 {@code becomeStandalone}，但那一刻
     * <b>给不能写自己的方块状态</b>——“刚拆掉的方块自己回来”就是这个坑，AE2 有显式修复（PR #5567：先置
     * removed 标志再拆簇，注释原文「otherwise that would undo the removal」；另见
     * {@code SpatialPylonBlockEntity#updateStatus} 的同口径注释）。</p>
     *
     * <p>为什么不用 {@link #setRemoved()}：那会提前销毁网格节点，而这一 tick 里还有别的代码在用这个 BE。</p>
     */
    private boolean beingRemoved;

    /** 逐块汇总时复用的暂存数组（组件贡献），见 {@link #componentGainsScratch()}。 */
    private final ComponentGain[] gainScratch = new ComponentGain[ModularCellConstants.maxComponentSlots()];

    /**
     * 只进不出的 FE 面（懒建）。能力注册拿到的是它，不是 AE2 自己那份适配器。
     *
     * @see io.github.lounode.ae2modularcells.common.energy.InsertOnlyEnergyStorage
     */
    @Nullable
    private IEnergyStorage feInput;

    /**
     * 令牌桶里的可用令牌（AE）：额度不是「严格本 tick」，而是一小段窗口的累积，见
     * {@link #remainingInputBudget()}。配 {@link #inputTokenTick} 按游戏时间现算，不需要 ticker。
     */
    private double inputTokens;

    /** 上次补令牌的游戏刻。 */
    private long inputTokenTick = Long.MIN_VALUE;

    /** 显示用的峰值：窗口内每 tick 收得最多的一次；配 {@link #lastInputTick} 做线性衰减。 */
    private long inputRatePeak;

    /**
     * 近 1 秒窗口内收进的 AE 合计，以及窗口起点（游戏刻）。
     *
     * <p>用途与 {@link #inputRatePeak} 不同：那是「本 tick 峰值 + 线性衰减」，给输入进度条做
     * 「没人供电就自己降下去」的效果；这里是**真均值**（窗口内累计 ÷ 窗口跨过的 tick 数），
     * 给界面读数与监控器用——持续供能时是稳的，停供后一个窗口内落到 0。</p>
     */
    private long inputWindowSum;

    /**
     * 平滑后的输入速率（AE/t），界面读数与监控器都用它。
     *
     * <p>{@code -1} 表示还没建立基准（第一次结算直接取原始值，避免从 0 慢慢爬）。</p>
     */
    private long smoothedInputRate = -1L;

    /** 指数滑动平均系数：越大越跟手、越小越平缓。0.25 在「不闪」与「跟手」之间。 */
    private static final double INPUT_RATE_SMOOTHING = 0.25D;

    /** 窗口起点（游戏刻）。每结算一次就挪到当前刻。 */
    private long inputWindowStartTick = Long.MIN_VALUE;

    /**
     * 显示窗口与额度重置的时长（tick）。
     *
     * <p>界面上那根「输入速率」条读的是一个脉冲式的量（本 tick 收了多少）：没有 ticker，下一 tick 没能量进来
     * 就回 0了，而界面隔一会儿才采样一次——采样点大多落在没输入的 tick 上，看起来就是「条子不动」。
     * 所以给实测值一个窗口：<b>窗口内峰值保持 + 线性衰减</b>，持续供能时显示满值，停供后 0.5 秒内线性回落。
     * 硬保持到点再归零会让数字一跳一跳，衰减看上去更像仪表。</p>
     */
    private static final int INPUT_RATE_TTL_TICKS = 10;

    /**
     * 令牌桶容量（tick 数）：一次脉冲最多能收下这么多 tick 的量。
     *
     * <p>为什么要给缓冲：额度原先是「严格本 tick」（{@code T_in} 减去本 tick 已收），而外部 FE 源的推送节奏
     * 不由我们控制——电缆与发电机常常每隔几 tick 才推一次。那种源每次只能收满 {@code T_in}，超出的部分退回
     * 且不累积，20 tick 均值就变成 {@code T_in ÷ 推送间隔}：每 4 tick 推一次是 1/4，每 6 tick 推一次是 1/6，
     * 界面上的输入速率条永远爬不上去，加大单次推送量也没用。给了 1 秒的桶之后，脉冲源的平均输入速率与
     * 连续源一致。
     */
    private static final int INPUT_BURST_TICKS = 20;

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
        // 多块聚合：照 AE2 合成单元（CraftingBlockEntity 构造里 setFlags(MULTIBLOCK, REQUIRE_CHANNEL)
        // + addService(IGridMultiblock.class, this::getMultiblockNodes)）。
        // 网格据此把整段视为「一个多块设备」：整段合成一体，而不是 N 个互不相干的节点。
        // 成型前不接入网络：getGridConnectableSides 在未成型时返回空集（见该方法），所以散模块既不占通道，
        // 也不会在 ME 网络里顶替原版能量元件的位置。旗标的常驻代价因此只落在成型结构上：整段算 1 条通道。
        getMainNode().setFlags(appeng.api.networking.GridFlags.MULTIBLOCK,
                appeng.api.networking.GridFlags.REQUIRE_CHANNEL)
                .addService(appeng.api.networking.IGridMultiblock.class, (appeng.api.networking.IGridMultiblock) this::getMultiblockNodes);
        this.tier = requireTier(state);
        // host 不能省：AppEngInternalInventory 只有在有 host 时才会调 saveChangedInventory，
        // 而「方块实体被打上脏标记」就是这么来的。不传 host 时，组件槽里的改动会不会被存档取决于
        // rebuildStructure() 是否碰巧改了账目——改了才 setChanged，没改就静默丢掉（退游戏后组件消失）。
        // 组件槽一个槽只放一件：容量折扣按「插了几件」算，堆叠会让公式失去意义。
        this.components = new AppEngInternalInventory(this, tier.componentSlots(), 1);
        this.ownStats = StructureStats.single(tier, new ComponentGain[0]);
        // 构造期先按「未成型」登记：网格只在入网那一刻按这个旗标登记存储节点，旗标晚一步翻就摘不掉
        // （见 onReady 上方那段）。真正的值由 onReady 与结构变更时的 afterStructureReferenceChanged 重设。
        setInternalPublicPowerStorage(false);
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
     * <p><b>入力额度</b>：整段输入速率的上限是 {@code T_in = min(T_v × f_in, Σ V_in_max_j)}，按一小段窗口结算
     * （令牌桶，见 {@link #remainingInputBudget()}）——长期平均等于 {@code T_in}，同时允许外部源隔几 tick 推一次。
     * 超出额度的部分原样退回。</p>
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
     * 还能收多少 AE：令牌桶的当前余量。
     *
     * <p>每经过一 tick 补 {@code T_in} 枚令牌，桶容量 {@code T_in × BURST_TICKS}，收能量时扣掉。长期平均仍是
     * {@code T_in}（多 tick 平均的输入上限没变），但脉冲式的源能在桶里攒够一次收下，不再被推送节奏惩罚。
     *
     * <p>没有 ticker：补令牌按游戏时间现算；同一 tick 内被调用多次（SIMULATE 探一次、MODULATE 收一次）
     * 结果一致。
     */
    private double remainingInputBudget() {
        long limit = stats().totalInputRate();
        if (limit <= 0L) {
            return 0.0D;
        }
        if (this.level == null) {
            return limit;
        }
        long now = this.level.getGameTime();
        double capacity = (double) limit * INPUT_BURST_TICKS;
        if (this.inputTokenTick == Long.MIN_VALUE) {
            // 首帧：桶直接装满，免得刚放下的结构还要等一秒才吃得上第一口。
            this.inputTokens = capacity;
            this.inputTokenTick = now;
        } else if (now != this.inputTokenTick) {
            long elapsed = Math.min(Math.max(0L, now - this.inputTokenTick), INPUT_BURST_TICKS);
            this.inputTokens = Math.min(capacity, this.inputTokens + (double) limit * elapsed);
            this.inputTokenTick = now;
        }
        return Math.max(0.0D, Math.min(this.inputTokens, capacity));
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
        if (ae <= 0.0 || this.level == null) {
            return;
        }
        long amount = Math.round(ae);
        long now = this.level.getGameTime();
        this.inputWindowSum += amount;
        // 扣令牌用原值，不用上面那个取整过的量：2 FE = 1 AE 时零头常是 0.5，按 1 扣会长期少收。
        this.inputTokens = Math.max(0.0D, this.inputTokens - ae);
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
    /**
     * 近 1 秒输入均值（AE/t）。
     *
     * <p>这是界面读数与监控器共用的口径：窗口内累计 ÷ 窗口实际跨过的 tick 数——停供后一个窗口内自然落到 0，
     * 不像 {@link #inputRate()} 那样按「本 tick 峰值 + 线性衰减」走（那是进度条的观感口径）。</p>
     */
    public long averageInputRate() {
        var core = resolveCore();
        if (core != null && core != this) {
            return core.averageInputRate();
        }
        return Math.max(0L, this.smoothedInputRate);
    }

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
     * 网格接入面：<b>成型后六面都允许连接</b>，未成型一个面都不连。
     *
     * <p>未成型 = 一台「离线」的储能单元：它能被 FE 线充能（{@link #getFeInput}，只进不出），但 ME 网络
     * 看不到它、也取不到里面的 AE——否则它顶替了原版能量元件的地位。成型后每一块都可以是玩家接线的那一块，
     * 暴露面由父类推给节点。</p>
     *
     * <p>结构一变就调 {@code onGridConnectableSidesChanged()} 让父类把新的可连接面推给节点。</p>
     */
    @Override
    public java.util.Set<Direction> getGridConnectableSides(appeng.api.orientation.BlockOrientation orientation) {
        // 判据要同时夹住两端：
        // - 核心在场时以它为准。成员手里可能还留着已被拆除结构的陈旧引用（解散时它的区块未加载，
        //   clearStructure 会跳过它），那种引用会让一块孤立的散模块仍然六面连网、白占一条通道。
        // - 核心不在场时按引用乐观放行。那不代表结构散了，只是坐标还没加载——跨区块结构（16³ 能跨 8 个
        //   区块）读档时成员的 onReady 必然早于核心所在区块；这里若一律走 isFormed()（要问核心），先加载的
        //   那批成员会拿到空暴露面，而暴露面一旦为空，节点会把已有连接全部销毁，此后只能等「区块卸载重载」
        //   或「拆散重组」才能恢复，表现就是「成型结构上的线没反应」。
        if (this.level != null && this.corePos != null && this.level.isLoaded(this.corePos)) {
            return isFormed()
                    ? java.util.EnumSet.allOf(Direction.class)
                    : java.util.EnumSet.noneOf(Direction.class);
        }
        return isPartOfFormedStructure()
                ? java.util.EnumSet.allOf(Direction.class)
                : java.util.EnumSet.noneOf(Direction.class);
    }

    /*
     * 这里不强求「整段只暴露一个节点」——那是原设计，但它要求玩家把线接在核心（最小角、不可见）那块上。
     *
     * 试过的做法：让成员把核心的节点转发出去（getGridNode 返回核心节点）。它在结构拐角处会撞上 AE2 的
     * 约束：一个线缆方块同时贴着多块成员时，每一面都拿到同一个核心节点，AE2 只按「方向」去重
     * （InWorldGridNode），第二次就成了「Connection between node ... already exists」的异常，直接打断服务端
     * 网格重算。
     *
     * 现在的口径：每块各交自己的节点（AE2 合成单元就是这个做法），只在「对外提供储能」上收紧——
     * 只有核心提供，否则网格里会出现与方块数同规模的存储节点、账目被重复计算（见 afterStructureReferenceChanged）。
     */

    /**
     * 客户端同步走存档那份写入代码。
     *
     * <p>AE2 默认的 update tag 只发 {@code writeToStream} 的字段，<b>不包含</b> {@code saveAdditional}
     * ——而 {@code corePos} / {@code structureOrigin} / 账目全写在那里。不覆写这一条，客户端的 {@code corePos}
     * 恒为 null，于是 {@code isFormed()} 恒 false（成型结构在客户端永远画未成型外观）。</p>
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
        return core != null ? core.ownStats : StructureStats.single(tier, componentGainsScratch());
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
            // 网格 tick 也要包：AE2 的 TickManager 不吞 tickable 的异常，而是包成 CrashReport 直接抛，
            // 所以这里漏一个异常，结果和方块 tick 那条一样——服务端崩、未落盘的改动全丢。
            guardStructure("网格 tick（换档 / 推读数）", this::updateFillLevels);
        }
        return TickRateModulation.SAME;
    }

    /**
     * 把整段的储能比例折算成档位，写进每一块结构件的方块状态。
     *
     * <p>只写真的变了的那几块：{@code setBlock} 会发网络包，一整段 16³ 的立方体不该每秒重写一遍。
     * 这一口径与「结构检测不在渲染路径上跑」是同一个思路——贵的操作要有人把关。</p>
     */
    /**
     * 换档去抖：同一个新档连续出现这么多次采样才真的写方块状态。
     *
     * <p>采样周期就是本方法的调用周期（每 20 tick 一次网格 tick），2 次约 2 秒——视觉上够跟手，
     * 又足以吃掉「比例掠过档边界又回来」这类波动。</p>
     */
    private static final int FILL_CHANGE_SAMPLES = 2;

    /** 待确认的新档位；没有待确认时为 -1。 */
    private int pendingFillLevel = -1;

    /** 待确认档位已连续出现的采样次数。 */
    private int pendingFillSamples;

    /** 清空待确认状态：换档已认下，或已回到现档。 */
    private void resetPending() {
        this.pendingFillLevel = -1;
        this.pendingFillSamples = 0;
    }

    private void updateFillLevels() {
        var level = this.level;
        if (level == null || level.isClientSide()) {
            return;
        }
        int fill = fillLevel(lightRatio());
        // 换档两道闸：先迟滞（比例停在档边界上时四舍五入会来回跳 0↔1），再去抖（波动只是短暂掠过新档也不换）。
        // 为什么必须按住：每次写方块状态，客户端都会**重置挖掘进度**——按不住的观感就是「结构成型后怎么挖都挖不掉」。
        // AE2 合成单元没有这个问题：它的方块状态只在换档（升级）时变，不跟着储量实时变。
        int current = getBlockState().getOptionalValue(EnergyCellModuleBlock.FILL).orElse(fill);
        if (fill != current) {
            float boundary = (current + (fill > current ? 0.5f : -0.5f)) / 4f;
            if (Math.abs(lightRatio() - boundary) < 0.06f) {
                fill = current;
            }
        }
        if (fill == current) {
            resetPending();
        } else if (fill == this.pendingFillLevel) {
            // 同一个新档连续出现够次数才认；一次充放电波动不会立刻换档。
            if (++this.pendingFillSamples >= FILL_CHANGE_SAMPLES) {
                resetPending();
            } else {
                fill = current;
            }
        } else {
            this.pendingFillLevel = fill;
            this.pendingFillSamples = 1;
            fill = current;
        }
        // 先把这一段窗口结算掉（原始均值揉进 EMA），再拿**平滑值**去推监控器与界面。
        // 顺序反了的话推出去的永远是上一秒的值，屏幕上就是「慢半拍 + 每秒跳一次」。
        settleInputWindow(level.getGameTime());
        int percent = Math.round(lightRatio() * 100F);
        long average = Math.max(0L, this.smoothedInputRate);
        if (this.structureOrigin == null || this.ownStats.side() <= 1) {
            // 未成型：只有自己这一块，也得跟单块账目一致。
            applyFill(level, this.worldPosition, fill);
            return;
        }
        // 档位没变就不必再逐块写 FILL：整段的档位是同一个核心写的，档位没变就意味着每块都已经在这个档上。
        // 只有刚重算完（fillDirty）时才需要整段补一遍——新加入的块默认 0 档，而档位自己并没有「变过」。
        // 这一趟原来每个采样都要走满 4096 次 getBlockState，稳态下纯属白烧。
        if (fill != current || this.fillDirty) {
            var layout = new MultiblockScanner.Layout(this.structureOrigin, this.ownStats.side());
            var cursor = new BlockPos.MutableBlockPos();
            int count = layout.moduleCount();
            boolean allWritten = true;
            for (int index = 0; index < count; index++) {
                layout.modulePos(index, cursor);
                allWritten &= applyFill(level, cursor, fill);
            }
            // 有成员不在场（区块未加载）就不算写完：留着下一轮补，不能把 dirty 清掉。
            this.fillDirty = !allWritten;
        }
        // 读数只推监控器：原来是把整段 4096 格都 getBlockEntity 一遍，只为了找那两三台监控器。
        // 清单跟着结构走，重算时作废（见 applyStructure）。
        for (var monitorPos : monitorPositions(level)) {
            applyMonitorReadout(level, monitorPos, fill, percent, average);
        }
    }

    /**
     * 这一段的监控器位置（按需收集一次，之后由核心缓存）。
     *
     * <p>只有核心会调它（{@link #updateFillLevels} 只在核心跑）。结构一变就清缓存，代价是下一次多扫一趟
     * 整段——比起每 20 tick 都扫一遍，这便宜得多。</p>
     */
    private List<BlockPos> monitorPositions(Level level) {
        if (this.monitorCache != null) {
            return this.monitorCache;
        }
        var layout = new MultiblockScanner.Layout(this.structureOrigin, this.ownStats.side());
        var cursor = new BlockPos.MutableBlockPos();
        var found = new ArrayList<BlockPos>(4);
        boolean complete = true;
        int count = layout.moduleCount();
        for (int index = 0; index < count; index++) {
            layout.modulePos(index, cursor);
            // 未加载的成员跳过，且**这一趟不定型成缓存**：清单缺了一角，缓存下去那台监控器就再也收不到
            // 读数了（要等到下一次结构事件）。顺带避免 getBlockEntity 触发同步区块加载。
            if (!level.isLoaded(cursor)) {
                complete = false;
                continue;
            }
            if (level.getBlockEntity(cursor) instanceof MonitorBlockEntity) {
                found.add(cursor.immutable());
            }
        }
        var result = List.copyOf(found);
        if (complete) {
            this.monitorCache = result;
        }
        return result;
    }

    /**
     * 结算「近 1 秒」输入窗口：算出这一段的原始均值，再揉进指数滑动平均。
     *
     * <p>为什么要平滑：直接用每段窗口的原始均值，读数会每 20 tick 跳一次——输入只要有一点波动，
     * 界面上就是闪烁。EMA 的表现才是参考源那种「持续供能时几秒内收敛到一个稳定值，停供后平缓落到 0」。</p>
     */
    private void settleInputWindow(long now) {
        // 首帧窗口起点还是哨兵值（没结算过），按一个结算周期算——否则会拿「开局到现在的总刻度」
        // 当分母，读数从 0 慢慢爬，而且每次重载都重演一遍。
        long span = this.inputWindowStartTick == Long.MIN_VALUE
                ? Math.max(1L, FILL_CHECK_INTERVAL)
                : Math.max(1L, now - this.inputWindowStartTick);
        long raw = Math.round((double) this.inputWindowSum / span);
        this.smoothedInputRate = this.smoothedInputRate < 0L
                ? raw
                : Math.round(this.smoothedInputRate + (raw - this.smoothedInputRate) * INPUT_RATE_SMOOTHING);
        this.inputWindowStartTick = now;
        this.inputWindowSum = 0L;
    }

    /**
     * 监控器的档位方块状态与读数。
     *
     * <p>监控器与储能模块共用一套结构枚举，但属性是各自的（{@code MonitorBlock.FILL} 不是
     * {@code EnergyCellModuleBlock.FILL}），所以这里单独写一次。读数走
     * {@link MonitorBlockEntity#applyReadout}——此前这个方法没有任何调用点，监控器上一直是空的。</p>
     */
    private void applyMonitorReadout(Level level, BlockPos pos, int fill, int percent, long average) {
        if (!level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof MonitorBlockEntity monitor)) {
            return;
        }
        var state = level.getBlockState(pos);
        boolean blockChanged = false;
        if (state.getBlock() instanceof MonitorBlock && state.getValue(MonitorBlock.FILL) != fill) {
            state = state.setValue(MonitorBlock.FILL, fill);
            level.setBlock(pos, state, net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
            blockChanged = true;
        }
        // 监控器上「输入速率」那一格显示的就是近 1 秒均值，与界面同源。
        if (monitor.applyReadout(percent, average, average) || blockChanged) {
            // 读数存在方块实体的 NBT 里，客户端把它画在正面——**必须显式发包**。
            // applyReadout 只调 setChanged()，那是「区块待落盘」标记，一个字节都不会往客户端送：
            // 不补这一发，正面数字就永远是 0（实机症状：两台监控器都不出读数）。
            level.sendBlockUpdated(pos, state, state, net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
        }
    }

    /** 比例 → 档位：0% 不发光，其余四舍五入到最近的档。 */
    private static int fillLevel(float ratio) {
        return Math.max(0, Math.min(FILL_LEVELS, Math.round(ratio * FILL_LEVELS)));
    }

    /**
     * 给一块结构件写档位。监控器没有这个属性，会被 {@code hasProperty} 挡下。
     *
     * @return true 表示这块已经在该档上（下一轮不用再来）；false 表示这轮跳过了（区块未加载），得留着补
     */
    private static boolean applyFill(Level level, BlockPos pos, int fill) {
        // 必须挡住未加载区块：这个 20 tick 的网格 tick 会走遍整段结构，没加载的那部分一读一写就把区块
        // 同步加载进来（同族事故本仓已有记录：resolveCore 早期版本反复发起区块加载、停在 Saving worlds）。
        if (!level.isLoaded(pos)) {
            return false;
        }
        var state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof EnergyCellModuleBlock)
                || state.getValue(EnergyCellModuleBlock.FILL) == fill) {
            // 现状态必须是「我们这种方块」才写：位置已经被换掉（拆了、换了别的机器）时，属性名可能凑巧命中，
            // 写下去的后果是把别人的方块改成我们的形态。
            return true;
        }
        // 纯视觉：不改变任何账目，所以只发客户端，不必让服务端再跑一次邻块更新。
        level.setBlock(pos, state.setValue(EnergyCellModuleBlock.FILL, fill),
                net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
        return true;
    }

    // ---- 结构生命周期 ----

    /**
     * 从这一块出发重算结构。
     *
     * <p>三种结论各走一路：成立就落到整段，确定不成立就回退成独立元件，<b>判不了（邻居没加载完）就什么都不做</b>
     * ——保留上一次算出来的状态。把「看不到」当成「不成型」，一次跨区块的区块加载就能把一段好结构判没。</p>
     */
    /**
     * 放置 / 破坏 / 组件变化之后的结构重算入口。
     *
     * <p>跑在 {@link #guardStructure} 里：这条链跑在方块 tick 与邻居事件上（{@code ServerLevel.tickBlock → tick
     * → rebuildAt}），异常一旦漏出去就是服务端的 {@code Exception ticking world}——当场终止、未落盘的区块
     * 改动全丢（线上已经发生过一次：扫描越界 {@code Index 570 > 562}）。</p>
     */
    public void rebuildStructure() {
        var level = this.level;
        if (level == null || level.isClientSide()) {
            return;
        }
        guardStructure("重扫（放置 / 破坏 / 组件变化）", this::rescanStructure);
    }

    /**
     * 从这一块出发重算结构。
     *
     * <p>三种结论各走一路：成立就落到整段，确定不成立就回退成独立元件，<b>判不了（邻居没加载完）就什么都不做</b>
     * ——保留上一次算出来的状态。把「看不到」当成「不成型」，一次跨区块的区块加载就能把一段好结构判没。</p>
     */
    private void rescanStructure() {
        var result = MultiblockScanner.scan(level, worldPosition);
        switch (result.status()) {
            case FORMED -> {
                // 不再要求「最小角那块是储能模块」：核心由 applyStructure 在成员里挑（监控器不持有账目，不能当核心，
                // 但它在结构里的任何位置都是合法的）。整段只有监控器、一块模块都没有时，那边会自己退回去。
                applyStructure(result.layout());
            }
            case NOT_FORMED -> {
                // 这是一条**静默**路径：没异常、没崩溃，玩家只看到「结构自己散开」。所以必须留下「为什么」，
                // 否则线上只能靠猜（本次排查就为此猜错了多轮）。只在原本处于成型态时打——一堆独立模块
                // 反复重扫是常态，不该刷屏。
                if (isFormed()) {
                    LOG.info("[结构] 判不成型 → 解散整段：{} @ {}，原因：{}", worldPosition,
                            level.dimension().location(), result.detail());
                }
                clearStructure();
            }
            case INDETERMINATE -> {
                // 有意留空：等下一次触发（放置 / 破坏 / 组件变化 / 玩家右键）再算。
            }
        }
    }

    /**
     * 结构副作用的统一防线：把一次会改结构状态或方块状态的动作包起来。
     *
     * <p>这几个动作玩家看不见，却都跑在服务端最要命的三条路径上：方块 tick、邻居/放置/破坏事件、AE2 的网格
     * tick。前两条漏异常就是 {@code Exception ticking world}；网格 tick 那条更糟——AE2 的 TickManager 不吞
     * tickable 的异常，而是包成 CrashReport 直接抛出去。线上已经因此丢过一次档。</p>
     *
     * <p>口径：异常止步于日志，本轮跳过、保留原状态，等下一次触发重算。刻意不降级成「判不成型」——那等于用
     * 一次异常把整段结构解散掉，比停下来更隐蔽。{@code Error} 不捕：OOM / StackOverflow 该由 JVM 自己说话。</p>
     *
     * <p>已知取舍：若异常停在 {@code applyStructure} 的写入趟中途，成员引用可能只写了一半（一部分指向新核心、
     * 一部分还是旧引用）——这一轮不再有崩溃回滚兜底，日志里会写明「可能需重扫自愈」，靠下一次触发整段重写。</p>
     *
     * @param entry  入口名（进日志，用来区分是哪条路触发的）
     * @param action 要执行的动作
     */
    /**
     * 同一个入口的重复报错最短间隔（tick）。确定性故障（例如网格 tick 每 20 tick 䟙同一次）不该把日志与
     * 磁盘刷满，但也不能看得见一次就再也不吭声，所以只降级、不闭嘴。
     */
    private static final int FAULT_LOG_INTERVAL_TICKS = 100;

    /** 上一次报错的入口与时刻，见 {@link #FAULT_LOG_INTERVAL_TICKS}；跑通一次就清掉。 */
    @Nullable
    private String lastFaultEntry;
    private long lastFaultLogTick = Long.MIN_VALUE;

    private void guardStructure(String entry, Runnable action) {
        try {
            action.run();
            // 跑通了就把限流记忆清掉：下一次失败仍然值得看一次完整堆栈。
            this.lastFaultEntry = null;
        } catch (Exception e) {
            long now = this.level == null ? 0L : this.level.getGameTime();
            var where = this.level == null ? "unknown" : this.level.dimension().location().toString();
            boolean repeated = entry.equals(this.lastFaultEntry)
                    && now - this.lastFaultLogTick < FAULT_LOG_INTERVAL_TICKS;
            this.lastFaultEntry = entry;
            this.lastFaultLogTick = now;
            if (repeated) {
                LOG.warn("[结构] {} 仍在失败（同类故障限流中）：{} @ {}", entry, worldPosition, where);
                return;
            }
            LOG.error("[结构] {} 失败，本轮跳过（保留原状态，可能需重扫自愈）：{} @ {}，core={}，origin={}，side={}",
                    entry, worldPosition, where, this.corePos, this.structureOrigin, this.ownStats.side(), e);
        }
    }

    /**
     * 放置路径：从新方块出发检测。由方块调用。
     *
     * <p>这一层也要包：{@code getBlockEntity} 自己就可能出问题（邻块要经同步区块加载），而它是与
     * {@link #rebuildStructure()} 并列的入口——从谁那里漏出去，结果都是服务端 tick 崩塌。</p>
     */
    public static void rebuildAt(Level level, BlockPos pos) {
        if (level.isClientSide()) {
            return;
        }
        try {
            if (level.getBlockEntity(pos) instanceof EnergyCellModuleBlockEntity module) {
                module.rebuildStructure();
            }
        } catch (Exception e) {
            LOG.error("[结构] 放置 / 邻居重扫入口失败，本轮跳过：{} @ {}", pos,
                    level.dimension().location(), e);
        }
    }

    /**
     * 破坏路径：真的缺了一块就整段失效；换成别的结构件则交给下一次重扫。
     *
     * <p>「先整段失效」不能省：一段 3³ 被拆掉一块之后，剩下 26 块里的任何一块单独重扫都会失败，但失败只会
     * 让它自己回退——其余 25 块还留着指向已消失结构的核心引用。所以这里拿被拆那一块手里的范围，把整段一起
     * 清掉，再由一个邻居重扫收尾。</p>
     *
     * <p><b>换成结构件时不在这里重扫</b>：这一刻被换掉的那块**旧模块实体还在**（上一分支正依赖这个事实），
     * 重扫会把它当成「布局序第一块模块」选成新核心，把整段能量池搬进一个马上要销毁的方块实体里（存量归零）。
     * 新方块自己的 {@code onPlace} 会铺一格 tick，下一 tick 世界已经一致，那次重扫才是对的。</p>
     *
     * <p><b>真拆这一路的能量口径</b>：整段的能量物理上只存在核心方块里，所以拆掉（或用非结构件替换掉）核心
     * 那一块时，那一份存量跟着方块一起消失——这是既有设计口径（拆结构时能量留在核心），不是本方法漏搬。</p>
     */
    public static void onModuleRemoved(Level level, BlockPos pos,
            net.minecraft.world.level.block.state.BlockState newState) {
        if (level.isClientSide()) {
            return;
        }
        // 新方块仍是结构件（模块换监控器、换另一档模块、监控器换模块）：立方体没有缺块，结构照旧成立。
        // 这一路**不能**解散整段——先散开、再等下一格重成型，客户端看到的是整段闪烁，服务端卡一下就变成
        // 「结构回退成模块单元」。
        if (newState.getBlock() instanceof StructurePart) {
            return;
        }
        // 与重扫同一条防线：破坏方块时整段清理一旦抛异常，同样会把服务端带走（Exception ticking world）。
        // 清不动就保留原引用，下一次触发会重算。
        try {
            if (level.getBlockEntity(pos) instanceof EnergyCellModuleBlockEntity module) {
                // 照 AE2 的收口（PR #5567）：先把「正在拆除」立起来，再动结构。否则这一次清理会扫到自己，
                // 把自己往一个马上要变成空气的位置写回去：那正是玩家看到的「拆掉的方块自己回来了」。
                module.beingRemoved = true;
                module.clearStructure();
            }
        } catch (Exception e) {
            LOG.error("[结构] 解散失败，本轮跳过：{} @ {}", pos, level.dimension().location(), e);
        }
        rebuildAtNeighbour(level, pos);
    }

    /**
     * 从 {@code pos} 周围挑**一块**模块，让它重扫。
     *
     * <p>一块就够：扫描是连通体检测，从任一成员出发都覆盖整段。原来六个方向各扫一遍，等于把同一段结构算
     * 六遍——16³（4096 块）下这是实打实的服务端卡顿（实测单 tick 落后 8~11 秒）。</p>
     *
     * <p>挑邻居前先问区块在不在场：{@code Level#getBlockEntity} 会经 {@code getChunkAt} 同步加载区块
     * （本仓记录过「停在 Saving worlds」的事故）。</p>
     */
    public static void rebuildAtNeighbour(Level level, BlockPos pos) {
        if (level.isClientSide()) {
            return;
        }
        var cursor = new BlockPos.MutableBlockPos();
        for (var direction : DIRECTIONS) {
            cursor.setWithOffset(pos, direction);
            if (!level.isLoaded(cursor)) {
                continue;
            }
            if (level.getBlockEntity(cursor) instanceof EnergyCellModuleBlockEntity) {
                rebuildAt(level, cursor);
                return;
            }
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
            // 先问区块在不在场：getBlockEntity 会经 getChunkAt 同步加载区块（本仓记录过一次「停在 Saving worlds」）。
            // 未加载的成员本来也接不到这次解散——它加载回来时会走 onReady 自愈。
            if (!core.level.isLoaded(cursor)) {
                continue;
            }
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
        var single = StructureStats.single(tier, componentGainsScratch());
        boolean wasCore = isCore();
        boolean changed = this.corePos != null || this.structureOrigin != null || !this.ownStats.equals(single);
        if (changed) {
            // 先把节点从网格里摘出去，**再**动账目与上限。
            //
            // 为什么不能只调顺序：网格离网时（EnergyService.removeNode）按「当下读到的上限」做减法，而本类的
            // getInternalMaxPower() 覆写版读的是 stats() → ownStats——只要下面那句 this.ownStats 先执行了，
            // 减法就按缩小后的值走，旧网格的 providerPowerSum 少减一截，网络面板的「最大储能」会虚高到下次入网。
            // 先显式清空暴露面就绕开了这个耦合：节点在这一刻已经离网，而账目还没动。
            // isReady 守卫：节点被 destroy 过时，setExposedOnSides 会经 initData 抛异常（只会变成日志噪声）。
            var node = getMainNode();
            if (node.isReady()) {
                node.setExposedOnSides(java.util.EnumSet.noneOf(Direction.class));
            }
        }
        this.corePos = null;
        this.structureOrigin = null;
        this.ownStats = single;
        this.rowCache = null;
        this.memberCache = null;
        this.monitorCache = null;
        // 池子的上限跟着账目走；能量不搬（它本来就在核心里，而「核心」现在就是自己）。
        if (!changed) {
            // 本来就是独立元件：外观、暴露面、落盘内容都没有任何变化。
            // 这一路在「整段解散」时会被走满 4096 遍，每遍都发一次 markForClientUpdate——一块没变的方块
            // 不该通知客户端，那 4096 个包正是卡顿的一部分。
            setInternalMaxPower((double) single.totalCapacity());
            return;
        }
        // 退化为独立元件：不再是成型结构的一部分。
        // 顺序要紧：**先离网、再改上限**。网格摘掉这条存储时（removeNode）按当下读到的上限做减法，
        // 而下面那行紧接着就把上限换成了单块值——颠倒过来就会少减（旧网格的 providerPowerSum 长期虚高，
        // 网络面板的「最大储能」跟着失真）。
        afterStructureReferenceChanged(wasCore, false);
        setInternalMaxPower((double) single.totalCapacity());
        setChanged();
    }

    /**
     * 结构引用变完之后的收尾：核心身份可能变了，要让网格节点重新拿一次「暴露在哪些面」。
     *
     * <p>为什么不在身份真的变了时才调：{@code setExposedOnSides} 是幂等的，而判断「真变了」需要跟上一轮的
     * 集合比；调用次数与结构变更次数同量级，不值当为此多存一份状态。</p>
     */
    private void afterStructureReferenceChanged(boolean wasCore, boolean formed) {
        // 成型态是方块状态（照 AE2 合成单元），放在这里保证：只要结构引用变了，外观判定同时到位。
        updateFormedState(formed);
        // 旗标与暴露面的**顺序随方向而变**：
        // - 成型：先把旗标立好再连网。节点在合并网格时会被重新 addNode，而 EnergyService 只在那一刻读这个
        //   旗标；晚一步翻，登记就漏了。
        // - 解散：先让网格把这份存储摘干净（暴露面先空）再翻旗标。removeNode 也按旗标决定要不要做减法，
        //   先翻旗标会让旧网格里的登记留在账上——表现是「拆散了网络还能从这块吸电」。
        if (formed) {
            // 只有「成型结构的核心」对外提供储能；单块独立时不算，未成型不接入 ME 网络（见该方法）。
            setInternalPublicPowerStorage(isCore());
            onGridConnectableSidesChanged();
        } else {
            onGridConnectableSidesChanged();
            setInternalPublicPowerStorage(false);
        }
        // 模型要重画：成型与否（以及连接位）都在模型数据里，结构一变外观就得跟上。
        // 用 AE2 的 markForClientUpdate（刷模型数据 + 方块更新包）而不是只 requestModelDataUpdate()：
        // 客户端也得拿到新的核对账目，而不是只把旧数据重画一遍。
        markForClientUpdate();
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
        // 核心取「布局顺序里的第一块储能模块」，不再固定成最小角那一块。
        //
        // 原口径要求最小角必须是模块（否则整段不成型），那是为了让核心一定有账目；但它把「监控器摆在最低角」
        // 这种完全合法的拼法判死了。现在改为在成员里挑一块模块当核心，同时保证账目一定挂在储能模块上：
        // 一块模块都没有（整段都是监控器）时，没有能量池可挂，这一段不算成型。
        var corePos = firstModuleIn(layout);
        if (corePos == null) {
            // 几何成立但一块储能模块都没有（整段都是监控器）：没有账目可挂，这一段不算成型。
            if (isFormed()) {
                LOG.info("[结构] 判不成型 → 解散整段：{} @ {}，原因：整段没有储能模块（{}³ 全是监控器）",
                        worldPosition, level.dimension().location(), layout.side());
            }
            clearStructure();
            return;
        }
        // 这次重算覆盖了整段：核心的监控器清单作废（可能多了一台），整段的 FILL 也要补写一遍
        // （新加入的块默认是 0 档，而档位自己并没有「变过」，光靠「档位变了才写」补不上）。
        if (level.getBlockEntity(corePos) instanceof EnergyCellModuleBlockEntity core) {
            core.monitorCache = null;
            core.fillDirty = true;
        }

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
            // 一次算出这一块的容量与速率：两者共用同一个「插了几个组件」计数（见 CellMath#moduleGains）。
            var gains = CellMath.moduleGains(moduleTier, module.componentGainsScratch());
            capacity += gains.capacity();
            rate += gains.transfer();
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
                    && !isAlreadyPartOf(module, corePos)) {
                carried += module.localStoredEnergy();
                newcomers++;
            }
        }

        for (int index = 0; index < count; index++) {
            layout.modulePos(index, cursor);
            if (level.getBlockEntity(cursor) instanceof EnergyCellModuleBlockEntity module) {
                double before = module.localStoredEnergy();
                boolean core = module.worldPosition.equals(corePos);
                module.applyStructureRef(corePos, layout.origin(), stats);
                if (core) {
                    // 核心自己那份已经在 before 里了，只补新进场的量；夹到整段容量上限内。
                    module.setInternalCurrentPower(Math.min(before + carried, (double) capacity));
                } else if (!isAlreadyPartOf(module, corePos)) {
                    module.setInternalCurrentPower(0.0);
                }
            } else if (level.getBlockEntity(cursor) instanceof MonitorBlockEntity monitor) {
                // 监控器也是结构成员（参与成型与几何），但不储能、不分担账目，只需要知道「我的核心在哪」
                // 以便正面读到整段的储能与流量。
                monitor.setCorePos(corePos);
            }
        }
    }

    /** 这一块在本次落到该结构之前，是否已经在同一段结构里（是就不该再动它的能量）。 */
    /**
     * 这一块在本次落到该结构之前，是否已经在同一段结构里（是就不该再动它的能量）。
     *
     * <p>比较用的是<b>核心位置</b>而不是几何最小角：结构身份由核心认，不是由哪个角认。用错那个会把真正的
     * 核心当成「新进场」——存量被归零、搬运量被丢弃，表现就是「每次重扫电量清空」。</p>
     */
    private static boolean isAlreadyPartOf(EnergyCellModuleBlockEntity module, BlockPos corePos) {
        return module.worldPosition.equals(corePos) || corePos.equals(module.corePos);
    }

    /**
     * 结构遍历序上的模块等级序列（客户端本地排行表用，见 {@code TerminalRows#build}）。
     *
     * <p><b>必须与 {@link #rows()} 同源</b>：同一个 multiblock 遍历序、同一个 {@code tierOfModuleAt}。
     * 服务端按窗口填槽内容、客户端按这份序列排本地行表，两端只要遍历序不同，就会出现「客户端把 A 模块画在第 i 格、
     * 服务端把 B 模块填进第 i 格」——玩家会把组件装到别的模块上。别改成走窗口列表（那是随滚动漂移的）。</p>
     */
    public EnergyCellTier[] moduleTiersInOrder() {
        var core = resolveCore();
        var level = this.level;
        if (core == null || level == null || core.structureOrigin == null || core.ownStats.side() <= 1) {
            return new EnergyCellTier[0];
        }
        var layout = new MultiblockScanner.Layout(core.structureOrigin, core.ownStats.side());
        int count = layout.moduleCount();
        var tiers = new EnergyCellTier[count];
        for (int index = 0; index < count; index++) {
            tiers[index] = tierOfModuleAt(level, layout, index);
        }
        return tiers;
    }

    /**
     * 布局里第一块储能模块的位置（确定性：按 {@code modulePos} 的顺序，第一块中选的永远相同）。
     *
     * <p>核心只能落在储能模块上——监控器不存能量、也没有账目，让它当核心会让整段没有能量池、终端也打不开。
     * 但核心**不必**是最小角那一块，所以这里要在成员里找。</p>
     */
    @Nullable
    private BlockPos firstModuleIn(MultiblockScanner.Layout layout) {
        var level = this.level;
        if (level == null) {
            return null;
        }
        var cursor = new BlockPos.MutableBlockPos();
        int count = layout.moduleCount();
        for (int index = 0; index < count; index++) {
            layout.modulePos(index, cursor);
            if (level.getBlockEntity(cursor) instanceof EnergyCellModuleBlockEntity) {
                return cursor.immutable();
            }
        }
        return null;
    }

    /** 写入结构引用。核心记范围与整段账目，其余块记核心位置与「自己那块」的账目（作为核心不在场时的退路）。 */
    private void applyStructureRef(BlockPos corePos, BlockPos origin, StructureStats stats) {
        boolean isCore = worldPosition.equals(corePos);
        boolean wasCore = isCore();
        BlockPos newCorePos = isCore ? null : corePos;
        BlockPos newOrigin = isCore ? origin : null;
        var newStats = isCore ? stats : StructureStats.single(tier, componentGainsScratch());

        // 行表缓存无条件作废（包括下面提前 return 的那条路径）：
        // 缓存键是账目快照，而“把两块方块的等级互换”这类改动可能让总容量/速率/边长/掩码恰好不变，
        // 分组却变了。作废只花一次重建（下一次查询时），漏作废则会把模块挂到错的标题下。
        this.rowCache = null;
        this.memberCache = null;

        // 上限无条件跟着账目走，包括下面提前 return 的那条路径：
        // AE2 的内部池默认上限是 10 000 AE，漏设一次，存量就会被 setStored 静默夹到 1 万——
        // 表现是「界面报得出真实容量、FE 却灌不进去」。
        // 非核心报 0 上限（口径：独立放置 = 单块容量、成员 = 0）。
        //
        // 诊断要记清楚，否则会照错的方向修：网格只在**节点入/离网那一刻**按 {@code isAEPublicPowerStorage()}
        // 登记存储节点，而网格的总量是增量维护的缓存值——成员入网后再翻旗标，已经登记的那一条摘不掉。
        // 所以真正的闸门是「旗标在入网之前就是对的」（见 onReady() 里 setInternalPublicPowerStorage 的位置），
        // **不是**这句 setInternalMaxPower：本类的 getInternalMaxPower() 覆盖版对非核心返回 stats().totalCapacity()，
        // 网格读的是覆盖版，父类这个字段对网格没有效果——它只影响父类自己的 setStored 夹取。
        // 重新成为核心时会被这里的 isCore 分支还原。
        setInternalMaxPower(isCore ? (double) newStats.totalCapacity() : 0.0D);

        if (Objects.equals(this.corePos, newCorePos)
                && Objects.equals(this.structureOrigin, newOrigin)
                && this.ownStats.equals(newStats)) {
            return;
        }
        this.corePos = newCorePos;
        this.structureOrigin = newOrigin;
        this.ownStats = newStats;
        // 走到这里就是被写进一段成型结构了。
        afterStructureReferenceChanged(wasCore, true);
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
        if (out.size() < count) {
            // 成员没加载齐：**不写缓存**，返回一份临时视图即可。
            //
            // 为什么不能写缓存：走到这里说明缓存本来就是空的（上面 :1171 已对非空缓存早退），所以这里无论如何
            // 都不该把残缺结果定型下来——一旦定型，它会在结构下一次写入之前一直生效，同一段结构的槽数会因为
            // 「定型发生在哪一刻」而不同（Jade 容器行、导出总线的可插槽位都读它）。
            // 下次调用若成员已到齐会重新收集并正常写缓存；跨区块的结构因此收敛到最后一次完整的视图上。
            return List.copyOf(out);
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
     * 本块槽里组件的贡献，直接喂给 {@link CellMath#moduleGains}。
     *
     * <p>复用同一块数组是为了不在逐块汇总时分配 4096 个小数组。返回值<b>只读且随时会被下一次调用覆盖</b>，
     * 调用方要么当场用完，要么自己复制一份。</p>
     *
     * <p>空槽写 {@code null} 而不是零值贡献：{@code CellMath} 要靠「非 null」数出实际插了几个组件，而一个
     * 真实的组件也可能两个维度都为 0。</p>
     */
    private ComponentGain[] componentGainsScratch() {
        Arrays.fill(gainScratch, null);
        int slots = Math.min(components.size(), gainScratch.length);
        for (int slot = 0; slot < slots; slot++) {
            gainScratch[slot] = StorageComponents.gainOf(components.getStackInSlot(slot));
        }
        return gainScratch;
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

    /**
     * 多块聚合的兄弟节点：整段结构里除自己以外的成员节点。
     *
     * <p>网格用它把 N 块当作一个多块设备（通道、寻路、设备列表只算一次）。监控器没有节点，天然跳过；
     * 未成型或核心区块未加载时返回空——那时它只是自己一块。</p>
     */
    private java.util.Iterator<appeng.api.networking.IGridNode> getMultiblockNodes() {
        var level = this.level;
        var core = resolveCore();
        if (level == null || core == null || core.structureOrigin == null || core.ownStats.side() <= 1) {
            return java.util.List.<appeng.api.networking.IGridNode>of().iterator();
        }
        // AE2 的算路服务直接调这个方法，而且上游那一层没有 try（PathingService）——漏出去的异常就是
        // 服务端崩。所以这里自己兜：退化成「这一段没有多块兄弟」，通道会按每块各算一次，不至于崩。
        try {
            var layout = new MultiblockScanner.Layout(core.structureOrigin, core.ownStats.side());
            var nodes = new java.util.ArrayList<appeng.api.networking.IGridNode>();
            var cursor = new BlockPos.MutableBlockPos();
            int count = layout.moduleCount();
            for (int index = 0; index < count; index++) {
                layout.modulePos(index, cursor);
                if (level.getBlockEntity(cursor) instanceof EnergyCellModuleBlockEntity member && member != this) {
                    var node = member.getMainNode().getNode();
                    if (node != null) {
                        nodes.add(node);
                    }
                }
            }
            return nodes.iterator();
        } catch (Exception e) {
            LOG.warn("[结构] 多块节点枚举失败，本次退化为单块：{} @ {}", worldPosition,
                    level.dimension().location(), e);
            return java.util.List.<appeng.api.networking.IGridNode>of().iterator();
        }
    }

    @Override
    public ModelData getModelData() {
        // 只带连接位：成型与否已经放到方块状态里（见 EnergyCellModuleBlock.FORMED）。
        return EnergyCellModuleModelData.create(getConnections());
    }

    /**
     * 把「是否成型」写进方块状态。
     *
     * <p>照 AE2 合成单元：{@code CraftingBlockEntity#updateSubType} 用
     * {@code level.setBlock(pos, newState, Block.UPDATE_CLIENTS)} 切成型态（不带邻居更新是故意的——代上邻居更新会死循环）。
     * 客户端因此不靠自定义包就能拿到成型态，模型选择/绘制随之切换。</p>
     */
    private void updateFormedState(boolean formed) {
        var level = this.level;
        if (level == null || level.isClientSide()) {
            return;
        }
        // 照 AE2 口径（AEBaseBlockEntity#markForUpdate 的三重短路）：已被移除、区块不在场、或正在被拆除时，
        // 一律不写方块状态——写下去就是把刚拆掉的方块原样摆回去。
        if (this.beingRemoved || isRemoved() || notLoaded()) {
            return;
        }
        var state = getBlockState();
        // 现状态必须是「我们这种方块」才写（比 hasProperty 更严：位置已被换掉时不碰别人的方块）。
        if (!(state.getBlock() instanceof EnergyCellModuleBlock)) {
            // 复用故障日志的限流：批量替换（/fill 换成别的方块之类）会一块一条，不节流就是几千行。
            long now = level.getGameTime();
            boolean repeated = "写成型态".equals(this.lastFaultEntry)
                    && now - this.lastFaultLogTick < FAULT_LOG_INTERVAL_TICKS;
            this.lastFaultEntry = "写成型态";
            this.lastFaultLogTick = now;
            if (!repeated) {
                LOG.warn("[结构] 写成型态时发现位置上的方块已不是本模组的：{} 现为 {}（写 FORMED={} 已跳过）",
                        worldPosition, state.getBlock(), formed);
            }
            return;
        }
        if (state.getValue(EnergyCellModuleBlock.FORMED) != formed) {
            level.setBlock(worldPosition, state.setValue(EnergyCellModuleBlock.FORMED, formed),
                    net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
        }
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
        // 成型外观靠模型数据（成型与否 + 连接位），而它在客户端是按位置缓存的：收到同步包不会自动失效。
        // 服务端在结构变化时已经推过一条（markForClientUpdate = requestModelDataUpdate + sendBlockUpdated），
        // 这里把客户端那一半补上——不补的话实机就是「结构成型了，模块方块仍是未成型纹理」。
        // 放 loadTag 而不是 handleUpdateTag：读档、同步包两条路径都会落到这里。
        if (this.level != null && this.level.isClientSide()) {
            requestModelDataUpdate();
        }
        this.structureOrigin = data.contains("origin") ? BlockPos.of(data.getLong("origin")) : null;

        // 引用与账目分开处理：非核心的账目本来就是「自己那块」，不能因为它的 side 恒为 1 而把有效的核心引用丢掉
        // ——丢了会让重载世界之后的非核心方块在界面上退化为单块，直到下一次放置/破坏/组件变化才自愈。
        if (this.corePos != null) {
            this.structureOrigin = null;
            this.ownStats = StructureStats.single(tier, componentGainsScratch());
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
            this.ownStats = StructureStats.single(tier, componentGainsScratch());
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
        // 必须在 super.onReady() **之前**：父类在 super 里就把节点建好并接入网格，而网格只在**入网/离网那一刻**
        // 按公开标志登记存储节点（EnergyService 的总量是增量维护的缓存值）。晚一步翻旗标，已经登记进去的
        // 成员不会被摘掉——读档后 ME 侧的总容量依旧按块重复计入。
        // 未成型（含读档后还没重算的）一律不充当公开存储，结合上面的暴露面，散模块不进网格。
        setInternalPublicPowerStorage(isCore() && isFormed());
        super.onReady();
        onGridConnectableSidesChanged();
        // 兜底第二道：读档路径（loadTag）会把上限设成单块容量，那是「独立放置」的口径；非核心这里再收口成 0，
        // 与 applyStructureRef 一致——即便真有残留登记，它贡献的也是 0。
        if (!isCore()) {
            setInternalMaxPower(0.0D);
        }
        // 自愈一次成型态：升级前的存档里 formed 还是默认 false；结构在某成员区块卸载期间于别处解散时，
        // 成员读档后引用还在、状态却是 true。只在「确实是成型结构」时补，避免核心区块未加载时把 true 抹掉。
        if (isFormed()) {
            guardStructure("读档自愈成型态", () -> updateFormedState(true));
        } else if (!isPartOfFormedStructure()) {
            // 反向也要自愈：结构在某成员区块卸载期间解散时，成员读档后引用已被清空、方块状态却还停在 true
            // （视觉上仍是成型外观，要等下一次结构事件才归位）。这里的判据是「引用为空」这个确定结论，
            // 不受核心区块加载状态干扰。
            guardStructure("读档自愈成型态（清除）", () -> updateFormedState(false));
        }
        // 核心区块加载这一刻，替整段成员重推一次暴露面。成员的 onReady 往往早于核心（见
        // getGridConnectableSides），那时它只能按自己的引用判断该不该连网；而「核心换位」「解散后重组」
        // 这类变化只由结构事件通知，成员区块重新加载时不会再收到一次。这里一次遍历把它们对齐，
        // 未加载的成员由它自己的 onReady 兜住。不注册 ticker，只在核心加载时走这一遍。
        // 过 guardStructure：它是一次结构级遍历，与本文件其它结构副作用同一条纪律。
        guardStructure("核心加载后重推成员暴露面", this::refreshMemberExposure);
    }

    /** 让整段成员按当前结构重推一次暴露面（只在核心的 onReady 里走一次）。 */
    private void refreshMemberExposure() {
        var level = this.level;
        if (level == null || !isFormed()) {
            return;
        }
        var layout = new MultiblockScanner.Layout(this.structureOrigin, this.ownStats.side());
        var cursor = new BlockPos.MutableBlockPos();
        int count = layout.moduleCount();
        for (int index = 0; index < count; index++) {
            layout.modulePos(index, cursor);
            // 先问区块在不在场：getBlockEntity 会经 getChunkAt 同步加载区块（本仓记录过一次「停在 Saving worlds」）。
            if (!level.isLoaded(cursor)) {
                continue;
            }
            var member = level.getBlockEntity(cursor);
            if (member instanceof EnergyCellModuleBlockEntity module && module != this) {
                module.onGridConnectableSidesChanged();
            } else if (member instanceof MonitorBlockEntity monitor) {
                // 监控器的核心引用与成型态也由核心驱动：它的 onLoad 早于核心时就无从判断，只能先按未成型
                // 显示（不能无条件补 true，否则拆掉的散监控器会一直顶着成型外观）。这里补上，
                // setCorePos 自己比较相等并早退，重复调不花代价。
                monitor.setCorePos(this.worldPosition);
            }
        }
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
