# AE2 模块化能源元件 架构手册

给接手这套代码的人：这页讲**代码怎么组织、东西放在哪、改哪里**。

玩法说明在 `../README.md`（英文，商店页与 GitHub 首页用），未完成与已知欠账在 `TODO.md`，历次改动在 `../CHANGELOG.md`。本手册不重复它们。

版本基线：NeoForge 21.1.241 / MC 1.21.1 / AE2 19.2.18 / Java 21。

> 本手册写于骨架阶段：包地图、数据流与协议面是**规划**，实现阶段按 `TODO.md` 的顺序把它们长出来。凡标注「规划」的段落，实现时以代码为准并回头修正这里。

---

## 1. 源集与包地图

### 源集

| 源集 | 位置 | 说明 |
|---|---|---|
| `main` | `src/main/java` | 公共与服务端。方块、方块实体、菜单、结构与能量逻辑 |
| `client` | `src/client/java` | 只在客户端加载。`build.gradle` 刻意把它与 main 分开，让公共代码无法误引客户端类——这不是洁癖，是编译期防线 |
| `main/templates` | `src/main/templates` | `neoforge.mods.toml` 模板，构建期展开到 `build/generated/sources/modMetadata` |
| `generated` | `src/generated/resources` | 数据生成的输出目录（`build.gradle` 已把 `--output` 指向它并接进 main 资源）。当前为空 |

**没有测试源集**：与姊妹项目 AE2 Pattern Disk 同口径——验证靠编译与实机（`runClient` / `runServer`）。

### 包地图

> 现状：`common.block`（`EnergyCellModuleBlock`）、`common.block.entity`（模块方块实体、能量池与连接状态数据键）、`common.logic`（`MultiblockScanner` / `StructureStats` / `CellMath`）、`common.menu`（管理终端菜单与槽位语义）已落地；`common` 根包还有组件识别（`StorageComponents`）、内置清单（`BuiltInComponents`）、`ComponentGain` / `ComponentSpec` / `StorageComponentTier` 数值与 `common.config`（`ModularCellConfig`）；`integration/ae2` 是 Jade 浮层（走 AE2 的 igtooltip SPI）。`common.energy` 仍是空的——能量池与 `IAEPowerStorage` 实现在 `common.block.entity`（方块实体继承 AE2 的 `AENetworkedPoweredBlockEntity`）。`api` 只有 `package-info`——对外稳定面要等结构查询接口定下来再写。客户端的连接渲染与屏幕在 `client` 源集的 `client/render` 与 `client/gui`。

| 包 | 职责 |
|---|---|
| （根包） | 模组入口 `AE2ModularCells`、注册表 `AEModularCellsRegistries` |
| `api` | **对外稳定面**。第三方接入的两个入口：注册等级（`ModularCellTiers#TIER_REGISTRY_KEY`）与注册方块套装（`ModularCellsApi#registerEnergyCellModule` → `ModuleBlockSet`）；另有只读查询结构状态（位置 → 段、等级、边长、总容量、当前能量）的接口与数据对象 |
| `common` | 公共与服务端 |
| `common.block` | 三个等级的模块方块（成型/未成型的外观属性也在这里） |
| `common.block.entity` | 模块方块实体：组件槽内容、本方块加成、**核心位置引用**；只有核心 tick |
| `common.logic` | 结构检测（`MultiblockScanner`）、容量汇总（`StructureStats`）、组件记账。不碰菜单、屏幕与世界 tick |
| `common.energy` | （空）能量池与网格接入实际落在 `common.block.entity`：方块实体继承 AE2 的 `AENetworkedPoweredBlockEntity`，储能就存在它身上 |
| `common.menu` | 菜单及其逻辑（组件槽、总览数据的同步） |
| `common.config` | 模组配置：可配的三个公式系数与 `componentGains` 额外组件表（MezzConfig 托管，服务端归属、游戏内可编辑） |
| `common.item` | 存储组件物品与方块物品 |
| `integration/…` | 邻居适配，一个包一个邻居（客户端侧在 `client/integration/…`） |
| `client` | 客户端源集根：屏幕、面板、渲染 |

### 分层规矩

- `api` 只放第三方会写的类型（接口、数据对象、门面）。它不反向依赖 `common/menu` 或屏幕。
- `common/logic` 里放「能单独读懂、能单独讲清」的东西：**结构检测**与**容量公式**是这一层的核心。回调优先——构造参数接 `Supplier` / `Runnable`，不主动抓菜单服务，也不去读世界：检测函数收「方块位置 → 是不是模块方块」这样一个查询回调，就能单独讲清、也方便将来补测试。
- `common/menu` 里放「必须知道菜单服务」的东西。判据：一段代码要不要用 `getPlayer()`、`sendClientAction`、`notify*`、`sendPacketToClient`？要，就留在菜单。
- `integration/<邻居>` 只写那个邻居的适配，**邻居的类型不得漏进总是加载的类**（红线，见 §5）。

---

## 2. 核心概念

### 2.1 等级（`EnergyCellTier`）

三个内置等级：基础 / 致密 / 超密。它们各自是一个**注册表元素**（注册表键 `ae2modularcells:energy_cell_tier`），不是枚举——其他模组可以用自己的命名空间注册新等级，接入方式见 §5.1。公式要用的五个数值现在是等级对象的字段（`common/EnergyCellTier.java`）：基础容量 A、基础速率 V、组件槽位 C_tier、每块速率上限 V_max、每块输入速率上限 V_in_max（整段上限 = 各块之和）。

| 等级 | A 基础容量 | 槽位 C_tier | V 基础速率 | V_max 每块速率上限 | V_in_max 每块输入上限 |
|---|---:|---:|---:|---:|---:|
| 基础 | 200 000 AE | 1 | 1 000 AE/t | 16 000 AE/t | 8 000 AE/t |
| 致密 | 1 600 000 AE | 2 | 8 000 AE/t | 128 000 AE/t | 64 000 AE/t |
| 超密 | 12 800 000 AE | 3 | 64 000 AE/t | 1 024 000 AE/t | 512 000 AE/t |

数值只此一处（`common/ModularCellTiers.java` 的三档声明）；结构汇总、菜单展示、配方（若需要）都引用等级实例，不写第二份字面量。

**位序是存档协议，而位号是固定的。** `StructureStats.tierMask` 是「第 N 位为 1 表示出现过第 N 档」的位掩码。那个 N 由 `ModularCellTiers#indexOf` 给出：内置三档写死 **0/1/2**（与它们曾经是枚举时的 `ordinal()` 逐位相同，所以只装本模组的旧存档读回来仍然认得对），其余等级从 3 起按注册名排序分配。

**位号刻意不取注册表的整数 id**：那是「注册分配序」，由各模组的加载与事件触发次序决定，第三方先注册就会夺走 0/1/2、本模组三档变成 3/4/5，旧存档的混拼结构全部认错档。位号只依赖「注册了哪几个等级」，因此同样的模组集合在任何一端都得到同样的位号。**破坏性改动是「改 `builtInIndex` 里那条写死的映射」或「换掉内置实例」**——位号按实例恒等判定，与 `builtIn()` 的注册顺序无关。掩码是 `int`，等级总数上限 32（`bitOf` 与启动期的 `validateRegistry` 都会拦）。

### 2.2 模块方块与组件槽

- 每个方块自带 **1 / 2 / 3 个组件槽**（按等级），槽里插的是**现成的组件**，本模组不自制组件物品。识别入口在 `common/StorageComponents`（`gainOf` / `magnitudeOf`），而它**只看配置表**——哪些物品算组件、各贡献多少，全权由 `componentGains` 托管；代码侧的 `common/BuiltInComponents` 只是内置清单，在注册完成时写进配置当初始值，不参与运行时识别。一个组件可以同时给两个维度（`ComponentGain`），内置清单里各来源各只给一个：**存储组件**（AE2 的 `1k ~ 256k`、MEGA Cells 的 `cell_component_1m ~ 256m`、OMNI Cells 的三组）给**容量**；**能量存储组件**（AppFlux 的 `core_1k ~ 256m`）给**速率**。
- 组件同时影响容量与速率，折扣不同：容量侧 `S_i × m_i × k`（k = 0.2），速率侧 `Svi × mvi × kv`（kv = 0.0001）。其中 `m_i` / `mvi` 是**这个组件自己的修正倍率**（默认 1.0），用来区分同档组件的强弱——例如 OMNI Cells 的三组值完全一样，靠倍率拉开。
- **数量加成按维分侧计数**：容量侧乘 `(1 + b × C_cap)`（b = 2.0，故三级最多 ×3 / ×5 / ×7）、速率侧乘 `(1 + bv × C_tr)`（bv = 0.1），`C_cap` / `C_tr` 各自只数「会提供这一维」的组件。所以插一个只给容量的组件不会让速率也跟着涨。代价是「插了不提供这一维的组件时这一维不再吃加成」；纯容量/纯速率结构下两侧计数与总组件数相等，数值与分侧前一致。
- 两条公式的**唯一实现**在 `common/logic/CellMath.java`。系数分两类：k / kv 乘的是组件自己带来的值，属组件常量，只在 `common/ModularCellConstants.java` 定义、**不进配置**；b / bv / f_in 描述结构规模与输入口径，可配，默认值在同处，运行时取值来自 `common/config/ModularCellConfig`（MezzConfig 托管，服务端归属、由服务端下发给客户端，游戏内可编辑）——实现阶段别在方块实体或菜单里再写一遍算式。游戏内那个编辑界面来自配套的 **MezzConfig GUI**（独立的客户端 mod `mezz_config_gui`，本项目只在开发环境装着）；配置文件本身由 MezzConfig 管，落点在存档侧，路径可在 GUI 里看到（它不再走 `config/` 目录）。
- **「升级」的两种含义要分开**：给方块插/换存储组件是**逐块升级**，结构不受影响——需求 2 说的就是这件事；而**替换更高等级的方块**等同于拆掉重建：检测遇异等级整体放弃（§2.6），过渡期结构不成立，于是 GUI 关闭、组件掉落（§2.3）。别把两者混成一条路径实现。

### 2.3 多方块结构

- 形状：**实心立方体**，边长 3 – 16；**等级不参与几何判定**——一段里可以混拼基础/致密/超致密。
  - 每块按**自己那一档**算 `D` 与 `D_v`，整段就是逐块求和。
  - 两个结构级上限 `V_max` / `V_in_max` 是**逐块提供量之和**：每块按自己那一档出自己的上限，整段上限 = Σ 各块上限。口径理由：上限是「这一段一共能传多快」，而一块的传输能力随方块数线性叠加；取「出现过的最高档」会把它变成固定值（一拼成立方体就顶格，扩建与加组件都不再有效），而那正是需求里要避免的。
  - 整段的「代表等级」就是那个最高档，只用于取界面文案；账目里的容量与速率不经过它（它们传进来之前已经算完）。
  - 界面在混拼时报「混合等级（N 种）」，不报单一等级名——报单一名字会把「这一段里有三档」说成一种；
    浮层只报「已成型 + 模块尺寸」，不报等级——混拼结构报单一名字会把「这一段里有三档」说成一种，
    而且浮层位置与 AE2 自己那行「已存储 X / Y」相邻，多一行数字只会互相打架。
- 边长与方块数上界取自 `ModularCellConstants`（`MIN_SIDE` / `MAX_SIDE` / `MAX_BLOCKS`）。
- **未成型**（或单独一块）：该方块本身就是一台能源元件，容量与速率按它自己的配置算。
- **成型**：整段作为一个整体能量存储接入 ME 网络——一个节点、一个共享能量池、任意面接线。
- **总额逐块求和**：`T = Σ D_j`、`T_v = min(Σ D_vj, Σ V_max_j)`。之所以不是 `D × N`，是因为每块的槽位内容可以不同；配置相同时两者结果一致。
- **速率上限随结构增长**：`Σ V_max_j` 是逐块之和，所以扩建与换高档方块都会真的变快；单块自己的上限（如基础档 16 kAE/t）只在单块时生效。
- **充能比放电慢**：`T_in = min(T_v × f_in, Σ V_in_max_j)`，f_in = 0.5。**它对 FE/外部路径是真限制**：那条路径经 AE2 的 `injectExternalPower` 落到 `funnelPowerIntoStorage`，按每 tick 剩余额度 `T_in − 已收` 收口，超出部分原样退回。
- **网格侧无法限速（已核实）**：AE2 的 `EnergyService` 直接调 `injectAEPower`，而 `injectAEPower` / `extractAEPower` / `getAEMaxPower` 在 `AEBasePoweredBlockEntity` 上均为 `final` → 子类盖不住。也就是说 `T_v`（放电）与网格侧充能目前只是显示值；要让它们真生效只能自行实现 `IAEPowerStorage`（不再继承 AE 的 powered 基类）。
- **拆解**：任一方块被破坏 → 结构失效 → GUI 关闭、该方块上的组件掉落、剩余方块回退为独立元件。

### 2.4 核心与位置引用

- 段内选**一台**方块实体当核心，其余方块只在 NBT/内存里记「核心的位置」。
- 核心负责：结构快照（等级、边长、总容量、总速率）、能量池、网格节点、对外查询。
- 非核心方块负责：自己的组件槽，以及把「我是谁、我的核心在哪」告诉菜单与核心。
- **核心选取规则**：结构内**坐标最小的那一块**，也就是立方体的最小角。两者重合是有意的——「找结构范围」与「找核心」于是成了同一件事，缓存里只需要记一个位置（`docs/TODO.md` 待定项 2 已定）。
- **连接状态不是成型与否**：边框按「六个方向有没有模块方块」算（**不分等级**——结构判定也不分，两处口径必须一致，否则混拼结构会「功能成型、画面不收边」），与结构是否成立无关。两个立方体贴在一起时，内部相邻处同样会收边——这是合成单元的观感，也是玩家判断「能不能拼」的现场提示。取舍：两个不同等级、各自独立的立方体面贴面时也会收边（画面说「连着」、判定说「不是一段」）；要严格一致得改成「同一结构（core 相同）才连边」，代价是模型数据要拿方块实体信息。
- **没有谁需要每 tick 工作**：模块方块实体不注册 ticker（见 §8），核心即便有周期工作也挂在网格 tick 上。未成型的单个方块同样不 tick。

### 2.5 能量池与网络接入 —— 用 AE2 的能量面，不用平台 FE

这一条是接入口径，写死在文档里免得实现时走错路：

- AE2 的能源元件**不走平台的 `IEnergyStorage`**（那是 Forge/NeoForge Energy 的面，AE2 的能量元件不用它，接了也不会被 AE2 网络使用）。
- AE2 19.2.x 的口径是：方块实体通过自己的网格节点把存储暴露为 `appeng.api.networking.energy.IAEPowerStorage`，全网格的能量平衡由 AE2 的 `IEnergyService`（`IGrid.getEnergyService()`）负责。核心只需如实回答：容量上限、当前能量、能收多少、能放多少、`getPowerFlow`。
- 实现前先读上游：`appeng/api/networking/energy/IAEPowerStorage.java`，以及 AE2 自己那台能源元件的方块实体（本地镜像路径见 `LOCAL_CLONES.md`）。
- **单一能量池**：能量只存在核心。其余方块不各存一份——那是把「一个电池」拆成 4096 个小电池，容量与速率的公式也失去意义。

### 2.6 结构检测

入口是 `common/logic/MultiblockScanner`，触发时机只有三个：**方块被放置、方块被破坏、某方块的组件槽变化**。绝不上 tick 扫描。

算法与实现口径：

1. 从触发方块出发沿六向扩张，只收**模块方块（不分等级）**；偏移越出 `±MAX_SIDE`、或邻居未加载，一律立刻判「不成型」——把没读完的区块当空，会得出一个假的实心立方体。
2. 连通体方块数超过 `MAX_BLOCKS` 立刻放弃；包围盒三边必须相等且落在 `[MIN_SIDE, MAX_SIDE]`。
3. 校验实心用「连通体方块数 == 边长³」这条等式，等价且不用逐格扫。
4. 成立 → 核心（= 最小角那一块）持有结构范围与整段账目，其余块记核心位置与「自己那块」的账目（核心不在场时的退路）；不成立 → 参与检测的那一段整体退化为独立元件。
5. 缓存随 NBT 持久化：`origin` / `core` / `side` / `capacity` / `rate` / `tierMask`（混等级时整段出现过哪几档；代表等级由掩码现推，不另存一份会对不上的数）。

临时对象只有两块定长数组（访问位图 `(33³ + 63) / 64` 个 long 与 `MAX_BLOCKS` 长的队列），逐块汇总时复用的也是同一个 `long[]` 暂存——所以「检测期间不分配与方块数同规模的临时对象」这条红线是**用结构保证的**，不是靠自觉。

**破坏路径必须整段清**：一段立方体被拆掉一块之后，剩下每一块单独重扫都会失败，只清自己会让其余块留着指向已消失结构的核心引用。所以拆解时先按被拆那块手里的范围把整段清成独立元件，再从六个邻居各自重算（见 `EnergyCellModuleBlockEntity#onModuleRemoved`）。

**已知边界**：跨区块拆解时，未加载一侧的引用要等到下一次放置/破坏/组件变化才校正——重扫会在「邻居未加载」处判不成型，而那一刻恰恰不能拿它去纠正状态。期间那部分方块在 GUI 里退化为单块账目。这条记在 `TODO.md`。

### 2.7 「谁持有网格节点」——本设计的张力点

需求同时要求两件互相拉扯的事：

- 未成型时**单个方块要能独立工作**（它就是一台 1×1×1 的结构）；
- 成型后**只有一个网络节点**（需求 4.5：网络节点仅核心注册）。

定稿口径：**「核心」是动态角色，节点只在核心上创建。**

- 每台模块方块实体都能成为核心；未成型时它自己就是那一段的核心，节点在它身上。
- 成型后，段内选出的核心保留节点，**非核心方块的节点销毁**。不采用「留着但不活动」：那等于让 4095 个节点继续挂在网络上，与「仅核心注册」不符，也与 §8 的红线相悖。
- 结构失效（拆解）后，每块重新各自成为核心，节点按需重建。

代价是结构变化时有一次节点生死，换来网格里节点数恒为 1。实现阶段的第一件事是确认 AE2 的公开面能干净地做到「销毁 + 重建」（`AENetworkProxy` 的 `invalidate()` / `destroy()` 与 `onReady()` 怎么配合），这一点已记在 `TODO.md` 待定项 8。**不要**用「每个方块都暴露存储、靠能量池互通」绕过去：那会在网格里出现 4096 个能量存储接口，直接违反需求 4.5 的性能目标。

---

## 3. 关键数据流

> 3.1 与 3.3 的账目部分（等级、边长、容量、速率）已按本节实现；凡涉及能量池与网格的（3.3 第 3 步、3.4）仍等 M4。

### 3.1 放置/破坏 → 结构检测 → 成型或不成型

1. 方块被放置或破坏（`onPlace` / `onRemove`；邻居变化只用来重取连接状态，不触发重扫）。
2. 触发检测：从被放置的方块（或被拆方块的六个邻居）扩张 → 得到结果（最小角、边长）或失败。
3. 成功：核心写入快照（容量 `T = Σ D_j`、速率 `T_v = min(Σ D_vj, Σ V_max_j)`、输入 `T_in = min(T_v × f_in, Σ V_in_max_j)`，三项都是逐块求和），其余方块写核心引用。**不改方块状态**：成型与否不换模型，外观由连接位驱动（见 §2.4）。
4. 失败：参与检测的那一段整体回退为独立元件；原本成型的那一段若被拆，能量归属见 `TODO.md` 待定项 3（M4 落地时定）。

### 3.2 右键方块 → 该方块的 GUI

0. **只有成型才有屏**：单块与「没拼成立方体的一堆」右键无反应，屏也就不存在。判据是结构检测的结果（{@code EnergyCellModuleBlockEntity#isFormed}），不是另加的方块状态——成型与否不换模型（§2.4）。结构失效时服务端在 `broadcastChanges` 里把玩家退出去（AE2 自己的写法：`getPlayer().closeContainer()`）。
1. 客户端右键 → 服务端按该方块开屏（**不是**核心的菜单：屏幕列出的是整段结构里的模块，但入口是手上这一块）。
2. 菜单在服务端构造时向核心要一份**总览快照**（代表等级、等级掩码、边长、模块数、总容量、总速率、总输入速率），经 `@GuiSync` 发给客户端；未成型时退化为边长 1、容量就是它自己。
3. **面板高度是拼出来的**：表头（17px）+ N 条 18px 带 + 表尾（88px），贴图切片给三段的高。N 由客户端按「终端样式」档位与窗口高度算（SMALL/MEDIUM/TALL/FULL → 4/6/8/填满，夹在 4~12），算完报给服务端（`menu.visibleRows`）——服务端不知道玩家窗口多大，这个值只能客户端报、服务端夹。换档或改窗口尺寸后面板会变高变矮；**表尾里的东西（玩家背包与快捷栏槽位、两条进度条、三段固定文字）跟着下移**，位移量 `footerDelta = footerTop - 表尾在贴图里的 y`。
   - 槽位坐标写在样式文档里，改写靠本模组的 AT（`Slot.x/y` 去 final，见 §6）；文档里 `text` 段的坐标是绝对的、挪不动，所以那三段文字由屏幕按「文档坐标 + footerDelta」重画（`setTextHidden` 关掉文档那份，文案仍从文档取）。
   - 表尾里锚点、玩家槽位的「原始坐标」只记一次（`playerSlotBaseY`），之后每次重排都是「原始 + 位移」——不能累加，且 `setSlotsHidden(sem, false)` 会触发一次全量重摆、必须在它之后再上一次位移。
4. 一屏 N 条 18px 带，每条带 3 列；窗口按**行**滑动（`setRowOffset`），服务端夹取后回传，两侧才一致。超出 N 的带在服务端不填内容（`visible` 里置空），否则快速移动会把组件塞进玩家看不见的槽。
5. **行表按等级分组**（`common/logic/TerminalRows`）：每档一条标题行（占整行的平面带）+ 若干模块行（每行 3 个模块）。行表是平坦的，标题行不绑在它下面那条模块行上；带图按「行角色（标题/模块）× 位置（首/中/末）」两面选——两个维度互不隶属，所以 6 条带是 2×3 的可用组合。**角色决定用哪一组带，所以模块行永远落在带 12 格底纹的槽带上、标题行永远落在平面带上**（贴图里带格线的正好三条：`y=35/71/107`）。完整行表由服务端拼成 `rowLayout` 字符串（`T:<等级短 id>:<该组模块数>` / `M` / `-`）同步给客户端，屏幕不自己重新分组。
6. **标题行 = 等级图标槽 + 它右侧左对齐的「模块数量：N」**：图标槽放的是那一档的方块物品（只读假物品），右击图标选中/取消选中该组；选中后快速移动只往这一组落（筛选在 `isValidQuickMoveDestination`，选中状态存服务端）。
7. **槽位坐标与内容解耦**：第 i 个模块槽永远在第 i/3 条带上（坐标写死在样式文档里，运行期不改 ），行表变动只换「哪条带装什么」。每条带上有三套语义（模块槽 + 组件槽 / 标题图标槽），屏幕按当前行表逐条隐藏用不到的那一套——文档必须把 `module_0..35`、`module_components_0..35`、`module_title_0..11` 全声明齐（自检脚本会核这个，见 §6）。
8. 组件覆盖层按「这个模块能不能给出这一格」铺：能则 `componentSlot`，不能（基础 1 个、致密 2 个）则 `unavailableSlot`；标题行与组末尾的空列不铺任何覆盖层（那里没有槽）。

### 3.3 组件变化 → 三段重算

1. 槽位变化（服务端）→ 写入的那一块触发一次结构重算（组件不影响几何，但影响账目）；重算以这一块为准遍历整段，不依赖「组件变化会通知核心」这种单点链路。
2. 核心重算总容量与速率：逐块求和，写回整段（`CellMath` 是唯一公式来源）。
3. 更新网格里的存储实例（容量上限变了，当前能量不变；若上限低于当前能量，按 AE2 上游对能源元件的处理走，别自己发明）——**M4 落地前这一步不存在**。

### 3.4 能量往来

1. 网格需要抽能/充能 → 经 `IEnergyService` 找到核心暴露的 `IAEPowerStorage`。
2. 核心按自己的容量与速率限额回答，能量记在核心一处。

---

## 4. 网络协议

菜单与屏幕之间的约定，与姊妹项目同口径：**名字即协议**。已用的值：

- `@GuiSync(60..69)`：`tierId`（等级短 id，字符串，客户端用 `EnergyCellTier.byId` 还原）、`structureSide`、`totalModules`、`totalCapacity`、`totalTransferRate`、`totalInputRate`、`currentEnergy`、`currentInputRate`、`rowOffset`（窗口顶端行号）、`totalRows`。**同一个菜单里不能重复**，AE2 在构造期会当场抛异常。
- `registerClientAction("setRowOffset")`：屏幕滑动窗口的意图。客户端只发意图，服务端夹取后经上面的 `@GuiSync` 回传，两侧才一致。
- 槽位窗口不另开协议：模块身份跟着展示槽那张方块物品的物品名走（`gui.ae2modularcells.module_label`），槽同步自带要传的东西。
- 方块实体的 NBT key 也是协议：`core`（非核心记的核心位置）、`origin`（核心记的最小角）、`side`、`capacity`、`rate`——定下来就不再改名。组件槽走父类 `AEBaseInvBlockEntity` 的键 **`inv`**（不再自己写一份）；能量走父类自己的 `internalCurrentPower`。

---

## 5. 集成层

适配邻居的方式：**按 mod id 门禁 + 每个特性各自守卫**。邻居的类型名不得出现在总是加载的类里——缺那个模组时要在加载阶段安全降级。

| 邻居 | 形态 | 干什么 |
|---|---|---|
| MEGA Cells | 可选，**不引用其类** | 提供 `cell_component_1m ~ 256m`（1M ~ 256M MEGA Storage Component），是槽位高档组件的来源；它的 Superdense Energy Cell 也是等级数值的对照参考。按 mod id 探测即可 |
| AppFlux（Applied Flux） | 可选，**不引用其类** | 提供 `core_1k ~ 256m`（1k ~ 256M ME Energy Storage Component），十档全覆盖。它的必需前置是 AE2 + GuideME + Glodium |
| AE2 OMNI Cells | 可选，**不引用其类** | 三组组件（`omni_` / `complex_omni_` / `quantum_omni_cell_component_`）同时给容量与速率，修正倍率依次 0.75 / 1.5 / 3（用户口径）——它们值完全相同，靠修正拉开行档；三个数在配置里也能改 |

OMNI 那三组的速率维乘的仍是 `kv`（`0.0001`），所以低档位下几乎不起作用（1k 档：`1024 × 3 × 0.0001 ≈ 0.3 AE/t`），真正可见的是它把组件计入了 `C_tr` 的计数加成；要到高档位才有量（256m 档：`268435456 × 3 × 0.0001 ≈ 80 531 AE/t`）。
| JEI / EMI | 可选，客户端 | 配方查看（三个等级的合成表）与将来的配方转移入口 |
| Jade | 可选，客户端 | 显示结构等级、尺寸、当前/总容量（M7） |

存储组件的识别**只看配置表**（`common/StorageComponents#gainOf` → `ModularCellConfig#configuredGains`）：哪些物品算组件、各贡献多少，全权由 `componentGains` 托管。代码侧的 `common/BuiltInComponents` 只是**内置清单**（AE2 只 k 系、MEGA 只 m 系，加上按注册名的 AppFlux / OMNI 条目），在服务器启动时写进配置当初始值，之后不再参与识别。

所以：新增一个邻居就是往 `BuiltInComponents` 的清单里加一条——它会在下次启动时被自动补入（服务器启动那一步把清单里「配置还没有的」写进文件），不必手动改存档。取舍是彻底的可见可改换来与上游脱节：邻居改了字节数我们不会自己跟。`integration/` 留给真正必须引用邻居类型的东西（Jade 浮层之类）。

`StorageComponentTier` 是清单的**数值来源**（改它只影响往后新写的初始值）。而手势判「更低档 / 同档另一种」比的是组件数值本身（`ComponentGain#magnitude`），不是那张表的序号，所以配置里新增的组件也能参与手势。

### 5.1 给其他模组的接入面（`api` 包）

其他模组要接入，就是两步：**注册一个等级**，再**注册它配套的方块套装**。用法（含脚本那条路）、要自备的资源与失败现象对照表在 [`INTEGRATION.md`](INTEGRATION.md)；本节讲的是这么设计的原因。

```java
// 等级那一步本模组代管：不用建 DeferredRegister、也不用挂总线
public static final EnergyCellTier MY_TIER = ModularCellsApi.registerTier(
        "yourmod", "my_tier", 400_000L, 2_000L, 2, 32_000L, 16_000L);

public static final ModuleBlockSet MY_SET = ModularCellsApi.registerEnergyCellModule(
        BLOCKS, ITEMS, BLOCK_ENTITIES, MY_TIER);
```

`ModularCellsApi#registerEnergyCellModule` 代做三件事：三者同名注册（方块 / 方块物品 / 方块实体类型）、把方块实体类型回填给方块、把套装塞进全局清单。

**全局清单是这一层的关键**（`ModularCellsApi#allModuleBlockSets`）。能力注册与客户端模型替换都要跨模组看「全部模块方块」：

- **能力注册**（`AEModularCellsRegistries#registerCapabilities`）按方块实体类型走一遍清单，给每个套装挂三条：`EnergyStorage.BLOCK`（只进不出的 FE 面）、`ItemHandler.BLOCK`（组件槽的自动化面）、`AECapabilities.IN_WORLD_GRID_NODE_HOST`。最后一条最容易漏——**AE2 只在它自己的类型表上批量注册网格主机能力**（上游 `InitCapabilityProviders` 遍历 `AEBlockEntities.BLOCK_ENTITY_TYPES`），第三方不补就接不上线缆。
- **客户端模型替换**（`ConnectedModuleBakedModel#install`）也遍历清单、按 `set.block().getId()` 匹配烘焙表。遍历等级表是不行的：第三方可以给自己的方块取别名。
- **创造页**反过来**只收本模组命名空间**的模块方块；第三方注册的进本模组的「外部注册」标签页（`AEModularCellsRegistries#EXTENSIONS_TAB`，按命名空间区分，没有外部套装时该页为空、客户端不显示）。
- 本模组自己的三档走**同一个入口**（`AEModularCellsRegistries` 的 static 块），不给自己留特权路径，否则两条注册路径迟早会分叉。

**第三方要自备的东西**：

- 贴图两张，按等级 id 命名：`<tierId>_energy_cell`（面板）与 `<tierId>_energy_cell_light`（亮面）。连通模型按等级 id 找它们；找不到时会**回退到内置基础档的贴图**并告警（不是紫黑格），所以第三方可以先把档位跑通再补外观。
- 方块状态与物品模型 JSON：本模组不做数据生成，三档那几份可以照抄（形状变体 `fill=0..4` × `formed=false/true`，共十个）。
- 语言键 `block.<命名空间>.<方块名>`。

（贴图、模型与语言键都只客户端需要。**掉落不用管**：方块本体由 `EnergyCellModuleBlock#getDrops` 给出、
组件由父类的 `addAdditionalDrops` 给出，所以挖掘、爆炸、扳手拆解与第三方工具的批量破坏都覆盖到了，脚本与
第三方注册的档位都不需要掉落表。面向接入者的完整版含失败现象对照表，见 [`INTEGRATION.md`](INTEGRATION.md)。）

**脚本侧（KubeJS）**

整合包脚本也能定义一个档位——给 {@code minecraft:block} 注册了一个本模组的具名 builder 变体，脚本写：

```js
StartupEvents.registry('block', event => {
    event.create('my_tier_energy_cell_module', 'ae2modularcells:module')
        .capacity(400000).transferRate(2000).componentSlots(2)
        .maxTransferRate(32000).maxInputRate(16000)
})
```

等级名默认取方块名去掉 `_energy_cell_module` 后缀（可用 `tierId('...')` 覆盖；方块名不含该后缀时整个方块名当等级名）。

**入口为什么是方块而不是等级**：KubeJS 的落盘只认「当前正在注册的那个注册表」——它的
`RegistryEventHandler#handleRegistryEvent` 取出该注册表的存储后逐个 `event.register`。而 `minecraft:block` 这类
vanilla 注册表早于本模组的自建注册表（**实测成立，不是契约**；若这个前提被上游改掉，表现是启动期报错、
不是静默半坏），所以在等级 builder 里顺带 `add(Registries.BLOCK, ...)` 会被**静默丢弃**
（实测过：等级建出来了、方块没有）。反过来，方块最早、物品与方块实体类型都比它晚，所以以方块为入口能落盘；
等级更晚，于是由方块 builder 产出实例、交给本模组在等级注册表那一次 `RegisterEvent` 补注册
（`ModularCellsApi#registerLateTiers`），脚本侧不必关心时序。

（首版曾以 `StartupEvents.registry('ae2modularcells:energy_cell_tier', ...)` 为入口，因上面的时序问题在实测中作废
——若见到那种写法，它不会生效。）

脚本建的套装经 `ModularCellsApi#registerModuleBlockSet` 与 Java 注册的汇进同一张清单，之后的处理完全一样。面向整合包的完整用法、可抄的案例与定档位的数值参照在 [`kubejs/README.md`](kubejs/README.md)。

**边界**：等级与方块套装是**双端都要满足**的注册表——只在服务端注册的等级，客户端解不出那一档（终端里的结构快照按位号配对）。位号因此在两端必须一致，而这靠「两端装了同一批等级」保证，与注册顺序无关（见 §2.1）。

---

## 6. 资源

- `assets/ae2modularcells/`：`lang`（含 `_zh_cn`）、`models`、`blockstates`、`textures`
- `assets/ae2/screens/ae2modularcells/`：**AE2 样式文档，唯一布局源**。屏幕代码只按它给的位置摆放；控件按 id 注册，文档有键而代码没注册则控件不出现，代码注册而文档没键则**开屏即抛**（NeoForge 拿不到屏幕实例，玩家看到的是被踢出 GUI）；**按编号批量注册的槽位语义同理**（没声明的语义拿不到坐标，且被隐藏后再也回不来）。改完跑：
  ```bash
  node tools/check-screen-widgets.cjs .
  ```
  退出码 0 = 全部已声明；1 = 有未声明；**2 = AE2 公共段落未取到、部分项无法判定——2 不算通过**。
- `data/ae2modularcells/`：`recipe`、`loot_table`（只剩监控器与 `blank_energy_unit`——模块方块不再需要掉落表）、`tags`
- `META-INF/accesstransformer.cfg`：本模组自己的访问转换器。目前只开一个口：`Slot.x` / `Slot.y` 去 final（终端面板高度随「终端样式」档位变，玩家背包区要跟着表尾往下挪，而槽位坐标只能改写）。AE2 自己也开了同一个口。

布局文档的控件尺寸、贴图切片、文字颜色也都归文档：能进文档的别写死在代码里。

---

## 7. 构建与开发

```bash
./gradlew compileJava compileClientJava   # 改完先跑这个
./gradlew assemble                        # 全量（含 client）
./gradlew runClient                       # 开发客户端（-Pemi 切换到 EMI）
./gradlew runData                         # 数据生成
```

仓库根有四个 Windows 启动脚本，内容就是上面的命令加 `--no-configuration-cache`（注释用英文：`.bat` 按系统代码页解析，中文注释在 GBK 环境下会乱码）：

| 脚本 | 等价命令 |
|---|---|
| `run_client.bat` | `gradlew runClient` |
| `run_client_emi.bat` | `gradlew runClient -Pemi` |
| `run_server.bat` | `gradlew runServer` |
| `run_data.bat` | `gradlew runData` |

`run*` 一律带 `--no-configuration-cache`：这些任务会把开发运行期的 classpath（`localRuntime` / `clientLocalRuntime`）卷进配置缓存，命中旧缓存就用老 classpath 启动（症状是「刚加的 mod 不生效」）。

开发环境里装的邻居 mod（只进 `localRuntime` / `clientLocalRuntime`，不是发布依赖）：

| mod | 为什么要装 |
|---|---|
| AE2 + GuideME | 本体与指南库，缺了跑不起来 |
| JEI（默认）/ EMI（`-Pemi`）+ AE2 JEI Integration | 配方查看；AE2 的 JEI 集成是独立模组。**走 `clientLocalRuntime`**：JEI 在无客户端的进程里初始化会崩（构造期碰 `Minecraft.getInstance()`，datagen 里那个实例是 null），挂 `localRuntime` 会让 `runData` 直接失败 |
| MEGA Cells | `cell_component_1m ~ 256m`：槽位高档组件的来源 |
| AppFlux + Glodium | `core_1k ~ 256m`：能量存储组件来源，Glodium 是它的必需前置 |
| Applied Generators（应用发电机）| FE 侧的电源：配合 AppFlux 把 FE 折成 AE，用来堆出 10^5 ~ 10^6 AE/t 量级的稳态负载（M8 的欠账）。它的必需前置是 AE2 / Glodium / AppFlux，三者都在场 |
| Jade | M7 的浮层集成要对着它写（纯客户端，走 `clientLocalRuntime`）|
| AE2 Omni Cells | 可选，**不引用其类** | 提供 `omni_cell_component_* / complex_omni_cell_component_* / quantum_omni_cell_component_*`（1k ~ 256m ×3 组）。它的组件是纯 `new Item(...)`、不实现 AE2 接口也不自带数值，本模组按注册名认（`common/BuiltInComponents`）|
| Embeddium / Modern UI | 优化与 UI 渲染（纯客户端，走 `clientLocalRuntime`）：前者压渲染开销，后者及早暴露屏幕渲染的共存问题 |
| ModDevMCP | 开发辅助：给 IDE / AI 代理一个查符号与映射的 MCP 服务。本地源码仓库在场时由 `settings.gradle` 的 `includeBuild` 替换成源码构建（`:Mod` 子项目），缺席则靠 mavenLocal 里那份 0.1.6——两者都没有时 `run*` 会在解析 `runtimeClasspath` 时失败（`build` 与编译不受影响）|

分界线是「这个 mod 在无客户端的环境里能不能活」：配方查看器与 Jade 的浮层只有客户端有意义，所以走 `clientLocalRuntime`（只进 `clientRuntimeClasspath`），`runServer` 与 `runData` 都不加载它们；而内容与服务端插件（AE2、MEGA Cells、AppFlux、Omni Cells、ModDevMCP）挂在 `localRuntime` 上，服务端运行也要在场。

依赖来源：

- AE2 / GuideME：`gradle/libs.versions.toml` 声明，`compileOnly`（运行期由整合包提供）。
- JEI / EMI：`clientCompileOnly` 只是编译期 API；运行期由 `clientLocalRuntime` 提供（见上表）。
- MEGA Cells：`localRuntime`（Modrinth 坐标用项目 id + 版本 id 双钉位，避免跨 loader 解析错构建）。
- **客户端运行的 classpath 是两半拼起来的**：`clientRuntimeClasspath`（`clientLocalRuntime` 的内容）加上 `main.runtimeClasspath`（`localRuntime` 的内容）。这条合并靠 `build.gradle` 里的 `sourceSets.client.runtimeClasspath += main.output + main.runtimeClasspath` 与 `runs.client.sourceSet = sourceSets.client` 两行，删掉任何一行，客户端就会只加载到一半的开发 mod（症状是「AE2 不见了，但 JEI 还在」）。
- 没有 `libs/*.jar` 本地件：本项目当前不需要没有公共 maven 坐标的构建产物。将来若引入，照姊妹项目的规矩放 `libs/` 并在 `.gitignore` 里排除。

上游源码镜像（读 AE2 实现时用）：路径与分支基线记在 `LOCAL_CLONES.md`（本机本地、已 gitignore，换机器要改）。**别读 gradle 缓存里的 AE2 sources jar** —— 那是别的 Minecraft 线的构建，会得出「某方法有 / 没有」这类反向结论。

---

## 8. 维护者要守住的东西

**协议面，只搬逻辑不搬标识**

- `registerClientAction` 的动作名、`@GuiSync` 的注解值、方块实体的 NBT key、`api` 包的签名。每台设备的键见它的 `saveAdditional` / `loadAdditional`。

**连接渲染怎么接进来的**

- 自定义烘焙模型没走 mixin（AE2 给内建模型开的口子是 `ModelBakery` 的 mixin），用的是公开的 `ModelEvent.ModifyBakingResult`：JSON 与 blockstate 照常是事实源，替换万一没装上最多退回一个普通立方体。连接位由方块实体的 `getModelData()` 现算、`updateShape` 里请求刷新——两端各算各的，所以不需要网络包。边框贴图的分工（角 / 横 / 竖）与合成单元同口径，见 `client/render/ConnectedModuleBakedModel`。
- **烘焙表里的键不是模型文件名**：1.21 起方块模型按 `BlockModelShaper.stateToModelLocation` 登记，键是 `<方块 id>#<状态串>`；同一张表里物品模型与方块模型**共用 id**，只靠 `#inventory` 变体区分——替换时必须排掉物品那份，否则物品会因为这份模型不出无朝向的面而变透明。按模型文件名去匹配不会报错，只会静静地不生效（本轮踩过一次，已加告警日志）。
- **手写几何的贴图靠 JSON 声明入图集**：边框贴图不被任何 `elements` 引用，靠 `models/block/*.json` 的 `textures` 段列出才进方块图集；漏了只会拿到 missingno，`install` 里对每个 sprite 校名并告警。

**性能红线**

- **不注册方块实体 ticker**：`BlockEntityType` 只要带了 ticker，NeoForge 每 tick 就会遍历段内 4096 台方块实体、逐台调用 `tick()`——函数体里写 `return` 只省了计算，没省调度。所以模块方块实体**不注册 ticker**：能量的注入与抽取本来是被动的（AE2 的 `IEnergyService` 主动来拉 `IAEPowerStorage`），不需要自己的 tick。真出现周期性工作（例如界面刷新节流），挂 AE2 的网格 tick（`IGridTickable` + `TickingRequest`），让调度只沿带着节点的核心走。
- **结构检测不轮询**：只在放置、破坏、组件变化时重算；缓存随 NBT 持久化。
- **扫描有上界**：单次检测迭代不超过 `MAX_BLOCKS`。
- **单一能量池**：能量只存核心，不给每个方块开能量账户。
- **网络节点只暴露一个**（见 §2.7）。

**线程边界**

- 一切方块、方块实体、网格与能量访问都在服务端 tick 线程。这一层没有任何允许出线程的工作（与姊妹项目的样式分析线程不同，本项目不需要）。

**数值集中**

- 容量、速率、槽位数、组件折扣（k / kv）与数量加成（b / bv）、输入比例 f_in、速率与输入上限只在 `EnergyCellTier` / `StorageComponentTier` / `ModularCellConstants` 里定义；公式只在 `common/logic/CellMath` 实现一次。别的地方写第二份就是给未来的平衡改动埋雷。

**注释里带原因的，别当废话删**

写下来的取舍（例如「为什么检测用计数而不是逐格」「为什么成型后要收回非核心节点」）删掉之后，下一个人会重新踩一遍。改动前先读完整段。

---

## 9. 常见改动指引

**加一个等级**：`EnergyCellTier` 加一项 → 方块与方块实体各加一套（照现有等级复制）→ 注册 → 两份 lang → `../README.md` 的数值表 → `TODO.md` 的平衡表。

**加一种存储组件档位**：`StorageComponentTier` 加一项（id + 容量值）作为对照表，并在 `../README.md` 与 `TODO.md` 的档位表里同步——识别与取值不用动代码：AE2 的 `IStorageComponent` 是唯一入口，邻居的组件只要实现了它就能插。本模组**不自制**组件物品，所以这里不用注册任何东西。

**改数值**：只动常量类。改完核对 `../README.md` 与 `TODO.md` 的表格（那两份是展示，允许滞后，但不该长期不一致）。

**改 GUI**：先改 `assets/ae2/screens/ae2modularcells/*.json`，再改屏幕代码，最后跑屏幕自检脚本。控件在代码里必须按 id 注册：文档有键而代码没注册，那块永远不出现。

**动 api**：`api/package-info.java` 写明这个包是稳定面。改签名等于破坏第三方兼容，改之前先在 `../CHANGELOG.md` 记一笔。

## 10. 终端（模块管理终端）

终端是**客户端本地布局**：与 AE2 的样板访问终端（me/patternaccess/PatternAccessTermScreen）同构——行对象列表、客户端排行、Scrollbar 持偏移、样式切换只做客户端重排。三条不变量，动它之前先读：

1. **行表在客户端算**。服务端只下发结构快照：`@GuiSync(74) structureSnapshot`，内容是按 multiblock 遍历序、每模块一个等级短 id、逗号分隔（见 EnergyModuleTerminalMenu#buildStructureSnapshot）。客户端用 EnergyCellTier.byId 解析后交给纯函数 common/logic/TerminalRows 排出整张行表（分组、标题带、每行几个模块）。**别再引入服务端拼好的行表字符串**——那正是两次首帧 bug（开屏空屏、要等一次画面更新）的来源。
2. **槽内内容由服务端填**。屏幕看到的内容来自 visible/windowModuleAt 与服务端镜像（ModuleDisplayInventory.serverStacks、ModuleTitleInventory）。原版菜单槽同步只推「与上次不同」的格子，所以服务端必须**存一份镜像再算**；按需现算会让同步永远推不出「变了」，客户端就一直是空的（历史事故：模块本体不显示、组件格整列不画）。
3. **槽位坐标不可改**。坐标写死在 assets/ae2/screens/ae2modularcells/energy_module_terminal.json，运行期只允许改**可见性**（applySlotVisibility / setSlotsHidden）。AE2 的 setSlotsHidden(sem, false) 会触发 positionSlots() 把所有语义拉回文档坐标，任何在客户端写 slot.x/y 的做法都会与它打架。

另外两条踩过的坑：

- **遍历序必须同源**。EnergyCellModuleBlockEntity#moduleTiersInOrder 与 rows() 必须用同一个 MultiblockScanner.Layout 与同一个 tierOfModuleAt。历史事故：快照曾走窗口列表（moduleTierAt → windowModuleAt），随滚动漂移、两端分组错开，会让玩家把组件装到别的模块上。
- **兜底要「不隐藏」而不是「少显示」**。快照未到的那一帧把带铺满（全是模块带），不要按算出来的行数少排——服务端已经按它自己的行表填了内容，少排会让尾部带被隐藏却仍可写入。
