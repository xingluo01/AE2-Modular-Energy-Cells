package io.github.lounode.ae2modularcells.common.config;

import net.minecraft.resources.ResourceLocation;

import io.github.lounode.ae2modularcells.common.ComponentGain;

/**
 * 配置里声明的一条额外组件：物品 id 加上它给的两个维度的值。
 *
 * <p>这是「额外组件」配置的<b>元素类型</b>——配置项本身是它的列表。之所以不做成「一条字符串」，是为了把
 * 校验挂到类型上：MezzConfig 的界面会用 {@link ComponentSpecSerializer#isValid} 实时判断每一行能不能用，
 * 用 {@link ComponentSpecSerializer#deserialize} 的诊断给出具体原因（物品不存在、两个值都是 0 之类）。
 * 只存字符串的话，这些判断只能推迟到运行期，而那时错误已经静默发生了。</p>
 *
 * @param id       物品的注册名，<b>要带命名空间</b>
 * @param capacity 贡献给容量侧的值（公式里的 S_i）
 * @param transfer 贡献给速率侧的值（公式里的 Sv_i）
 */
public record ComponentSpec(ResourceLocation id, long capacity, long transfer) {

    /** 转成识别路径用的贡献对象。 */
    public ComponentGain gain() {
        return new ComponentGain(capacity, transfer);
    }

    /** 两个维度是否都是 0——这样的条目不贡献任何东西，只会遮蔽内置识别。 */
    public boolean isEmpty() {
        return capacity <= 0L && transfer <= 0L;
    }
}
