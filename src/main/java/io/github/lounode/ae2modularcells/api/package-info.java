/**
 * 对外稳定面：给第三方模组用的类型。
 *
 * <p>这个包里的包路径与签名改动等于破坏兼容——改之前先在本仓 {@code CHANGELOG.md} 记一笔（与姊妹项目
 * AE2 Pattern Disk 同规矩）。</p>
 *
 * <p>规划面（详见 {@code docs/ARCHITECTURE.md}）：<b>只读</b>查询某段模块化能源结构的位置、等级、边长、
 * 总容量与当前能量，以及「这段结构的核心在哪」；外加一个注册口，允许邻居把自己的组件加成口径接进来。
 * 写侧（改槽位、加能量）不进 api 包。</p>
 */
package io.github.lounode.ae2modularcells.api;
