package io.github.lounode.ae2modularcells.common;

import org.jetbrains.annotations.Nullable;

/**
 * 可插入模块组件槽的存储组件档位与数值表。
 *
 * <p>槽位里放的不是本模组自制的东西，而是现成的存储组件：{@code 1k} ~ {@code 256k} 来自 AE2 本体，
 * {@code 1m} ~ {@code 256m} 来自 MEGA Cells（它缺席时这几档自然没人提供，槽位照常工作）。每一档的
 * {@link #capacity()} 同时参与两条公式——容量侧按 {@code k} 折扣计入、速率侧按 {@code kv} 折扣计入，
 * 见 {@link io.github.lounode.ae2modularcells.common.logic.CellMath}。</p>
 *
 * <p>为什么把数值表钉在这里：折扣要乘的是「组件本身的值」，而识别组件靠物品（AE2 与 MEGA Cells 各自的
 * 物品类）。两边一旦对不上（模组改了数值、加了新档），公式还在按旧表算。所以档位与数值只此一处，
 * 集成层负责把物品映射到这里的档位。</p>
 */
public enum StorageComponentTier {

    /** AE2 的 1k 存储组件。 */
    STORAGE_1K("1k", 1024L),

    /** AE2 的 4k 存储组件。 */
    STORAGE_4K("4k", 4096L),

    /** AE2 的 16k 存储组件。 */
    STORAGE_16K("16k", 16_384L),

    /** AE2 的 64k 存储组件。 */
    STORAGE_64K("64k", 65_536L),

    /** AE2 的 256k 存储组件。 */
    STORAGE_256K("256k", 262_144L),

    /** MEGA Cells 的 1m 存储组件。 */
    STORAGE_1M("1m", 1_048_576L),

    /** MEGA Cells 的 4m 存储组件。 */
    STORAGE_4M("4m", 4_194_304L),

    /** MEGA Cells 的 16m 存储组件。 */
    STORAGE_16M("16m", 16_777_216L),

    /** MEGA Cells 的 64m 存储组件。 */
    STORAGE_64M("64m", 67_108_864L),

    /** MEGA Cells 的 256m 存储组件。 */
    STORAGE_256M("256m", 268_435_456L);

    private final String id;
    private final long capacity;

    StorageComponentTier(String id, long capacity) {
        this.id = id;
        this.capacity = capacity;
    }

    /** 档位短 id（{@code 1k} / {@code 4k} / … / {@code 256m}），也是语言键与配置名用的后缀。 */
    public String id() {
        return id;
    }

    /** 该档存储组件的容量值（AE）：容量侧乘 {@code k}、速率侧乘 {@code kv} 计入模块公式。 */
    public long capacity() {
        return capacity;
    }

    /**
     * 按短 id 找档位；找不到返回 {@code null}。
     *
     * <p>存档里出现陌生档位（模组版本回退、存档来自别的分支）时用它兜底——调用方按「这个组件不参与
     * 计算」处理即可，不要抛异常：一件物品的问题不该放大成整段结构的加载失败。</p>
     */
    @Nullable
    public static StorageComponentTier byId(@Nullable String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        for (var tier : values()) {
            if (tier.id.equals(id)) {
                return tier;
            }
        }
        return null;
    }
}
