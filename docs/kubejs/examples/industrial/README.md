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
- [ ] 挖掉有掉落（本体与里面的组件都要掉）——这两样由模组保证，**不需要任何数据包**

## 两张贴图已经带了

`textures/block/` 那两张是从本模组基础档复制改名的，直接用即可。换成自己的就覆盖它们。

**名字跟等级名、命名空间跟方块**（连通模型按「方块所在命名空间 + 等级名」找图），对不上就是紫黑格。其余五张是共享件，模型 JSON 里已指向本模组，不用管。

## `lang/` 两份都是片段

每份只有一行，并进整合包已有的同类文件；直接覆盖会把其它模组的键冲掉。

## 想改名

三处一起改：脚本里的方块名与 `tierId(...)`；资源的四个文件名（跟**方块名**走）；两张贴图名（跟**等级名**走）。改完记得 `lang` 键也跟上。
