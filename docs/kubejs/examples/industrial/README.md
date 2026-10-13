# 完整示例：加一个「工业」等级

演示从零加一个模块等级需要的全套东西。装在整合包里就是把两棵树铺进 `kubejs/`：

```
kubejs/
├── startup_scripts/industrial_cell.js          ← 本目录 startup_scripts/
├── assets/kubejs/                              ← 本目录 assets/kubejs/
│   ├── blockstates/industrial_energy_cell_module.json
│   ├── models/block/industrial_energy_cell_module.json
│   ├── models/item/industrial_energy_cell_module.json
│   ├── lang/zh_cn.json 与 en_us.json
│   └── textures/block/                         ← 两张 png 要你补（见下）
└── data/kubejs/                                ← 本目录 data/kubejs/
    └── loot_table/blocks/industrial_energy_cell_module.json
```

## 两张贴图要自己补

二进制文件不在仓库里。从本模组复制两张改名即可：

```
assets/ae2modularcells/textures/block/basic_energy_cell.png
    →  kubejs/assets/kubejs/textures/block/industrial_energy_cell.png
assets/ae2modularcells/textures/block/basic_energy_cell_light.png
    →  kubejs/assets/kubejs/textures/block/industrial_energy_cell_light.png
```

**名字必须与等级名一致**（这里是 `industrial`），**命名空间必须与方块的一致**（这里是 `kubejs`——连通模型按「方块所在命名空间 + 等级名」找图）。对不上的表现都是紫黑格，而且只会在资源重载日志里留一条告警。自己画也行，两张都是 16×16。

其余五张贴图（`ring_corner` / `ring_side_hor` / `ring_side_ver` / `energy_cell_light_base` / `unit`）是连通边框与通用件的共享件，模型 JSON 里已经直接指向本模组，不用管。

## `lang/` 两份都是片段

每份只有一行。整合包通常已经有自己的同类文件，把这一行并进去；直接覆盖会把你自己模组的键冲掉。

## 想改名

三处要一起改：

1. 脚本里的方块名，以及 `.tierId('...')`（不写 `tierId` 时它会从方块名去掉 `_energy_cell_module` 推出来）；
2. 资源四个文件名（两个模型 + 方块状态 + 掉落表）；
3. 两张贴图名（`<等级名>_energy_cell` / `<等级名>_energy_cell_light`）。

只有第 1 项与第 3 项是**必须一致**的（等级名决定贴图查找名）；资源文件名跟方块名走。改完记得 lang 键也跟着方块名。
