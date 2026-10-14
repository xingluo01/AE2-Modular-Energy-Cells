# CurseForge 发版清单（本仓）

> 面向维护者。CurseForge 的项目描述**没有可写的公开 API**（官方需求单 CF-I-6366 至今未落地），所以项目页上的摘要与正文只能人工在站点粘贴；jar 与 changelog 由 `.github/workflows/release.yml` 在打 tag 时自动上传。
> 本仓当前只接 CurseForge，Modrinth 未接入（见 §六）。

## 一、谁做什么

| 环节 | 谁做 | 说明 |
|---|---|---|
| 构建 jar | GitHub Actions | `./gradlew build -Pmod_version=<tag 去掉 v>` |
| 上传文件（jar + changelog + 依赖关系 + 游戏版本） | GitHub Actions | tag `v*` 触发 |
| GitHub Release | GitHub Actions | 与 CF 同一份 changelog |
| 发布后修正**文件级**元数据（依赖 / 发布类型 / 显示名 / 日志 / 游戏版本） | GitHub Actions（**手工触发**） | 见 §七 |
| 项目页摘要 / 正文 / 分类 / 图标 | **人工** | 站点上操作，正文直接粘贴 `docs/PLATFORM.md` 全文 |
| 发布前自检 | 人工 | 见 §八 |

## 二、项目页设置（一次性，站点手工）

| 字段 | 取值 | 出处 |
|---|---|---|
| 项目名 | AE2 Modular Energy Units | `gradle.properties` 的 `mod_name` |
| Summary | `gradle.properties` 的 `mod_description` 原文（当前 196 字符） | 与模组元数据同源，改一处即可 |
| Description | [`docs/PLATFORM.md`](PLATFORM.md) **全文**（英文） | 该文件是项目页正文的唯一事实源；站点改过的手改内容下次粘贴会被覆盖 |
| 分类 Categories | 站点可选项为准——语义上最接近的是「能源 / 存储」这类科技向分类 | 具体项名以站点列表为准，本文件不臆造 |
| Environment | Client + Server | 本模组双端都要装：能量池、结构与配置都在服务端 |
| License | LGPL-3.0 | `gradle.properties` 的 `mod_license`；`LICENSE` 即 LGPL-3.0 全文，且随 jar 打包在 `META-INF/LICENSE` |
| Logo | `src/main/resources/ae2modularcells-icon.png`（400×400） | 与 jar 内 `iconFile` 同名同图；banner 位 `ae2modularcells-banner.png`（800×800，宽幅位会居中留白） |

**项目页上的依赖关系**（与 `src/main/templates/META-INF/neoforge.mods.toml` 的必需依赖一一对应）：

| 模组 | 关系 | CF slug（= 项目 URL 末段） |
|---|---|---|
| Applied Energistics 2 | required | `applied-energistics-2` |
| GuideME | required | `guideme` |
| MezzConfig | required | `mezzconfig` |

三个都是必需依赖：缺 AE2 没有网络，缺 GuideME 与 MezzConfig 在加载阶段就起不来。三个项目在 CurseForge 上都有页面（slug 取自各自项目 URL），所以文件里可以直接声明依赖关系。

## 三、每个上传文件的字段

| 字段 | 取值 | 依据 / 注意 |
|---|---|---|
| `file_path` | `build/libs/ae2modularcells-<version>.jar` | `build.gradle` 里 `base.archivesName = mod_id` |
| `game_endpoint` | `minecraft` | action 的固定值：决定往 curseforge.com 的哪个游戏子域上传 |
| `display_name` | `AE2 Modular Energy Units <version> [NeoForge 1.21.1]` | 与 `mod_name` 一致，便于站点上按文件名筛选 |
| `release_type` | 默认 `alpha`；仓库变量 `CURSEFORGE_RELEASE_TYPE` 可覆盖为 `beta` / `release` | 开发中构建按 `alpha` 发，玩家会看到「Alpha」标记；调平衡后改发 `release` 只需改变量 |
| `game_versions` | `11779,10150,9638,9639` | 见下方拆解 |
| `changelog` | `CHANGELOG.md` 里当前 tag 那一版段落 | 取法见 `docs/CHANGELOG_STYLE.md`；取不到时 CI 退回按提交主题拼接 |
| `changelog_type` | `markdown` | 与 GitHub Release 同口径 |
| `relations` | `applied-energistics-2:requiredDependency,guideme:requiredDependency,mezzconfig:requiredDependency` | 格式为 `slug:关系类型`，逗号分隔；语义与 §二的依赖表一致 |

**`game_versions` 数字 id 拆解**（官方传统接口 `GET /api/game/versions` 的编号，与 Eternal API 的另一套编号不同）：

| id | 含义 | 证据 |
|---|---|---|
| `11779` | Minecraft 1.21.1 | 外部仓库的两个独立 workflow 里都能查到（`70CentsApple/ChatTools`、`GregTaoo/Concerto` 的版本映射表） |
| `10150` | Modloader: NeoForge | `70CentsApple/ChatTools/.github/workflows/release.yml` 的注释逐条列出该编号 |
| `9638` | Environment: Client | 同上注释 |
| `9639` | Environment: Server | 同组编号；**CF 要求至少勾一个环境组项**，否则上传报 `You must select at least one version from the environment group of versions` |

上传 `itsmeow/curseforge-upload` 这个 action 时也可以写名字（如 `'1.21.1,NeoForge,Client,Server'`），它会自己查表换算——但**匹配不到时是静默丢弃**，所以这里固定写数字 id。要复核时打开 <https://minecraft.curseforge.com/api/game/versions?token=你的令牌> 对照。

## 四、仓库里要先配好的东西

| 类型 | 名称 | 用途 |
|---|---|---|
| Secret | `CURSEFORGE_TOKEN` | <https://www.curseforge.com/account/api-tokens> 生成 |
| Variable | `CURSEFORGE_PROJECT_ID` | CF 的**数字**项目 id（项目页侧栏可见，不是 slug） |
| Variable（可选） | `CURSEFORGE_RELEASE_TYPE` | 覆盖默认的 `alpha` |

`GITHUB_TOKEN` 由 Actions 自动提供，用于建 GitHub Release，不需要手工配。

CF 项目必须**先在站点人工创建**（自动化只能往已有项目传文件），且第一个文件要通过人工审核才对玩家可见。

## 五、发版步骤

1. 改 `gradle.properties` 的 `mod_version`。
2. 定稿 `CHANGELOG.md`：把 `## [未发布]` 改成 `## [<版本>] - <YYYY-MM-DD>`（版本标题行本身不进发布文案，由 CI 注入，别手写标题）。顺带删掉段内的注释行——取段时注释会原样进发布文案。
3. 提交并推 `main`。
4. 打 tag `v<版本>` 并推：`git tag v0.1.0 && git push origin v0.1.0`。CI 第一步就是校验 tag 与 `gradle.properties` 的 `mod_version` 是否一致，不一致直接失败（防止 tag 打错或忘改版本号）。
5. 看 Actions 日志里的 `Collect changelog` 一行：`changelog source: CHANGELOG.md` 表示取段命中；若是 `commit log`，说明版本段标题没对上，去核体例。
6. 上传后到 CF 上核对三件事：文件状态（首个文件要等审核）、依赖关系是否真的挂上了、游戏版本标签是否含 Client/Server。
7. 顺手核对项目页摘要与正文是否还是最新（正文 = `docs/PLATFORM.md`）。

## 六、重跑、回退与 Modrinth

- **同一个 tag 重跑会怎样**：CF 不去重，会**再传一份重复文件**。所以发布中断时优先**改版本号重发**，不要直接重跑 workflow。删文件只能在站点手工操作。
- **Modrinth 未接入**：那边要 `minotaur` 插件 + `MODRINTH_PROJECT_ID` + PAT，项目描述的同步还得另配一个 workflow（姊妹项目 AE2 Pattern Disk 的 `.github/workflows/sync-project-pages.yml` 是现成样板，用 `PATCH /v2/project` 把 `docs/PLATFORM.md` 推上去）。本仓还没做，要做时照它抄。

## 七、发布后修正已上传的文件

上传之后要改**文件级**元数据（依赖关系、发布类型、显示名、更新日志、游戏版本），用 `.github/workflows/cf-update-file.yml`（仅 `workflow_dispatch`，手工触发）：

1. Actions → **Update CurseForge file** → Run workflow。
2. `file_id` 填 CF 文件页 URL 末尾的数字（**不是**版本号）；`dry_run` 保持默认的 `true` 先跑一次，核对日志里打印的 metadata。
3. 确认无误后只把 `dry_run` 改成 `false` 再跑一次：`relations` 写 `slug:类型` 逗号分隔（与 `release.yml` 的写法一致），`game_versions` 写数字 id 逗号分隔。

两条注意：

- **除 `file_id` 外至少要给一项字段**，只填 `file_id` 会被工作流拦下（官方接口对空改动也失败）。
- 给 `relations` 等于**重设这个文件的依赖集合**，不是追加——接口只有「设置」语义，没有增量与删除端点。改依赖时请照 §二 那张表把三个必需依赖（`applied-energistics-2` / `guideme` / `mezzconfig`）写全，否则没写的那几条会被抹掉。工作流会把将要发送的 metadata 与「依赖设为 N 条」先打印出来供核对。

依据是 CF 官方《CurseForge Upload API》的 Project File Management API（`POST /api/projects/{projectId}/update-file`，multipart 只带 `metadata` 字段），认证用与上传同一个令牌。**新版 v1（Eternal）API 的 `file.dependencies` 只有只读字段、没有写接口**；项目页的 Summary / Description 两边都写不了（CF-I-6366），只能人工粘贴。

从本机直连这条接口可能被 Cloudflare 挑战页挡下（实测 403 + `Just a moment...`），所以走 Actions：runner 上一般不被拦。工作流会认出挑战页并给出对应提示，而不是把它报成「令牌不对」。

## 八、发布前自检

- [ ] `mod_version` 与要打的 tag 一致；`CHANGELOG.md` 的段落日期填了当天。
- [ ] `mods.toml` 的两个 `version_range` 仍覆盖目标环境（`minecraft_version_range` / `neo_version_range`）。
- [ ] 项目页正文与 `docs/PLATFORM.md` 无差异；摘要与 `gradle.properties` 的 `mod_description` 无差异。
- [ ] 依赖关系与 `mods.toml` 的必需依赖表一致（三个必需项见 §二）。
- [ ] 借用的美术素材已列进 `README.md` 的 License and credits（当前为空，没有借用件）。
- [ ] 贴图仍是 `tools/gen-placeholder-textures.cjs` 生成的占位件时，正文与站点都不得声称美术已完工。
- [ ] 指南书（`assets/ae2modularcells/ae2guide/`）的中英两份都跟着本次改动更新了——玩家点「打开指南」看到的是它。
- [ ] 要发正式版就把 `CURSEFORGE_RELEASE_TYPE` 改成 `release`（默认是 `alpha`）。
