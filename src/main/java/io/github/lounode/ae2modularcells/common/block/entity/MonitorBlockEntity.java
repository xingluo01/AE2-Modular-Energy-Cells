package io.github.lounode.ae2modularcells.common.block.entity;

import java.util.EnumSet;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.energy.IEnergyStorage;

import org.jetbrains.annotations.Nullable;

import io.github.lounode.ae2modularcells.common.block.MonitorBlock;
import io.github.lounode.ae2modularcells.common.block.StructurePart;

/**
 * 监控器方块实体：只保存「显示用」的那几个数，以及它所属结构的核心坐标。
 *
 * <p><b>读数由核心推送</b>（口径已定）：核心在它的网格 tick 里算出整段的储能百分比与输入流量，再写到本段的
 * 监控器上。所以监控器这边<b>没有 ticker、也没有自己的网格节点</b>——整段结构依旧只有一个节点。</p>
 *
 * <p><b>为什么用 NBT 同步而不是自定义网络包</b>：读数本来就该跟着方块实体一起同步（区块重载、玩家走远再回来都
 * 要能立刻画出上一次的值），而 {@code getUpdateTag} + {@code sendBlockUpdated} 就是原版给这条路造的车。</p>
 *
 * <p><b>只在显示值真的变了才推</b>：{@link #applyReadout} 先做一次粗粒度比较（百分比取整到 1%、流量取整到
 * 64 AE/t），不变就直接返回。否则核心每 tick 都推一次，1 秒 20 个包只为了把小数点后几位改掉。</p>
 */
public class MonitorBlockEntity extends BlockEntity {

    private static final Logger LOG = LoggerFactory.getLogger(MonitorBlockEntity.class);

    /** 同一条「位置已不是我们的方块」告警的最短间隔（tick）：批量替换时会一块一条，不节流就是几千行。 */
    private static final int WARN_INTERVAL_TICKS = 100;

    /** 推送的粒度：百分比按 1%、流量按 64 AE/t。低于这个幅度的变化不推包。 */
    private static final int PERCENT_STEP = 1;
    private static final long RATE_STEP = 64L;

    /** 我所属结构的核心位置；未成型时为 null。 */
    @Nullable
    private BlockPos corePos;

    /** 上一次告警时刻，见 {@link #WARN_INTERVAL_TICKS}。 */
    private long lastWarnTick = Long.MIN_VALUE;

    /** 储能百分比（0~100，取整）；流量监控器用不到，留着是为了两类监控器共用一套同步。 */
    private int storagePercent;
    /** 输入流量（AE/t，实测）。 */
    private long inputRate;
    /** 吞吐量（AE/t，1 秒均值）。 */
    private long throughput;

    public MonitorBlockEntity(net.minecraft.world.level.block.entity.BlockEntityType<?> type, BlockPos pos,
            BlockState state) {
        super(type, pos, state);
    }

    public boolean isFlowMonitor() {
        return this.getBlockState().getBlock() instanceof MonitorBlock monitor && monitor.kind() == MonitorBlock.Kind.FLOW;
    }

    @Nullable
    public BlockPos corePos() {
        return this.corePos;
    }

    /**
     * FE 接收面：NeoForge 的能量能力。
     *
     * <p>监控器自己不存能量，也不属于能量面——它只是整段结构上的一块显示面板。但玩家会把线接在整段的任意一块
     * 上，所以这里把外部 FE 原样转交给核心：核心那块才是整段唯一的能量池。换算比例与「只进不出」的口径都由核心
     * 的 FE 面负责，这里不复制一份——同一个比例出现在两处，改一处就会对不上。</p>
     */
    @Nullable
    public IEnergyStorage getFeInput(@Nullable Direction side) {
        var core = resolveCore();
        // 面参数故意不传给核心：整段的进能面不分朝向（AE2 默认 ALL_SIDES），而监控器的朝向与核心的朝向没有
        // 对应关系——原样透传等于把「监控器的那一面」当成「核心的那一面」，一旦上游按面限制（setPowerSides）
        // 就会在某些朝向上拿到 null，症状回到「线接上没反应」。传 null = 不区分面，与「整段一个池子」同义。
        return core == null ? null : core.getFeInput(null);
    }

    /** 我所属结构的核心；未成型、或核心已经不在了的时候为 null。 */
    @Nullable
    private EnergyCellModuleBlockEntity resolveCore() {
        if (this.corePos == null || this.level == null) {
            return null;
        }
        return this.level.getBlockEntity(this.corePos) instanceof EnergyCellModuleBlockEntity core ? core : null;
    }

    /** 结构重算时由核心写入（见 {@code EnergyCellModuleBlockEntity#applyStructure}）。 */
    public void setCorePos(@Nullable BlockPos corePos) {
        if (Objects.equals(this.corePos, corePos)) {
            return;
        }
        this.corePos = corePos;
        setChanged();
        // 成型态写进方块状态（照 AE2 合成单元：成型切换走方块状态，客户端必然同步）：
        // 有核心 = 已经是某段成型结构的一员（核心只在结构成立时才写这个引用）。
        updateFormedState(corePos != null);
    }

    /**
     * 读档/区块加载后自愈成型态。
     *
     * <p>与模块同一口径（见 {@code EnergyCellModuleBlockEntity.onReady} 里那句 updateFormedState）：
     * 方块状态与方块实体数据是两处落盘，可能出现「存档里 FORMED 还是 false、而 corePos 已经非空」
     * （升级前旧存档、结构在监控器区块卸载期间成型）。没有这一步，显示侧的未成型守卫会把这个脏状态
     * 固化成「这块监控器永远不显示读数」。</p>
     */
    @Override
    public void onLoad() {
        super.onLoad();
        if (this.corePos == null) {
            // 引用为空 = 确定不属于任何结构。方块状态却可能停在 true：旧存档里被旧版代码写过，或者结构在
            // 本块区块卸载期间解散。以前这里直接 return，于是那种脏状态永远不会被纠正——一块独立摆着的
            // 监控器就一直顶着成型外观（实机反馈的现象）。这里补回 false。
            updateFormedState(false);
            return;
        }
        if (this.level == null || !this.level.isLoaded(this.corePos)) {
            // 核心区块还没加载：无从判断引用是否还有效，按有效处理（等它加载回来自然会对上）。
            updateFormedState(true);
            return;
        }
        // 核心在场就以它为准：位置空了、或它自己已经不在这段成型结构里（被拆散、退化成独立模块），
        // 我这条引用就是陈旧的——清掉并退回未成型外观，否则它会一直顶着成型面板。
        if (!(this.level.getBlockEntity(this.corePos) instanceof EnergyCellModuleBlockEntity core)
                || !core.isFormed()) {
            setCorePos(null);
            return;
        }
        updateFormedState(true);
    }

    public int storagePercent() {
        return this.storagePercent;
    }

    public long inputRate() {
        return this.inputRate;
    }

    public long throughput() {
        return this.throughput;
    }

    /**
     * 核心推送读数。返回是否真的更新过（调用方据此决定要不要发包，一般直接用返回值）。
     *
     * @param storagePercent 整段储能百分比（0~100）
     * @param inputRate      本 tick 收进的 AE（实测输入流量）
     * @param throughput     近 1 秒平均吞吐（AE/t）
     */
    public boolean applyReadout(int storagePercent, long inputRate, long throughput) {
        if (sameDisplay(storagePercent, inputRate, throughput)) {
            return false;
        }
        this.storagePercent = storagePercent;
        this.inputRate = inputRate;
        this.throughput = throughput;
        setChanged();
        return true;
    }

    /**
     * 把「是否成型」写进方块状态。
     *
     * <p>与模块方块同一口径：成型态是方块状态而不是方块实体数据——模型数据在客户端按位置缓存、靠自定义包推
     * 容易漏一环，方块状态不会。</p>
     */
    private void updateFormedState(boolean formed) {
        var level = this.level;
        if (level == null || level.isClientSide()) {
            return;
        }
        // 与模块方块同一套守卫（照 AE2 AEBaseBlockEntity#markForUpdate 的三重短路）：已被移除 / 区块不在场时
        // 一律不写方块状态——写下去会把刚拆掉的监控器原样摆回去（AE2 在同类坑上有显式修复：PR #5567）。
        if (isRemoved() || !level.hasChunkAt(worldPosition)) {
            return;
        }
        var state = getBlockState();
        // 现状态必须是监控器才写（比 hasProperty 更严：位置已被换掉时不碰别人的方块）。
        if (!(state.getBlock() instanceof MonitorBlock)) {
            long now = level.getGameTime();
            if (now - this.lastWarnTick >= WARN_INTERVAL_TICKS) {
                this.lastWarnTick = now;
                LOG.warn("[结构] 写监控器成型态时发现位置上的方块已不是本模组的：{} 现为 {}（写 FORMED={} 已跳过）",
                        worldPosition, state.getBlock(), formed);
            }
            return;
        }
        if (state.getValue(MonitorBlock.FORMED) != formed) {
            level.setBlock(worldPosition, state.setValue(MonitorBlock.FORMED, formed),
                    net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
        }
    }

    /** 粗粒度比较：只有「屏幕上看得出变化」才算变了。 */
    private boolean sameDisplay(int storagePercent, long inputRate, long throughput) {
        return Math.abs(this.storagePercent - storagePercent) < PERCENT_STEP
                && Math.abs(this.inputRate - inputRate) < RATE_STEP
                && Math.abs(this.throughput - throughput) < RATE_STEP;
    }

    @Override
    public void saveAdditional(CompoundTag data, HolderLookup.Provider registries) {
        super.saveAdditional(data, registries);
        if (this.corePos != null) {
            data.putLong("core", this.corePos.asLong());
        }
        data.putInt("percent", this.storagePercent);
        data.putLong("input", this.inputRate);
        data.putLong("throughput", this.throughput);
    }

    @Override
    public void loadAdditional(CompoundTag data, HolderLookup.Provider registries) {
        super.loadAdditional(data, registries);
        this.corePos = data.contains("core") ? BlockPos.of(data.getLong("core")) : null;
        this.storagePercent = data.getInt("percent");
        this.inputRate = data.getLong("input");
        this.throughput = data.getLong("throughput");
    }

    /**
     * 连接位：哪几面贴着结构件。侧面要按它与模块同规格地收边（未连接的一侧补边框），所以监控器也得
     * 交一份出来——与模块同一套 {@link EnergyCellModuleModelData}，客户端拿它画面。
     */
    @Override
    public ModelData getModelData() {
        return EnergyCellModuleModelData.create(connections());
    }

    /** 六面里哪几面贴着结构件。与结构判定同口径：只看是不是结构件（模块或监控器），不看等级、不看成型。 */
    private EnumSet<Direction> connections() {
        var out = EnumSet.noneOf(Direction.class);
        var level = this.level;
        if (level == null) {
            return out;
        }
        var cursor = new BlockPos.MutableBlockPos();
        for (var direction : Direction.values()) {
            cursor.setWithOffset(this.worldPosition, direction);
            if (level.getBlockState(cursor).getBlock() instanceof StructurePart) {
                out.add(direction);
            }
        }
        return out;
    }

    /**
     * 客户端同步走 NBT：读档与「核心推了新读数」用的是同一份写入代码。
     */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    /**
     * 主动推送用的包：<b>必须覆写</b>。
     *
     * <p>原版 {@code BlockEntity#getUpdatePacket()} 默认返回 {@code null}，而 {@code Level#sendBlockUpdated}
     * 走的就是它——不覆写，方块实体数据一个字节也发不出去。症状很隐蔽：正面档位（方块状态）照常变，
     * 百分比与吞吐却永远停在刚进区块时的值（新结构上就是 0%）。</p>
     *
     * <p>只覆写 {@link #getUpdateTag} 不够：那个是「区块发送时打包」用的，不是「主动推送」用的。</p>
     */
    @Override
    public net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ClientGamePacketListener> getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }
}
