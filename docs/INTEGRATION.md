# 兼容层与接入 API

面向两类读者：想让自己的模组或整合包脚本**接进本模组**的人，以及要给本模组**加一个邻居适配**的维护者。
设计动机与内部机制在 [`ARCHITECTURE.md`](ARCHITECTURE.md) §5，本篇只讲怎么用、以及失败时你会看到什么。

---

## 一、兼容层：本模组怎么适配邻居

### 三条铁律

1. **邻居的类型名不得出现在总是加载的类里。** 缺那个模组时必须在加载阶段安全降级，不能等到类加载才炸。
2. **能只比字符串就只比字符串。** 组件识别整张表只用来拼 `namespace:path`，不 import 任何邻居的类——邻居缺席时它那些条目只是永远不会被用到。
3. **`integration/<邻居>` 只写那个邻居的适配。** 需要真正引用邻居类型的代码全放这里，并由邻居自己来加载它。

### 四种适配形态

| 形态 | 用在谁身上 | 怎么隔离 |
|---|---|---|
| 按 mod id 门禁，不引用其类 | MEGA Cells、AppFlux、OMNI Cells | 只比注册名字符串，识别口径全交给配置表 |
| 注解驱动 | Jade | `@WailaPlugin`，全仓没有总是加载的类引用它 |
| ServiceLoader | AE2 的工具提示 | `META-INF/services/...TooltipProvider`，走上游 SPI |
| 插件清单 | KubeJS | `resources/kubejs.plugins.txt` 点名，KubeJS 不在场就没人读这个文件 |

前三种是**本模组去适配别人**；KubeJS 那条反过来，是本模组**给别人开放入口**——所以它是唯一一个带完整接入 API 的集成。

### 现有邻居

| 邻居 | 可选性 | 提供什么 |
|---|---|---|
| MEGA Cells | 可选，不引用其类 | `cell_component_1m ~ 256m`，槽位高档组件的来源 |
| AppFlux | 可选，不引用其类 | `core_1k ~ 256m` ME 能量存储组件，十档全覆盖 |
| AE2 OMNI Cells | 可选，不引用其类 | 三组混合类型组件 |
| JEI / EMI | 可选，客户端 | 配方查看（开发期 `-Pemi` 可切 EMI） |
| Jade | 可选，客户端 | 结构等级、尺寸、当前/总容量浮层 |
| KubeJS | 可选，双端 | 反向入口：脚本可定义新档位（见第二节） |

存储组件的识别**只看配置表**（`componentGains`）。代码里的 `common/BuiltInComponents` 只是内置清单，服务器启动时把配置里还没有的写进去当初始值，之后以文件为准。

### 加一个邻居要走哪几步

新的存储组件来源最常见，做两件事就够：往 `common/BuiltInComponents` 的清单加一条（邻居 mod id + 注册名前缀 + 它给哪个维度），再在 `docs/ARCHITECTURE.md` §5 的邻居表里记一行。清单在下次启动时自动补进配置，不必手动改存档。

代价要说清：这套做法换来「玩家可见可改」，失去的是「跟着上游走」——邻居改了字节数，我们不会自己跟。

---

## 二、接入 API：别人怎么接本模组

### 稳定面在哪

`io.github.lounode.ae2modularcells.api` 包。**这个包里的包路径与签名改动等于破坏兼容**，改之前要往 `CHANGELOG.md` 记一笔。

等级本身不在这个包里，它的注册表键是 `ModularCellTiers.TIER_REGISTRY_KEY`（`ae2modularcells:energy_cell_tier`）。

### Java：两步

```java
// 1) 注册一个等级（自己的 mod id 作命名空间）
public static final DeferredRegister<EnergyCellTier> TIERS =
        DeferredRegister.create(ModularCellTiers.TIER_REGISTRY_KEY, "yourmod");
public static final DeferredHolder<EnergyCellTier, EnergyCellTier> MY_TIER =
        TIERS.register("my_tier", () -> new EnergyCellTier("my_tier", 400_000L, 2_000L, 2, 32_000L, 16_000L));

// 2) 注册它配套的方块套装（方块 + 方块物品 + 方块实体类型）
public static final DeferredModuleBlockSet MY_SET = ModularCellsApi.registerEnergyCellModule(
        BLOCKS, ITEMS, BLOCK_ENTITIES, MY_TIER.get());
```

两件注册器都要挂到模组总线（`TIERS.register(modBus)` 等），本模组不代管。

`EnergyCellTier` 构造参数依次是：注册名（也是语言键与贴图名用的那个短名）、单模块基础容量（AE）、基础速率（AE/t）、组件槽位数、每块速率上限（AE/t）、每块输入速率上限（AE/t）。**注册名必须与 `DeferredRegister` 里用的路径一致**，两者不一致会在启动期报错。

`registerEnergyCellModule` 替你做的三件事：三者同名注册、把方块实体类型回填给方块（方块先注册、类型后注册，AE 的方块基类靠它做「拆下来给哪个物品」这类查询）、把套装塞进全局清单。

**拿到的 `DeferredModuleBlockSet` 是三个句柄**（`block()` / `item()` / `blockEntity()`）。它在注册完成前 `get()` 会抛——要实例就走 `ModularCellsApi.allModuleBlockSets()`（一张合并了所有来源的清单，元素是 `ModuleBlockSet`，装的是真对象），或者先用 `isBound()` 问一句。

如果你**不走 `DeferredRegister`**（自己调 `event.register` 的那类写法），可以用 `ModularCellsApi.registerModuleBlockSet(tier, id, block, item, blockEntity)` 直接交实例。**这条路要自己把方块实体类型回填给方块**——调 `block.setBlockEntity(EnergyCellModuleBlockEntity.class, type, null, null)`，漏了会让扳手拆解与导出设置失效，而且没有任何报错，启动期的检查也查不出这次回填。

**那三条能力由本模组替你挂**：FE 输入面、组件槽的物品面、网格主机能力。前提是套装进过上面两个入口之一。网格主机能力那条尤其要命：AE2 只在它自己的类型表上批量注册它，第三方不补就接不上线缆——**而表现只是那一块永远不联网，一句错都没有**。

### 脚本：一步（KubeJS）

```js
// startup_scripts/your_file.js
StartupEvents.registry('block', event => {
    event.create('my_tier_energy_cell_module', 'ae2modularcells:module')
        .capacity(400000)
        .transferRate(2000)
        .componentSlots(2)
        .maxTransferRate(32000)
        .maxInputRate(16000)
})
```

等级名默认取方块名去掉 `_energy_cell_module` 后缀，可以用 `tierId('...')` 改。等级名与内置三档或别人的等级撞名会撞上 NeoForge 的重复键；两个脚本方块写同一个 `tierId` 则会被本模组拦下来并让你改（见下面的对照表）。

**入口为什么是方块而不是等级**，这件事踩过坑，值得记下来：KubeJS 的落盘只认「当前正在注册的那个注册表」，而 `minecraft:block` 这类原版注册表早于本模组的自建注册表。第一版把入口放在等级上，实测结果是**等级建出来了、方块一个都没有，日志里没有任何错**。改以方块为入口才通——方块最早，物品与方块实体类型都比它晚。等级则由本模组在它自己那一次 `RegisterEvent` 里补注册，脚本侧不用管时序。

只支持这一种写法。若你见到 `StartupEvents.registry('ae2modularcells:energy_cell_tier', ...)`，那是首版的做法，已作废——那种类型没有对应的 builder，KubeJS 会照它自己的规矩报错。

### 你要自备的资源

API 不代管资源，四样都要自己给：

| 文件 | 缺了会怎样 | 哪边要 |
|---|---|---|
| `textures/block/<tierId>_energy_cell.png` 与 `<tierId>_energy_cell_light.png` | 紫黑格。连通模型按等级 id 找这两张，名字不对就找不到 | 客户端 |
| `blockstates/<方块名>.json` 与 `models/block|item/<方块名>.json` | 模型缺失。形状变体是 `fill=0..4`，内置三档那几份可以直接照抄 | 客户端 |
| `data/<命名空间>/loot_table/blocks/<方块名>.json` | 挖下来什么都不掉，而且不报错 | 服务端（数据包） |
| 语言键 `block.<命名空间>.<方块名>` | 显示原始键名 | 客户端 |

### 失败时你会看到什么

这一层的失败大多很安静，所以列一份对照。引号里是日志消息的片段，能直接搜。

| 现象 | 原因 |
|---|---|
| 方块能放能储能，但接线缆没反应、那一块永远不联网 | 套装没进全局清单，网格主机能力没挂上。**不会报错** |
| 方块挖了什么都不掉 | 缺掉落表。**不会报错** |
| 紫黑格 | 贴图文件名与等级 id 对不上 |
| 启动时报 `has no registered block` / `has no registered item` / `has no registered block entity type` | 套装的三件里某一件没进注册表，注册路径没走完 |
| 启动时 WARN `has no module block set; it will never appear in a structure` | 那个等级没有配套方块。脚本把方块名写错时常见 |
| 启动时抛 `not in the energy cell tier registry` | 套装引用的等级没注册 |
| 启动时抛 `more than one module block set` | 同一个等级被注册了两套方块 |
| 启动时抛 `must reference the same tier instance` | 方块与套装用了两个等价但不同的等级实例，必须共用同一个 |
| 启动时抛 `registered as ... but reports id ...; the two must match` | 等级的注册名与它自称的 `id` 不一致 |
| 启动时抛 `is used by more than one tier` | 两个等级的 `id` 重复。id 是存档与物品 id 的标识，必须唯一 |
| 启动时 ERROR `More than one scripted module block claims tier ...; skipping it` | 两个脚本方块写了同一个 `tierId`。这一条只跳过那个等级，不中断启动 |
| 启动时 ERROR `resolved to bit ..., beyond the ...-bit tier mask` | 等级总数超过 32。这条只记日志；真正抛异常是在运行期算到那一档的位号时（结构重算路径上） |

启动期这些检查在服务器起的最后一步跑（`ModularCellsApi#validateAll` 与 `ModularCellTiers#validateRegistry`）。**它们只在服务端跑**——客户端那边出问题不会被拦，只会表现为显示不对（紫黑格、裸键名这类）。

### 边界

**等级与方块套装是双端都要满足的。** 只在服务端注册的等级，客户端解不出那一档——终端里的结构快照按位号配对。资源那四样按上表分边即可。

**位号是存档协议，不要动内置三档那三个数。** 存档里的 `tierMask` 按固定位号记等级：内置三档写死 0/1/2（与它们曾经是枚举时的 `ordinal()` 逐位相同，所以旧存档读回来仍然认得对），你自己的等级从 3 起按注册名排序分配。位号只依赖「装了哪几个等级」，与加载顺序无关。

破坏性改动是把那个写死的映射改掉，或者换掉内置实例——只调注册顺序不影响位号。等级总数上限 32（掩码是 `int`）。

**本模组不碰你方块的默认状态。** 创造模式标签页只收本模组命名空间的物品，你的方块进你的标签页。
