package io.github.lounode.ae2modularcells.common.config;

/**
 * 一条组件的「值」部分：两个维度各贡献多少。
 *
 * <p>对应关系是「键 = 组件本身、值 = 该组件的衍生数据」：{@code ComponentSpec} 是完整的一条，
 * 其中物品 id 是键、这个 record 是值。拆开是为了让配置界面给它两列，而不是挤成一串长文本。</p>
 *
 * @param capacity 贡献给容量侧的值（公式里的 {@code S_i}）
 * @param transfer 贡献给速率侧的值（公式里的 {@code Sv_i}）
 */
record ComponentValues(long capacity, long transfer) {

    /** 两个维度是否都是 0——这样的条目不贡献任何东西。 */
    boolean isEmpty() {
        return capacity <= 0L && transfer <= 0L;
    }

    /** 沿用 {@code 容量:速率} 的文本形，与配置文件里的写法一致。 */
    @Override
    public String toString() {
        return capacity + ":" + transfer;
    }
}
