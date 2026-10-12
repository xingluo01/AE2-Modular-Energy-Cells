package io.github.lounode.ae2modularcells.common.block.entity;

import java.util.Objects;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import org.jetbrains.annotations.Nullable;

import io.github.lounode.ae2modularcells.common.block.MonitorBlock;

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

    /** 推送的粒度：百分比按 1%、流量按 64 AE/t。低于这个幅度的变化不推包。 */
    private static final int PERCENT_STEP = 1;
    private static final long RATE_STEP = 64L;

    /** 我所属结构的核心位置；未成型时为 null。 */
    @Nullable
    private BlockPos corePos;

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
        if (this.corePos != null) {
            updateFormedState(true);
        }
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
        var state = getBlockState();
        if (state.hasProperty(MonitorBlock.FORMED) && state.getValue(MonitorBlock.FORMED) != formed) {
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

    /** 客户端同步走 NBT：读档与「核心推了新读数」用的是同一份写入代码。 */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }
}
