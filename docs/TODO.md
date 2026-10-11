# AE2 模块化能源元件 需求待办表

> 状态图例：⬜ 未开始 | 🔄 进行中 | ✅ 完成 | ⏸ 暂缓 | ❌ 已放弃
> 优先级：P1 最高 | P2 | P3
> 单位约定：容量与速率的数值都是 AE 与 AE/t（AE2 的单位）。`200_000` 就是 200 kAE，不写「千」。

## 一、实施顺序

### M1 项目骨架 — ✅（本轮完成）

- ✅ 构建脚手架（`build.gradle` / `settings.gradle` / `gradle.properties` / wrapper / `libs.versions.toml`）
- ✅ 模组元数据模板 `src/main/templates/META-INF/neoforge.mods.toml`（依赖：AE2 必需；JEI/EMI/MEGA Cells 可选）
- ✅ 包结构与注册入口（`AE2ModularCells` / `AEModularCellsRegistries`）
- ✅ 数值集中类（`EnergyCellTier` / `StorageComponentTier` / `ModularCellConstants`）
- ✅ 文档体系（`AGENTS.md`、`docs/ARCHITECTURE.md`、本表、`docs/CHANGELOG_STYLE.md`、`CHANGELOG.md`、`README.md`）
- ✅ 屏幕样式自检脚本 `tools/check-screen-widgets.cjs`（映射表待实现阶段填）

### M2 方块与方块实体 — ✅（第一阶段完成）

- ✅ 三个等级方块（`basic_energy_cell_module` / `dense_...` / `superdense_...`）+ 方块物品 + 独立创造标签页
- ✅ 方块实体：等级、组件槽内容、本方块容量与速率贡献、**核心位置引用**、结构账目快照
- ✅ 不注册方块实体 ticker（`BlockEntityType` 带 ticker 就会每 tick 被逐台调用，函数体里写 `return` 省不了那次调度）；真要周期工作就挂 AE2 的 `IGridTickable`
- ⏸ 方块状态不带成型标志：**成型不替换模型**，成型与否由连接位驱动（见 `docs/ARCHITECTURE.md` §2.4）。原计划的「成型/未成型外观切换属性」不再需要，M7 的视觉留位改在连接贴图上做

### M3 结构检测 — ✅ P1（第一阶段完成）

- ✅ 检测算法（`common/logic/MultiblockScanner`）：从触发方块扩张**模块**连通体（不分等级，见下）→ 校验实心与立方 → 记录最小角（= 核心）与边长
- ✅ **允许混等级**（用户口径）：一段立方体里可混拼基础/致密/超致密；每块按自己那一档算 `D` 与 `D_v`，两个上限也是逐块之和（代表等级只用于界面文案，口径见 `docs/ARCHITECTURE.md` §2.3）
- ✅ 缓存与失效：只在放置/破坏/组件变化时重算，绝不上 tick 扫描；结果随 NBT 持久化（`tierMask` 一并存，代表等级由掩码现推）
- ✅ 上界保护：单次扫描不超过 `ModularCellConstants.MAX_BLOCKS`（4096），超界立刻放弃；临时对象只有两块定长数组（访问位图 + 队列）
- ✅ 破坏路径：任一方块被拆 → 整段失效（先按被拆那块的记录整段清）→ 组件掉落、剩余方块回退为独立元件
- ⬜ **跨区块拆解后的引用校正**：未加载一侧要等下一次触发才校正（期间退化为单块账目）。做法：要么在载入时按「结构范围是否都已加载」决定要不要复检，要么给核心记一个待校验标记

### M4 能量池与网络接入 — ✅ P1

- ✅ 核心方块实体持有网格节点（只有它注册节点；非核心的节点暴露面为空集，见 `getGridConnectableSides`）
- ✅ 实现 AE2 的 `IAEPowerStorage`（**不是**平台 `IEnergyStorage`，口径见 `docs/ARCHITECTURE.md` §2.5）
- ✅ 容量与速率按公式动态计算；结构或组件变化后重算上限（`setInternalMaxPower` 跟着账目走）
- ✅ 单一能量池：能量只存在核心；非核心从任何面收的 FE 在同一 tick 转交核心（`funnelPowerIntoStorage`）
- ✅ **FE 输入**：`RegisterCapabilitiesEvent` 按方块实体类型注册 `EnergyStorage.BLOCK`，比例走 AE2 的 `ForgeEnergyAdapter`（默认 1 FE = 0.5 AE）
- ⬜ 实机确认：FE 源持续灌入跨过 1 万 AE 仍能继续涨；成型后在结构里插一个组件，储能不得变成 N×E

### M5 菜单与屏幕 — ✅ P1（实机观感待确认）

- ✅ **只有成型才能开屏**：单块与没拼成立方体的一堆右键无反应（与合成单元同口径）；结构失效时服务端主动关屏
- ✅ 菜单：18 个模块位（6 条带 × 3 列，只读展示）+ 每模块 3 个组件槽 + 玩家背包/快捷栏；窗口滑动走 `setRowOffset`（粒度 = 行表的一行），客户端只发意图、服务端夹取
- ✅ **行表按等级分组**（`common/logic/TerminalRows`）：每档一条标题行（占整行的平面带）+ 若干模块行；标题行不绑在它下面的模块行上，行表按 `rowLayout` 字符串同步（`T:<id>:<该组模块数>` / `M` / `-`）。混拼时报「混合等级（N 种）」
- ✅ 屏幕：AE2 样式文档驱动的布局（`assets/ae2/screens/ae2modularcells/energy_module_terminal.json` 是唯一布局源）；**面板高度是拼出来的**——表头（17px）+ N 条 18px 带 + 表尾（88px），换终端样式档位就换 N；表尾里的固定文字、两条进度条与玩家背包区跟着下移（差值 `footerDelta`，靠本模组的 AT 改写 `Slot.x/y`；样式文档的 `text` 段是绝对坐标，所以那三段文字由屏幕按「文档坐标 + 偏移」重画）；带图按「行角色（标题/模块）× 位置（首/中/末）」两面选（2×3 的 6 条带）
- ✅ **终端样式决定条数**：档位 → 目标条数 4 / 6 / 8 / 填满窗口（上限 12，下限 4），客户端按「窗口高度算得出的条数」夹取后同步给服务端（`menu.visibleRows`）；之所以不照搬 AE2 的 `TerminalStyle#getRows` 分数制，是因为这里的「行」是固定 18px 的模块行，直接给条数更好懂
- ✅ 左侧工具条三枚按钮，顺序为：**打开指南**（AE2 的 `OpenGuideButton`，显隐由样式文档的 `helpTopic` 控制）→ **终端样式**（AE2 自己的 `SettingToggleButton` + `Settings.TERMINAL_STYLE`，写 `AEConfig`，换档后重排本屏）→ **显示方式**（本模组的，轮换字母/科学/工程三种数字格式，贴图取 `states.png` 的 `0,0` / `16,0` / `32,0`）
- ✅ 数字格式：字母 `12.3M` / 科学 `1.23E7` / 工程 `12.3E6`，作用于两条进度条与顶栏汇总；模式存在客户端静态字段，不进存档
- ✅ 标题行：一个只读的等级图标槽（放的是那一档的方块物品，只为看）+ 它右侧左对齐的「模块数量：N」；**右击图标 = 选中/取消选中该等级组**，选中后快速移动只往这一组落（筛选在 `isValidQuickMoveDestination`，选中状态存服务端）
- ✅ 组件槽只收现成的存储组件（识别走 AE2 的 `IStorageComponent`，邻居那一层见 M6），放进/取出实时更新账目（本方块 → 核心）；组件跟着各自的模块方块存档（`getInternalInventory()` + `saveChangedInventory` → `setChanged`）
- ⬜ 实机确认（本轮不做，用户口径：不上实机）：开屏不抛、三枚按钮的落点与轮换、进度条数值与条长、**首行是模块行 / 末行是标题行时表外缘缺的 1px 边线**（见第四节）
- ⬜ 逐模块坐标标签已随行表改版去掉（模块行里没有写字的空位）；悬停模块槽仍能看到坐标（槽里那件方块物品的名字就是坐标）

### M6 槽位与配方 — ⬜ P2

- ✅ 槽位识别：只走 AE2 的公开面 `IStorageComponent`（`isStorageComponent` + `getBytes`）——AE2 `1k ~ 256k`、MEGA Cells `cell_component_1m ~ 256m`、AppFlux `core_1k ~ 256m` 都在内。本模组**不自制**组件物品；`StorageComponentTier` 只作对照表（待定项 10 的识别口径至此定稿）
- ✅ **邻居组件的识别口径已用外部证据钉死**（本机没有那两个 jar，结论取自上游源码，证据见待定项 10）：MEGA Cells 直接复用 AE2 的 `StorageComponentItem`（`implements IStorageComponent`），走接口路径即可；AppFlux 的 `core_1k ~ core_256m` 是普通 `NormalItem`、**不实现**该接口，所以必须有按注册名的退回层——`integration/appflux/AppFluxComponents`（只比注册名字符串，不引邻居类型名）
- ⬜ 合成配方：三个等级模块（基础模块「比原版能源元件略贵或相当」）
- ⬜ 数据生成或手写 JSON（本项目沿用姊妹项目口径：先手写，需要时再接 datagen）

### M7 外观与反馈 — 🔄 P2

- 🔄 **Jade 浮层已接**：走 AE2 的 `igtooltip` SPI（`META-INF/services` 注册，**不需要** `clientCompileOnly(libs.jade)`、也不需要按 mod id 门禁）；AE2 自己的 `PowerStorageDataProvider` 会免单给「储量 X / Y」一行，本模组只补「已成型 / 模块尺寸」两行。剩下的是实机看排版。（`integration/jade` 那个 `@WailaPlugin` 是例外：它要引 Jade 的 API 类型，故 `build.gradle` 里另有 `compileOnly(libs.jade)`——别照本条把依赖删了）
- ⬜ 成型后的统一边框/发光（需求 5.1 的「大立方体应有额外价值」）：连接边框已实现（合成单元那套收边规则）+ 脚本生成的线框占位贴图，正式美术与发光效果待做
- ⬜ 方块模型与纹理：占位件已落盘（`tools/gen-placeholder-textures.cjs` 生成 3 档 × 4 张，`models/block` 与 `blockstates` 已就位）；换正式美术时**保持四个文件名不变**即可，代码与模型不用动。发布前在 README 的 License and credits 节列明来源
- ⬜ 连接渲染的光照口径要对一次：本模组用 AO=true / usesBlockLight=true（画的是整块带内面的立方体），上游合成单元是 false / false；实机比一次边框与内面的明暗，不一致就按上游改并订正那段注释
- ⬜ Jade 集成（可选）：显示结构等级、尺寸、当前/总容量。**已落地**（见 M7），这里的旧口径（补 `clientCompileOnly(libs.jade)` 与 mod id 门禁）已作废——走的是 AE2 的 igtooltip SPI，全程不引 Jade 类型

### M8 工程补件 — ⬜ P3

- ⬜ 配置项（数值可调：组件折扣 k / kv、数量加成 b / bv、输入比例 f_in、速率与输入上限；照需求 §7「数值集中、便于调整」）
- ⬜ `modaccessor` / 访问转换（仅当确需访问 AE2 非公开成员时接入，届时补 `mods_accessor.cfg`）
- 🔄 开发环境已补 **Applied Generators（应用发电机）**（`maven.modrinth:y1qmWFWM:TW2EMLE6`，需 AE2 + Glodium + AppFlux）：FE 侧电源 + AppFlux 折算，用来堆 10^5 ~ 10^6 AE/t 的稳态负载。**尚未实机压测**——速率的触顶行为与 f_in 折半目前仍停在算术推演
- ⬜ 发布流程（Modrinth/CurseForge workflow、`icon.png`、`logoFile`）
- ✅ **本机专属属性已搬出仓库**：`gradle.properties` 不再写 `org.gradle.java.home` 与 jvmargs 里的 `Windows-ROOT` 信任库（Linux 上不是合法 keystore 类型、路径不存在时 Gradle 直接失败），它们只存在于本机的 `~/.gradle/gradle.properties`（不入库）。接 CI 时用命令行传 `-Dorg.gradle.java.home="$JAVA_HOME"` 与 `-Dorg.gradle.jvmargs="...trustStoreType=JKS"` 覆盖
- ⬜ 补 `icon.png` 并在元数据模板的 `[[mods]]` 段加 `logoFile`（当前缺，发布件会没图标）
- ⬜ 指南书（GuideME `ae2guide`，中英两份要同步改）
- ⬜ `docs/LOCAL_DEPS.md` 与 `docs/LOCAL_CLONES.md`（本机本地，已 gitignore）

## 二、已定数值（唯一事实源是代码里的常量类）

| 等级 | A 基础容量 | 槽位 C_tier | V 基础速率 | V_max 每块速率上限 | V_in_max 每块输入上限 |
|---|---:|---:|---:|---:|---:|
| 基础 | 200 000 AE | 1 | 1 000 AE/t | 16 000 AE/t | 8 000 AE/t |
| 致密 | 1 600 000 AE | 2 | 8 000 AE/t | 128 000 AE/t | 64 000 AE/t |
| 超密 | 12 800 000 AE | 3 | 64 000 AE/t | 1 024 000 AE/t | 512 000 AE/t |

### 公式与系数

```
D    = (A + Σ S_i × k)  × (1 + b  × C)      单模块容量（Σ 只累加**存储组件**：AE2 1k~256k、MEGA cell_component_*）
T    = Σ D_j                                 总容量（逐块求和）
D_v  = (V + Σ Sv_i × kv) × (1 + bv × C)      单模块速率（Σ 只累加**能量存储组件**：AppFlux core_1k~256m）
T_v  = min(Σ D_vj, Σ V_max_j)                总速率（上限也是逐块之和）
T_in = min(T_v × f_in, Σ V_in_max_j)         总输入速率
```

| 项 | 值 | 出处 |
|---|---:|---|
| 结构边长 | 3 – 16 | `ModularCellConstants.MIN_SIDE` / `MAX_SIDE` |
| 方块数上界 | 4 096 | `ModularCellConstants.MAX_BLOCKS` |
| 组件容量折扣 k（存储组件→容量） | 0.005（可配 0.001 ~ 0.01） | `ModularCellConstants.COMPONENT_DISCOUNT` |
| 容量数量加成 b | 0.5 | `ModularCellConstants.CAPACITY_COUNT_BONUS` |
| 组件速率折扣 kv（能量存储组件→速率） | 0.0001 | `ModularCellConstants.TRANSFER_DISCOUNT` |
| 速率数量加成 bv | 0.1 | `ModularCellConstants.TRANSFER_COUNT_BONUS` |
| 输入比例 f_in | 0.5 | `ModularCellConstants.INPUT_RATIO` |
| 组件档位（同一张对照表，两条路各取所需） | AE2 `1k ~ 256k` / MEGA Cells `cell_component_1m ~ 256m` / AppFlux `core_1k ~ 256m` | `StorageComponentTier` |

### 材料量参考（模块方块本身，不含石英/铁/红石/硅与组件）

| 边长 | 方块数 | 基础等效 | 致密等效 | 超密等效 |
|---:|---:|---:|---:|---:|
| 3 | 27 | 27 | 216 | 1 728 |
| 4 | 64 | 64 | 512 | 4 096 |
| 5 | 125 | 125 | 1 000 | 8 000 |
| 7 | 343 | 343 | 2 744 | 21 952 |
| 9 | 729 | 729 | 5 832 | 46 656 |
| 11 | 1 331 | 1 331 | 10 648 | 85 184 |
| 13 | 2 197 | 2 197 | 17 576 | 140 608 |
| 15 | 3 375 | 3 375 | 27 000 | 216 000 |
| 16 | 4 096 | 4 096 | 32 768 | 262 144 |

## 三、待定项（实现前要定，按影响面排序）

0. **混等级结构的上限口径**：✅ 已定（用户口径）——`V_max` / `V_in_max` 取**逐块提供量之和**（Σ 各块上限）。理由：上限应该是「这一段一共能传多少」，取最高档会把它变成固定值（一拼成立方体就顶格，扩建与加组件都不再有效）。
   - 后果要知道：1 块超密就能把整段的速率上限从 16 000 抬到 1 024 000，而基础块只有 1 000 AE/t——要 1024 块基础才可能触到新上限，所以混拼时上限几乎不生效。想要「上限跟主体等级走」就改成「取数量最多的那一档」，一行的事。
   - 改口径只需改 `EnergyCellModuleBlockEntity#applyStructure` 里挑 `capTier` 那几行（读档路径的重新收口在 `loadTag`，改口径时两处都要改）。
1. **立方体判定口径**：需求写「实心立方体」，尺寸又写「边长 3–16」。三边必须相等，还是允许长方体（任一边 3–16）？——建议：**只认立方体**（三边相等），实现最简单、容量公式也自洽。
2. **核心选取规则**：✅ 已定——结构内**坐标最小**的那一块（= 立方体最小角），见 `MultiblockScanner.Layout`。与「最小角」重合是有意的：找范围与找核心成了同一件事，缓存里只记一个位置。混等级不影响这条（核心的等级不参与账目，只当它是一块普通模块）。
3. **拆结构的能量归属**：整段拆掉时池里的能量去哪？——建议：留在核心方块（其余方块本就只是一份容量贡献，没有自己的能量账户）。
4. **单块是否也吃上限**：✅ 已定——单块也照公式吃自己那一档的上限（`V_max` / `V_in_max`），不开特例。公式下 N=1 时 `T_v = min(D_v, 该档 V_max)`；基础模块插 1 个 256m 组件时 `D_v = (1 000 + 268 435 456 × 0.0001) × 1.1 ≈ 30 628 AE/t`，会被这一档的 16 000 夹到 16 000。
5. **组件折扣是否开成配置项**：k 的需求给了区间（0.001 ~ 0.01，默认 0.005），kv 目前是定值 0.0001；两者要不要一起可配（甚至按整合包调）？数值已在 `ModularCellConstants`，开配置就是接一层读取。
6. **组件槽是否热插拔**：结构成型期间取出组件，容量掉到当前能量之下怎么办（AE2 原版的处理是允许超额、能量慢慢耗掉）？实现时对齐 AE2 上游行为。
7. **破坏任一方块后**：✅ 已定——破坏时先把整段清成独立元件（按被拆那块手里的范围），再从六个邻居各自重算（需求 3.2 的口径）。
8. **速率触顶的接受度**：✅ 已解决——上限改为**逐块之和**后「越建越快」自然成立，单块自己的上限只在单块时生效。要调的是每档的 `V_max` / `V_in_max`（`EnergyCellTier`）。原描述：三档 V_max 是单模块基础速率的 16 倍，而最小结构 27 块——无组件时 3³ 就已顶格，扩建后组件对速率也基本只用于补回已被上限吃掉的部分。若希望「越建越快」，要么擑高 V_max，要么让 V_max 随边长缩放。
9. **f_in 取 0.5 还是 1.0**：需求给的是「0.5 或 1.0」，当前取 0.5（充能比放电慢）。一个数就能换。
10. **邻居组件的识别口径**：✅ 已定——只走 AE2 的公开物品接口 `IStorageComponent`（`isStorageComponent` + `getBytes`），**只对确实没有实现该接口的邻居**加一层按注册名的退回（不写「物品 → 档位」的数值表，数值仍只查 `StorageComponentTier`）。已用外部证据核对（代码级证据取自与本仓库依赖坐标对齐的上游源码）：
    - **MEGA Cells 4.11.0（`maven.modrinth:jjuIRIVr:RPG4EriK`，tag `4.11.0`）**：`megacells:cell_component_1m/4m/16m/64m/256m` 直接 `new appeng.items.materials.StorageComponentItem(p, mb * 1024)` → **实现了 `IStorageComponent`**，`getBytes = mb × 1,048,576`。走接口路径即可，**不需要**退回层。（同模组的 `bulk_cell_component` / `radioactive_cell_component` 是 `MaterialItem`、不实现该接口，但也不是 M 级存储组件，不在槽位目标集合内。）
    - **AppFlux 1.21-2.1.5-neoforge（`maven.modrinth:appflux:4x40hq9D`）**：`appflux:core_1k/4k/16k/64k/256k/1m/4m/16m/64m/256m` 都是 `NormalItem extends appeng.items.AEBaseItem`（只 `extends Item`）→ **不实现 `IStorageComponent`**，接口路径必然漏判。退回层 `integration/appflux/AppFluxComponents`（命名空间 + `core_` 前缀 + 后缀查 `StorageComponentTier`）**必需且已实现**。
    - AppFlux 的 `core_<档>` 里 k/m 指的是**字节（KiB）**（`ItemFECell` 构造 `kilobytes * 1024L`），与 AE2 组件的字节口径一致，所以能共用同一张档位表；`core_*` 本身不带 FE 能力，是它自产 FE 元件的合成材料。
11. **终端样式档位 → 面板条数**：✅ 已定但可推翻——SMALL / MEDIUM / TALL / FULL → **4 / 6 / 8 / 填满窗口**条 18px 带（下限 4、上限 12，由 `EnergyModuleSlotSemantics.MIN_ROWS/MAX_ROWS` 定）。AE2 自己的 `TerminalStyle#getRows` 是「放得下几条的分数制」（1/4、2/4、3/4、全满），这里的行是固定 18px，直接给条数更好懂；想改回分数制只需改 `EnergyModuleTerminalScreen#targetBands` 一个方法。
12. **频道占用**：✅ 已定——**不占频道**（与 AE2 上游一致）。上游的能源元件（普通与致密共用 EnergyCellBlockEntity）建节点时只挂 setIdlePowerUsage(0) 与 IAEPowerStorage / IGridTickable 服务，不加 REQUIRE_CHANNEL；全 AE2 用该旗标的是 Drive、ME 箱、合成单元、空间塔、P2P、接口、样板供应器等设备，能源元件不在其中。本模组照此：整段由核心接进网格，但核心不要求频道；非核心块靠 getGridConnectableSides 返回空集，本来就不接网格（只有一个网络节点）。
    - 若将来要改成占频道：只能在建节点前设（createMainNode() 覆写里 setFlags(REQUIRE_CHANNEL)）——创建后调 setFlags 会抛 IllegalStateException，且改不到已建的节点。
    - 后果要知道：一旦真加了这个旗标，频道不足时该段会被判为未激活、能量池不参与网格能量平衡。
13. **开发环境测试 mod：建筑手杖**：原作 Construction Wand 只发到 1.20.2/Forge，开发环境挂的是它在 1.21.1/NeoForge 上的续作 **Construction Wand - KOTS**（`maven.modrinth:construction-wand-kots:cFpoCw3t`，只进 `clientLocalRuntime`，**不引 API**）——用途只是实机看「用建筑手杖对着本模组方块延伸摆放」的观感。

## 四、已知欠账 / 有意不做

- **发布流程未接入**：尚未创建 `.github/workflows/`，本仓暂无 tag 发版流程（M8）。
- **指南书已建**：`assets/ae2modularcells/ae2guide`（英/中两套 + 导航页），终端左侧「打开指南」直接指向它（样式文档的 `helpTopic`）。文案与显示名随改名同步（M8 剩下的是排版与插图）。
- **访问转换已接入**：`src/main/resources/META-INF/accesstransformer.cfg`（目前只开 `Slot.x/y` 去 final，见 `docs/ARCHITECTURE.md` §6）。
- **占位贴图是线框**：十二张方块贴图（3 档 × 4 张）由 `tools/gen-placeholder-textures.cjs` 脚本生成，只为「看得出连接关系是否生效」，不是美术。
- **界面只看账目，不看能量**：管理终端显示的等级、边长、容量与速率是真的（走 `CellMath`），当前能量与网格接入是 M4 的事。
- **本阶段未经实机验证**：正确性止于编译通过与屏幕样式自检（退出码 0）。槽位与覆盖层的 1px 级落点、滚动条滑块位置、连接边框的观感都要在 `runClient` 里对过才算数——相关数值都是按贴图像素推出来的（推导过程写在样式文档的 `$comment` 里）。
- **带格线的行带缺两张「首/末」变体**：贴图里只有 `rowTextTop`（自带顶边框）与 `rowSlotsBottom`（自带底边框）带表外缘；其余 4 张没有。行表动态化后会出现「首行是模块行」（取 `rowSlotsTop`）或「末行是标题行」（取 `rowTextBottom`），这两种情形下表外缘缺 1px 边线。修法二选一：补画 `rowSlotsTop`（带顶边框）与 `rowTextBottom`（带底边框）两张，或把外缘改成屏幕单画的 1px 线（与角色解耦）。
- **组件槽对总线是「只写面」**：`Capabilities.ItemHandler.BLOCK` 已给三档模块方块实体注册，口径是「只进不出 + 只收存储组件 + 仅成型后开口」（`common/inventory/ComponentInsertOnlyItemHandler`）。导出/输出总线能把组件塞进去，导入/存储总线会「看得见组件、但永远搬不动」——这是有意为之，不是 bug。
- **Jade 的 FE 能量条屏蔽靠优先级**：`integration/jade/ModularCellsJadePlugin` 给本模组方块实体注册一个空的能量存储扩展，抢在 Jade 本体那条通用 FE 提供者之前返回空组（上游 `CommonProxy#getServerExtensionData` 取第一个非 null 组）。这条只在源码层面确认过（Jade 15.10.6），未实机验证——`runClient` 里对着模块方块开一次 Jade 就能确认；条子没消失就把优先级显式传进注册 API。
- **输入速率是实测值、不是上限**：界面那根输入条读的是「每 tick 实际收进的 AE」，显示口径是**窗口内峰值 + 线性衰减**（10 tick 窗口：持续供能时保持满值（采样晚一拍也按满值算，避免抖动），停供后 0.5 秒内线性回落，不再硬保持到点突然归零）；AE2 的 `Actionable.SIMULATE` 试探不入账。速率侧只认 AppFlux 的 `core_*` 组件（AE2/MEGA 组件只加容量、不加速率），且 `T_in = min(T_v × f_in, Σ V_in_max_j)` 会被逐块之和的上限夹住。
  - ✅ **上限是真限制（口径：上限不取消）**：**FE/外部路径**（经 AE2 的 `injectExternalPower` 落到 `funnelPowerIntoStorage`）按每 tick 剩余额度 `T_in − 已收` 收口，超出的原样退回。
  - ⚠️ **AE 网格注入不受限（已核）**：AE2 的 `EnergyService` 直接调 `injectAEPower`，而它在 `AEBasePoweredBlockEntity` 上是 `final`（`extractAEPower` / `getAEMaxPower` 同）→ 子类无法限速；要让网格侧也受 `T_in`、放电侧受 `T_v` 约束，只能不再继承 AE 的 powered 基类、自己实现 `IAEPowerStorage`（结构性改造，待定）。目前 `T_v` 与 `T_in` 对网格侧只是显示值。
  - ⬜ 若希望「越建越快/加组件就变快」，要动的是每档的 `V_max` / `V_in_max` 数值本身（`EnergyCellTier`）；当前口径就是 `T_v = min(Σ D_vj, Σ V_max_j)`、`T_in = min(T_v × f_in, Σ V_in_max_j)`。
- **本轮（浮层两行、计数模式多行 tooltip、文字坐标、未成型右键、组件槽自动化、速率窗口、首帧重同步）未经实机**：正确性止于 `./gradlew build` 与屏幕样式自检（退出码 0）。首帧那条的修法是「行表第一次到手时向服务端要一次重同步」，与拖滚动条走的是同一条路；1px 级落点与观感仍需在 `runClient` 里对一遍。
- **手势（Shift + 右键）**：对成型的模块结构手上拿着存储组件 / 能量存储组件时，先填空槽，没空槽再换「同种、更低档」的，最后换「另一种、同档」的；扫描顺序从核心起，换下的组件进背包（塞不下就掉在脚上）。动作栏会报「已装入 / 已替换为 / 没有可装入或可替换的组件槽」。档位标尺用 `StorageComponentTier`（两类组件共用同一套序列）。**尚未实机验证**。创造模式下这个手势也照常消耗手持组件（未与 AE2 自身动作对齐，待定）。
- **卡死修复（待实机确认）**：结构查询一律先在 `isLoaded` 之后才问区块——`resolveCore` / `moduleAt` / `tierOfModuleAt` / `getConnections` 都已加守卫，终端每拍同步在核心不在场时直接早退。病因推断与验证方式：日志停在 `Saving worlds` 不再续写、无崩溃产物（软卡死）；要确认就复现卡死瞬间抓一次 `jstack`，看 Server thread 栈顶是否落在 `resolveCore` / `ServerChunkCache.getChunk`。另外两处已知代价：① 邻块所在区块**未加载**时 `getConnections()` 不连线，区块边界的连接边框会短暂断开（不加载区块只能如此）；② 核心区块被卸时终端**不关屏**也不同步（只认「核心真的被拆」才关屏）。MEGA Cells 的组件若 `getBytes` 与 `StorageComponentTier` 的数值序列对不上，Shift 手势会静默失效（只报「没有可装入或可替换的组件槽」）——实机确认后再决定是否加「取不大于它的最大档」兜底。
- **不做**：不改 AE2 原版能源元件的行为；不把能量存进每个方块（单一能量池是设计前提）；不在检测里上 tick 轮询。
