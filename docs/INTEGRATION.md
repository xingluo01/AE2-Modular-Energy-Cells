# 兼容层与接入 API

面向两类读者：想让自己的模组或整合包脚本接进本模组的人，以及要给本模组加邻居适配的维护者。设计动机与内部机制在 [`ARCHITECTURE.md`](ARCHITECTURE.md) §5，本篇只讲怎么用、以及失败时你会看到什么。

## 一、兼容层：本模组怎么适配邻居

### 三条铁律

1. 邻居的类型名不得出现在总是加载的类里。缺那个模组时必须在加载阶段安全降级。
2. 能只比字符串就只比字符串。组件识别整张表只用来拼 `namespace:path`。
3. `integration/<邻居>` 只写那个邻居的适配，并由邻居自己来加载它。

### 四种适配形态

| 形态 | 用在谁身上 | 怎么隔离 |
|---|---|---|
| 按 mod id 门禁，不引用其类 | MEGA Cells、AppFlux、OMNI Cells | 只比注册名字符串，识别口径交给配置表 |
| 注解驱动 | Jade | `@WailaPlugin` |
| ServiceLoader | AE2 的工具提示 | `META-INF/services/...TooltipProvider` |
| 插件清单 | KubeJS | `resources/kubejs.plugins.txt` 点名 |

前三种是本模组去适配别人；KubeJS 那条反过来，是本模组给别人开放入口——所以它是唯一带完整接入 API 的集成。

### 现有邻居

| 邻居 | 可选性 | 提供什么 |
|---|---|---|
| MEGA Cells | 可选，不引用其类 | `cell_component_1m ~ 256m` |
| AppFlux | 可选，不引用其类 | `core_1k ~ 256m` 能量存储组件 |
| AE2 OMNI Cells | 可选，不引用其类 | 三组组件，修正倍率 0.75 / 1.5 / 3 |
| JEI / EMI | 可选，客户端 | 配方查看 |
| Jade | 可选，客户端 | 结构浮层 |
| KubeJS | 可选，双端 | 脚本可定义新档位（见下） |

存储组件的识别只看配置表（`componentGains`）。`common/BuiltInComponents` 只是内置清单，服务器启动时把配置里还没有的写进去当初始值，之后以文件为准。

### 加一个邻居

新的存储组件来源做两件事：往 `common/BuiltInComponents` 的清单加一条（邻居 mod id + 注册名前缀 + 它给哪个维度），再在 `ARCHITECTURE.md` §5 的邻居表里记一行。清单在下次启动时自动补进配置。

代价：换来「玩家可见可改」，失去「跟着上游走」——邻居改了字节数，我们不会自己跟。

## 二、接入 API

### 稳定面

`io.github.lounode.ae2modularcells.api` 包。这个包的路径与签名改动等于破坏兼容。

等级本身不在这个包里，它的注册表键是 `ModularCellTiers.TIER_REGISTRY_KEY`（`ae2modularcells:energy_cell_tier`）。

### Java：两步

```java
// 1) 注册等级（自己的 mod id 作命名空间）
public static final DeferredRegister<EnergyCellTier> TIERS =
        DeferredRegister.create(ModularCellTiers.TIER_REGISTRY_KEY, "yourmod");
public static final DeferredHolder<EnergyCellTier, EnergyCellTier> MY_TIER =
        TIERS.register("my_tier", () -> new EnergyCellTier("my_tier", 400_000L, 2_000L, 2, 32_000L, 16_000L));

// 2) 注册它配套的方块套装
public static final DeferredModuleBlockSet MY_SET = ModularCellsApi.registerEnergyCellModule(
        BLOCKS, ITEMS, BLOCK_ENTITIES, MY_TIER.get());
```

两件注册器都要自己挂到模组总线（`TIERS.register(modBus)` 等）。

`EnergyCellTier` 构造参数依次是：注册名、单模块基础容量（AE）、基础速率（AE/t）、组件槽位数、每块速率上限（AE/t）、每块输入上限（AE/t）。**注册名必须与 `DeferredRegister` 里的路径一致**，否则启动期报错。

`registerEnergyCellModule` 替你做三件事：三者同名注册、把方块实体类型回填给方块、把套装塞进全局清单。

**`DeferredModuleBlockSet` 是三个句柄**（`block()` / `item()` / `blockEntity()`）。注册完成前 `get()` 会抛——要实例走 `ModularCellsApi.allModuleBlockSets()`，或先用 `isBound()` 问一句。

**不走 `DeferredRegister`** 的写法可以用 `ModularCellsApi.registerModuleBlockSet(tier, id, block, item, blockEntity)` 直接交实例。这条路要自己调 `block.setBlockEntity(EnergyCellModuleBlockEntity.class, type, null, null)` 回填——漏了会让扳手拆解与导出设置失效，**没有任何报错**，启动期的检查也查不出来。

**三条能力由本模组替你挂**：FE 输入面、组件槽的物品面、网格主机能力。前提是套装进过上面两个入口之一。最后一条尤其要命：AE2 只在它自己的类型表上批量注册它，第三方不补就接不上线缆——**表现只是那一块永远不联网，一句错都没有**。

### 脚本：一步（KubeJS）

```js
StartupEvents.registry('block', event => {
    event.create('my_tier_energy_cell_module', 'ae2modularcells:module')
        .capacity(400000).transferRate(2000).componentSlots(2)
        .maxTransferRate(32000).maxInputRate(16000)
})
```

两个脚本方块写同一个 `tierId` 会被拦下。默认值表、资源清单与脚本专属的失败现象见 [`kubejs/README.md`](kubejs/README.md)。只支持这一种写法——首版那种 `registry('ae2modularcells:energy_cell_tier', ...)` 已作废。

### 要自备的资源

| 文件 | 缺了会怎样 | 哪边要 |
|---|---|---|
| `textures/block/<等级名>_energy_cell.png` 与 `..._light.png` | 紫黑格。按「方块自己的命名空间 + 等级名」找 | 客户端 |
| `blockstates/<方块名>.json` 与 `models/block\|item/<方块名>.json` | 模型缺失。变体是 `fill=0..4` × `formed=false/true` | 客户端 |
| 语言键 `block.<命名空间>.<方块名>` | 显示原始键名 | 客户端 |

掉落不用管——方块本体与组件都由模组自己给，挖掘、爆炸、扳手拆解与第三方工具的批量破坏都覆盖到了。爆炸是必定掉落（原先掉落表里 `survives_explosion` 的概率口径随之取消）。

### 失败时你会看到什么

引号里是日志片段，能直接搜。

| 现象 | 原因 |
|---|---|
| 能放能储能，但接线缆没反应、永远不联网 | 套装没进全局清单。**不会报错** |
| 紫黑格 | 贴图文件名或命名空间不对 |
| 启动报 `has no registered block` / `has no registered item` / `has no registered block entity type` | 套装三件里某一件没进注册表 |
| 启动 WARN `has no module block set; it will never appear in a structure` | 那个等级没有配套方块 |
| 启动抛 `not in the energy cell tier registry` | 套装引用的等级没注册 |
| 启动抛 `more than one module block set` | 同一个等级被注册了两套方块 |
| 启动抛 `must reference the same tier instance` | 方块与套装用了两个等价但不同的等级实例 |
| 启动抛 `registered as ... but reports id ...; the two must match` | 等级的注册名与它自称的 id 不一致 |
| 启动抛 `is used by more than one tier` | 两个等级的 id 重复 |
| 启动 ERROR `More than one scripted module block claims tier ...` | 两个脚本方块写了同一个 `tierId`。只跳过后面那个 |
| 启动 ERROR `resolved to bit ..., beyond the ...-bit tier mask` | 等级总数超过 32 |

这些检查在服务器起的最后一步跑，**只在服务端**——客户端出问题不会被拦，只会表现为显示不对。

### 边界

**等级与方块套装是双端都要满足的。** 只在服务端注册的等级，客户端解不出那一档。

**位号是存档协议，不要动内置三档那三个数。** 存档里的 `tierMask` 按固定位号记等级：内置三档写死 0/1/2，你自己的等级从 3 起按等级 id 排序分配。位号只依赖「装了哪几个等级」，与加载顺序无关。上限 32（掩码是 `int`）。

**本模组不碰你方块的默认状态。** 创造模式标签页只收本模组命名空间的物品，你的方块进你的标签页。
