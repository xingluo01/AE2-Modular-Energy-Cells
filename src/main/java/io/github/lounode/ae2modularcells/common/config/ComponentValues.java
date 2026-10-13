package io.github.lounode.ae2modularcells.common.config;

/**
 * 一条组件的「值」部分：两个维度各贡献多少，以及各自的修正倍率。
 *
 * <p>对应关系是「键 = 组件本身、值 = 该组件的衍生数据」：{@code ComponentSpec} 是完整的一条，
 * 其中物品 id 是键、这个 record 是值。拆开是为了让配置界面给它两列，而不是挤成一串长文本。</p>
 *
 * @param capacity           贡献给容量侧的值（公式里的 {@code S_i}）
 * @param transfer           贡献给速率侧的值（公式里的 {@code Sv_i}）
 * @param capacityMultiplier 容量修正倍率 {@code m_i}，{@code 1.0} 表示不修正
 * @param transferMultiplier 速率修正倍率 {@code mv_i}，{@code 1.0} 表示不修正
 */
record ComponentValues(long capacity, long transfer, double capacityMultiplier, double transferMultiplier) {

    /** 倍率不修正时的取值。 */
    static final double NO_MODIFIER = 1.0D;

    /** 文本形沿用配置文件的写法：{@code 容量:速率}，倍率不是 1.0 时才往后追加。 */
    @Override
    public String toString() {
        String head = capacity + ":" + transfer;
        boolean noCapacityModifier = capacityMultiplier == NO_MODIFIER;
        boolean noTransferModifier = transferMultiplier == NO_MODIFIER;
        if (noCapacityModifier && noTransferModifier) {
            return head;
        }
        if (noTransferModifier) {
            return head + ":" + capacityMultiplier;
        }
        return head + ":" + capacityMultiplier + ":" + transferMultiplier;
    }
}
