package io.github.lounode.ae2modularcells.common;

import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;

/**
 * 一个模块等级的数值定义：单块基础容量、基础速率、组件槽位数、速率上限、输入速率上限。
 *
 * <p><b>它是注册表元素，不是枚举</b>：本模组内置三档（基础 / 致密 / 超密，见 {@link ModularCellTiers}），
 * 其他模组可以注册自己的等级——方块与方块实体都吃任意等级，所以新等级配上方块就能直接接入多方块与能量系统。
 * 注册走 NeoForge 的自定义注册表（{@link ModularCellTiers#TIERS}），本类的实例只由注册表持有。</p>
 *
 * <p><b>为什么数值在这里而不在配置里</b>：这五个数是「这个等级是什么」的一部分，与方块一一对应；配置管的是
 * 打折与加成（{@code ModularCellConstants} / {@code ModularCellConfig} 那套）。等级不可由配置文件新增——
 * 新等级需要新方块，而方块不能在运行期凭空出现。</p>
 *
 * <p>单位：容量与速率都是 AE 与 AE/t，{@code 200_000} 就是 200 kAE。</p>
 *
 * @param id               注册名（路径部分），例如 {@code basic}；方块名、语言键都用它
 * @param baseCapacity     单模块基础容量 A（AE），容量公式里的加数项
 * @param baseTransferRate 单模块基础传输速率 V（AE/t）
 * @param componentSlots   每个模块提供的组件槽位数（1 / 2 / 3），也是公式里 {@code C_cap} / {@code C_tr} 的上界
 * @param maxTransferRate  每块提供的速率上限 V_max（AE/t）；整段上限是逐块之和
 * @param maxInputRate     每块提供的输入速率上限 V_in_max（AE/t）；整段上限同样是逐块之和
 */
public final class EnergyCellTier {

    private final String id;
    private final long baseCapacity;
    private final long baseTransferRate;
    private final int componentSlots;
    private final long maxTransferRate;
    private final long maxInputRate;

    /**
     * 建一个等级。实例必须交给注册表（{@link ModularCellTiers#TIERS}）管理。
     *
     * <p>负数与 0 槽位在这里就被挡掉：它们是坏数据，而等级是启动期注册的，让它在注册时炸掉比等到运行期算出
     * 一台负容量的电池要好。</p>
     */
    public EnergyCellTier(String id, long baseCapacity, long baseTransferRate, int componentSlots, long maxTransferRate,
            long maxInputRate) {
        if (id == null || !id.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException(
                    "Energy cell tier id must be a non-empty lowercase resource path, got: " + id);
        }
        this.id = id;
        if (baseCapacity < 0L || baseTransferRate < 0L || maxTransferRate < 0L || maxInputRate < 0L) {
            throw new IllegalArgumentException("Energy cell tier values must not be negative");
        }
        if (componentSlots <= 0) {
            throw new IllegalArgumentException("Energy cell tier needs at least one component slot");
        }
        this.baseCapacity = baseCapacity;
        this.baseTransferRate = baseTransferRate;
        this.componentSlots = componentSlots;
        this.maxTransferRate = maxTransferRate;
        this.maxInputRate = maxInputRate;
    }

    /** 注册名（路径部分），例如 {@code basic}——方块名、语言键与配置名都用它。 */
    public String id() {
        return id;
    }

    /**
     * 完整注册名，例如 {@code ae2modularcells:basic}；尚未注册进注册表时给 {@code null}。
     *
     * <p>供日志与异常消息用：出错时标出「是哪一份注册」比短 id 更能定位。内部逻辑用 {@link #id()}——它是自带字段，
     * 注册前后都可用。</p>
     */
    @Nullable
    public ResourceLocation registryName() {
        return ModularCellTiers.registryNameOf(this);
    }

    /** 单模块基础容量 A（AE）。 */
    public long baseCapacity() {
        return baseCapacity;
    }

    /** 单模块基础传输速率 V（AE/t）。 */
    public long baseTransferRate() {
        return baseTransferRate;
    }

    /** 每个模块提供的组件槽位数。 */
    public int componentSlots() {
        return componentSlots;
    }

    /** 每块提供的速率上限 V_max（AE/t）。 */
    public long maxTransferRate() {
        return maxTransferRate;
    }

    /** 每块提供的输入速率上限 V_in_max（AE/t）。 */
    public long maxInputRate() {
        return maxInputRate;
    }

    // 等级的身份就是注册表里的那一个实例，所以 equals/hashCode 用默认的引用相等——两个内容相同的实例仍是
    // 两个不同的等级，注册表也会给它们两个不同的 id（而这正是 tierMask 位序依赖的东西）。
}
