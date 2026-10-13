# AGENTS.md

本仓库的**文档地图 + 动手前必读的硬约定**。给进入这个仓库的 AI 代理（pi / Claude Code / Cursor…），也给人看。
**先读本页再动手**：这里只做索引，细节都在被指向的文档里，本页不重复。

## 这是什么

AE2（应用能源 2）的附属模组 `ae2modularcells`：模块化能源元件。把模块方块拼成实心立方体（等级可混拼；3×3×3 起、最大 16×16×16），整段作为一个整体能量存储接入 ME 网络；每块自带 1/2/3 个存储组件槽，容量按块独立加成、能量池共享。

> **当前状态：骨架阶段。** 仓库里有构建脚手架、文档体系与注册入口，方块与方块实体尚未实现。实施顺序见 `docs/TODO.md`。

**基线：Minecraft 1.21.1 · NeoForge 21.1.241 · AE2 19.2.18 · Java 21**
权威来源是 `gradle/libs.versions.toml` 与 `gradle.properties`，这一行只是速览。

## 一、规则类文档（进 git，改代码前必读）

| 文档 | 什么时候读 |
|---|---|
| `docs/ARCHITECTURE.md` | 改任何代码前。分层、目录职责、结构检测与能量接入的口径、性能红线 |
| `docs/TODO.md` | 想知道「这事做没做、为什么没做、数值是多少」时：实施顺序、平衡表、待定项 |
| `docs/CHANGELOG_STYLE.md` | 改 `CHANGELOG.md` 前：格式被发版流程约束，`## [` 是段落边界 |
| `CHANGELOG.md` | 发行说明（版本段会被取为发布文案） |
| `README.md` | 玩法自述（英文，商店页与 GitHub 首页） |

## 二、信息类文档（**本机本地，已 gitignore**，不随仓库分发）

| 文档 | 内容 |
|---|---|
| `docs/LOCAL_DEPS.md` | 本机路径索引：`libs/*.jar` 各从哪来、兄弟仓库快捷方式指向哪、gradle 缓存在哪（**待建**） |
| `docs/LOCAL_CLONES.md` | 上游源码浅克隆的分支基线（**待建**；具体路径与克隆清单属本机信息，写在那份文档里） |

## 三、动手前必须知道的硬约定

- **语言**：注释、`docs/` 与 `CHANGELOG.md` 一律中文；对外英文文案（`README.md`）与代码标识符除外。
- **只向上游读，不向上游写**：不修改 AE2 原版代码与其能源元件行为；接入走公开 API（`IAEPowerStorage` 等）。
- **能量面口径**：AE2 的能源元件**不走**平台 `IEnergyStorage`，走网格的 `IAEPowerStorage` + `IEnergyService`。写这条是防走错路，详见 `docs/ARCHITECTURE.md` §2.5。
- **性能红线**：模块方块实体**不注册 ticker**（`BlockEntityType` 带了 ticker，NeoForge 每 tick 就会逐台调用 `tick()`，函数体里写 `return` 省不了那次调度）；真要周期工作就挂 AE2 的 `IGridTickable`。结构检测只在放置/破坏/组件变化时触发，绝不轮询；单次扫描上界 4096 方块；网络节点只暴露一个。
- **数值集中**：容量、速率、槽位数与每块上限只在 `EnergyCellTier` / `StorageComponentTier` / `ModularCellConstants` 里。五个公式系数分两类：k / kv 乘的是组件自己带来的值，属于组件常量，只在 `ModularCellConstants` 定义、**不进配置**；b / bv / f_in 描述结构规模与输入口径，`ModularCellConstants` 里只作**默认值**，运行时取 `common/config/ModularCellConfig`。公式只在 `common/logic/CellMath` 实现一次，其他地方不写第二份字面量。
- **集成红线**：`integration/<邻居>` 只写那个邻居的适配；**邻居的类型名不得漏进总是加载的类**——缺那个模组时会在加载阶段炸。门禁写在独立小类里。
- **「名字即协议」**：`registerClientAction("...")` 的动作名与 `@GuiSync(n)` 的注解值靠客户端/服务端配对，不能改、也不能被第二个字段复用；方块实体 NBT key 同理。
- **屏幕样式文档是唯一布局源**：代码按 id 取控件，文档没声明就会在开屏时抛异常踢玩家。改完跑：
  ```bash
  node tools/check-screen-widgets.cjs .
  ```
  退出码 0 = 通过；1 = 有未声明；**2 = AE2 公共段落未取到、部分项无法判定，不算通过**。
- **没有测试代码**：验证靠构建 + 实机（`runClient` / `runServer`）。
- **模板即事实源**：模组元数据改 `src/main/templates/META-INF/neoforge.mods.toml`，不要手改 `build/` 下的生成件。

## 四、常用命令

```bash
./gradlew build              # 全量构建
./gradlew compileJava        # 只编 main（服务端/通用）
./gradlew compileClientJava  # 只编 client
./gradlew runClient          # 实机验证（开发运行期配方查看器默认 JEI，-Pemi 切 EMI）
./gradlew runServer
./gradlew runData            # 数据生成
node tools/check-screen-widgets.cjs .   # 屏幕样式自检
```

Windows 上同样四条命令另有启动脚本（仓库根）：`run_client.bat` / `run_client_emi.bat` / `run_server.bat` / `run_data.bat`——比命令行多带一个 `--no-configuration-cache`，避免用缓存里的旧 classpath 启动（症状是「刚加的开发环境 mod 不生效」）。

## 五、读上游源码之前

- 位置与分支见 `docs/LOCAL_CLONES.md`（**待建**：上游各仓的镜像路径与分支基线都记在那份文档里，可从姊妹项目 AE2 Pattern Disk 的同名文档抄一份，再按本机实际路径改）。
- **别信 gradle 缓存里那份 AE2 sources jar**：那是别的 Minecraft 线的构建，读错版本会得出「某方法有 / 没有」这类反向结论。
- 本项目的接入点都在 AE2 的公开面：`appeng/api/networking/energy/IAEPowerStorage.java`、`IEnergyService`，以及 AE2 自己那台能源元件的方块实体（照它写最稳）。
