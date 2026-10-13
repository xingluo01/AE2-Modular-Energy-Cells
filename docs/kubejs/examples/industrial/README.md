# 完整示例：加一个「工业」等级

演示从零加一个模块等级需要的全套东西。**这份可以直接当测试包发出去**——把这里的 `kubejs/` 整个拷进整合包根目录就完事，不用摊平、也不用另找贴图。

```
kubejs/                                                ← 拷这个目录
├── startup_scripts/industrial_cell.js
├── assets/kubejs/
│   ├── blockstates/industrial_energy_cell_module.json
│   ├── models/block/industrial_energy_cell_module.json
│   ├── models/item/industrial_energy_cell_module.json
│   ├── lang/zh_cn.json 与 en_us.json
│   └── textures/block/industrial_energy_cell.png 与 ..._light.png
└── data/kubejs/loot_table/blocks/industrial_energy_cell_module.json
```

## 前置

这个包**只有脚本与资源**，跑起来还需要：

- **本模组 jar**（`ae2modularcells`：自己 `./gradlew build`，或向作者要）
- **KubeJS**（实测可用的是 `2101.7.2-build.353`；大版本对不上是这类包最常见的翻车点）
- **AE2** + GuideME（本模组的硬前置，缺了整个模组不加载）

## 怎么装

1. 把 `kubejs/` 拷进整合包根目录（与整合包已有的 `kubejs/` 合并，`lang` 那份按下面的说明处理）。
2. 进游戏。

## 验收清单

- [ ] 创造模式能搜到「工业级能量单元模块」
- [ ] 放进世界后右键能开终端
- [ ] 往槽里插存储组件，终端里的容量数字跟着变
- [ ] 接线缆后能联网——**这一条最容易静默失败**：套装没进清单之类的问题不报错，只表现为那一块永远不联网
- [ ] 挖掉有掉落——缺掉落表时**什么都不掉，而且不报错**

## 两张贴图已经带了

`textures/block/` 下那两张是从本模组的基础档复制改名的，克隆下来就能直接用，不用自己画。

想换成自己的就覆盖它们——**名字必须与等级名一致**（这里是 `industrial`），**命名空间必须与方块的一致**（这里是 `kubejs`：连通模型按「方块所在命名空间 + 等级名」找图）。对不上的表现都是紫黑格，而且只会在资源重载日志里留一条告警。

其余五张（`ring_corner` / `ring_side_hor` / `ring_side_ver` / `energy_cell_light_base` / `unit`）是连通边框与通用件的共享贴图，模型 JSON 里已经直接指向本模组，不用管。

## `lang/` 两份都是片段

每份只有一行。整合包通常已经有自己的 `zh_cn.json` 与 `en_us.json`，把这一行并进去；直接覆盖会把你其它模组的键冲掉。

## 想改名

三处要一起改：

1. 脚本里的方块名，以及 `.tierId('...')`（不写 `tierId` 时它会从方块名去掉 `_energy_cell_module` 推出来）；
2. 资源的四个文件名（两个模型 + 方块状态 + 掉落表）——跟**方块名**走；
3. 两张贴图名（`<等级名>_energy_cell` 与 `<等级名>_energy_cell_light`）——跟**等级名**走。

只有第 3 项与第 1 项里的等级名必须一致（等级名决定贴图查找名）。改完记得 `lang` 键也跟着方块名。
