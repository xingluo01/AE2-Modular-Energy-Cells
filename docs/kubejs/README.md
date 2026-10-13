# 用 KubeJS 定义新的模块等级

整合包可以直接在脚本里加一个模块等级，不用写 Java。走通一次要准备两样东西：**一个启动脚本**，和**一套资源文件**。

本模组给 `minecraft:block` 加了一个具名 builder 变体（`ae2modularcells:module`），所以入口是**方块**：

```js
// kubejs/startup_scripts/industrial_cell.js
StartupEvents.registry('block', event => {
    event.create('industrial_energy_cell_module', 'ae2modularcells:module')
        .capacity(6400000)
        .transferRate(32000)
        .componentSlots(2)
        .maxTransferRate(512000)
        .maxInputRate(256000)
})
```

这一脚本就完成了「一个等级」的全部注册动作——**等级本身、方块、方块物品、方块实体类型**都从它派生，还会自动挂上 FE 面、组件槽与网格主机能力。脚本不用管时序。

> **必须放在 `startup_scripts/`。** 注册表只在启动期开放，写在 `server_scripts/` 里不会生效。KubeJS 需要**双端都装**：只在服务端注册的等级，客户端解不出那一档。

---

## 一、五个数值

五个方法对应等级对象的五个字段，**都是可选的**——不写就用默认值（即内置「基础」档）。

| 方法 | 含义 | 默认值 |
|---|---|---:|
| `capacity(long)` | 单模块基础容量 A（AE） | 200000 |
| `transferRate(long)` | 单模块基础速率 V（AE/t） | 1000 |
| `componentSlots(int)` | 每块提供的组件槽位数 | 1 |
| `maxTransferRate(long)` | 每块提供的速率上限 V_max（AE/t） | 16000 |
| `maxInputRate(long)` | 每块提供的输入速率上限 V_in_max（AE/t） | 8000 |

对照内置三档（数值都在 `common/ModularCellTiers.java`，这里只是给你定档位时的参照）：

| 等级 | A | 槽位 | V | V_max | V_in_max |
|---|---:|---:|---:|---:|---:|
| 基础 | 200 000 | 1 | 1 000 | 16 000 | 8 000 |
| 致密 | 1 600 000 | 2 | 8 000 | 128 000 | 64 000 |
| 超密 | 12 800 000 | 3 | 64 000 | 1 024 000 | 512 000 |

要算整段的账目，看 `docs/ARCHITECTURE.md` §2：容量与速率都是**逐块求和**，上限也是逐块之和（所以越建越快）；输入速率另有 `f_in = 0.5` 的折扣，且受 `Σ V_in_max` 夹取。

### 等级名（`tierId`）

```js
event.create('industrial_energy_cell_module', 'ae2modularcells:module')
    .tierId('industrial')   // 不写则自动取方块名去掉 _energy_cell_module（方块名不含该后缀时，整个方块名当等级名）
```

等级名是这个档位的身份：**存档里的位号、语言键、贴图文件名都按它走**。名字撞了内置三档或别的模组，会撞上 NeoForge 的重复键；两个脚本方块写同一个 `tierId`，本模组会在启动期报 ERROR 并跳过后面那个（不中断启动）。

---

## 二、资源清单（示例已带全套）

API 只管注册，资源全要自己给。方块 id 是 `kubejs:industrial_energy_cell_module`（脚本里不写命名空间时默认是 `kubejs`），所以下面都放在 `kubejs/assets/kubejs/` 与 `kubejs/data/kubejs/` 下。

| 文件 | 缺了会怎样 |
|---|---|
| `textures/block/industrial_energy_cell.png` 与 `..._light.png` | **紫黑格**。连通模型按「**方块自己的命名空间** + 等级名」找这两张 |
| `blockstates/industrial_energy_cell_module.json` | 模型缺失 |
| `models/block/industrial_energy_cell_module.json` 与 `models/item/...json` | 模型缺失 |
| `data/kubejs/loot_table/blocks/industrial_energy_cell_module.json` | **挖下来什么都不掉，而且不报错** |
| 语言键 `block.kubejs.industrial_energy_cell_module` | 显示原始键名 |

完整可抄的一套在 [`examples/industrial/`](examples/industrial/)——那是把内置基础档那份逐字改过来的，贴图也已带（从基础档复制改名）。

**必须自己准备的只有两张贴图**（`textures/block/<等级名>_energy_cell.png` 与 `..._light.png`，见上表）——`examples/industrial/` 里已带副本。另有五张是连通边框与通用件的共享贴图：`ring_corner` / `ring_side_hor` / `ring_side_ver`、`energy_cell_light_base`、`unit`，模型 JSON 里直接引用本模组的即可（`ae2modularcells:block/...`），不用自己造。

**那两张等级贴图必须与方块同命名空间。** 上表写 `kubejs:` 是因为示例方块在 `kubejs` 命名空间下；若你把方块写成 `mypack:xxx_energy_cell_module`，两张图就要放到 `mypack` 的 `textures/block/` 下——连通模型是按「方块所在的命名空间 + 等级名」去找的，放在别处会紫黑格，而且只会在资源重载日志里留一条「没进方块图集」的告警。

---

## 三、装进整合包

要把这一套发给别人测，直接把 [`examples/industrial/kubejs/`](examples/industrial/kubejs/) 拷过去就行——它是铺平的整合包形态，且贴图已带。[前置模组与验收清单](examples/industrial/README.md)在案例说明里。

要自己从零铺、或只装其中一部分，按下面来：

1. **脚本**放 `kubejs/startup_scripts/` 下。
2. **资源**：`assets/` 与 `data/` 两棵树放进 `kubejs/`（路径同上一节）。
3. **贴图**：`examples/industrial/` 已带副本；自己另做档位时，从 `assets/ae2modularcells/textures/block/` 复制两张改名，放进**自己命名空间**的 `textures/block/` 下。
4. 进游戏，方块应该出现在创造模式的搜索里（**你的方块进你的标签页**，本模组不代管；这一套不发配方，只能创造拿）。

等级与方块套装都是**双端注册表**，所以脚本两边都要装。资源按边分：`assets/` 只客户端要（少了是紫黑格、裸键名），`data/` 里的掉落表走服务端（少了挖下来什么都不掉）。

---

## 四、失败时你会看到什么

这一层的失败大多很安静。引号里是日志片段，可以直接搜。

| 现象 | 原因 |
|---|---|
| 方块能放、能储能，但接线缆没反应、永远不联网 | 套装没进全局清单。**不报错** |
| 挖下来什么都不掉 | 缺掉落表。**不报错** |
| 紫黑格 | 贴图文件名与等级名对不上；或命名空间不对（见上节末尾） |
| 方块根本没出现 | 脚本写在了 `server_scripts/`，或者脚本本身报错了（KubeJS 会打印自己的脚本错误） |
| 启动 WARN `has no module block set; it will never appear in a structure` | 那个等级没有配套方块 |
| 启动 ERROR `More than one scripted module block claims tier ...; skipping it` | 两个脚本方块写了同一个 `tierId`。只跳过后面那个，不中断 |
| 改了 `tierId` 之后紫黑格或模型缺失 | 资源文件名与贴图名还按旧等级名。这条**不会**报错 |

更全的对照表（含 Java 侧那几条）在 [`../INTEGRATION.md`](../INTEGRATION.md) 的「失败时你会看到什么」。

启动期的这些检查只在**服务端**跑（`ModularCellsApi#validateAll` 与 `ModularCellTiers#validateRegistry`）。客户端出问题不会被拦，只表现为显示不对。

---

## 五、几条边界

- **位号是存档协议。** 存档里的 `tierMask` 按固定位号记等级：内置三档写死 0/1/2，你的等级从 3 起按注册名排序分配。位号只依赖「装了哪几个等级」，与加载顺序无关。
- **等级总数上限 32**（掩码是 `int`）。超了启动期记一条 ERROR，真正出问题是在运行期算到那一档时。
- **示例**：[`examples/`](examples/) 下两个——`industrial/` 是带全套资源的完整例子（可直接当测试包分发）；`minimal/` 只是**脚本写法演示**，不含任何资源，单独装只会得到一个没模型、不掉落的方块。
