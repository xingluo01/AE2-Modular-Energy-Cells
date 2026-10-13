# 用 KubeJS 定义新模块等级

整合包可以在脚本里加一个模块等级，不用写 Java。要一个启动脚本，加一套资源。

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

这一脚本就建好了等级、方块、方块物品与方块实体类型，并接上 FE 面、组件槽与网格主机能力。

* 必须放 `startup_scripts/`，写在 `server_scripts/` 里不生效。
* 双端都要装 KubeJS 与这份脚本。只在服务端注册的等级，客户端解不出那一档。

## 五个数值

| 方法 | 含义 | 默认值 |
|---|---|---:|
| `capacity(long)` | 单模块基础容量 A（AE） | 200000 |
| `transferRate(long)` | 单模块基础速率 V（AE/t） | 1000 |
| `componentSlots(int)` | 每块提供的组件槽位数 | 1 |
| `maxTransferRate(long)` | 每块提供的速率上限 V_max（AE/t） | 16000 |
| `maxInputRate(long)` | 每块提供的输入上限 V_in_max（AE/t） | 8000 |

都是可选的，不写就用默认值（等同基础档）。内置三档的值拿来定档位参照：

| 等级 | A | 槽位 | V | V_max | V_in_max |
|---|---:|---:|---:|---:|---:|
| 基础 | 200 000 | 1 | 1 000 | 16 000 | 8 000 |
| 致密 | 1 600 000 | 2 | 8 000 | 128 000 | 64 000 |
| 超密 | 12 800 000 | 3 | 64 000 | 1 024 000 | 512 000 |

整段账目按 `docs/ARCHITECTURE.md` §2 的公式现算：容量与速率逐块求和，两个上限也是逐块之和。

### 等级名

默认取方块名去掉 `_energy_cell_module`（方块名不含该后缀时，整个方块名当等级名），可以用 `tierId('...')` 改。

等级名是这个档位的身份——存档位号、语言键、贴图文件名都按它走。撞上内置三档或别人的等级会撞 NeoForge 的重复键；两个脚本方块写同一个 `tierId`，本模组会在启动期报 ERROR 并跳过后面那个。

## 要自备的资源

方块 id 是 `<命名空间>:<方块名>`（脚本不写命名空间时是 `kubejs`），资源放 `kubejs/assets/<命名空间>/` 下：

| 文件 | 缺了会怎样 |
|---|---|
| `textures/block/<等级名>_energy_cell.png` 与 `..._light.png` | 紫黑格 |
| `blockstates/<方块名>.json` | 模型缺失 |
| `models/block/<方块名>.json` 与 `models/item/<方块名>.json` | 模型缺失 |
| 语言键 `block.<命名空间>.<方块名>` | 显示原始键名 |

两张贴图必须与方块同命名空间——连通模型按「方块所在命名空间 + 等级名」找它们。

另外五张（`ring_corner` / `ring_side_hor` / `ring_side_ver` / `energy_cell_light_base` / `unit`）是共享件，模型 JSON 里直接引用本模组的即可。

掉落不用管：方块本体与组件都由模组自己给。

## 装进整合包

1. 把 [`examples/industrial/kubejs/`](examples/industrial/kubejs/) 整个拷进整合包根目录（它已经铺成整合包形态，贴图也带了）。
2. 进游戏，创造模式搜索你的方块。这一套不发配方，只能创造拿。

自己从零铺：脚本放 `kubejs/startup_scripts/`，资源放 `kubejs/assets/<命名空间>/`。

前置模组与验收清单见[案例说明](examples/industrial/README.md)。

## 失败时你会看到什么

| 现象 | 原因 |
|---|---|
| 方块根本没出现 | 脚本写在了 `server_scripts/`，或脚本本身报错了（KubeJS 会打印自己的脚本错误） |
| 紫黑格 | 贴图名与等级名对不上，或命名空间不对 |
| 改了 `tierId` 之后紫黑格或模型缺失 | 资源文件名与贴图名还按旧等级名。不会报错 |
| 启动 WARN `has no module block set; ...` | 那个等级没有配套方块 |
| 启动 ERROR `More than one scripted module block claims tier ...` | 两个脚本方块写了同一个 `tierId`。只跳过后面那个 |

启动期的检查只在服务端跑。客户端出问题不会被拦，只表现为显示不对。更全的对照表（含 Java 侧那几条）在 [`../INTEGRATION.md`](../INTEGRATION.md)。

## 边界

* 位号是存档协议：内置三档写死 0/1/2，你的等级从 3 起按等级 id 排序分配。
* 等级总数上限 32。
* 创造模式标签页只收本模组命名空间的物品，你的方块进你的标签页。

## 示例

* [`examples/industrial/`](examples/industrial/)：带全套资源的完整例子，可直接当测试包分发。
* [`examples/minimal/`](examples/minimal/)：只演示脚本写法，不含资源，单独装会得到一个没有模型的方块。
