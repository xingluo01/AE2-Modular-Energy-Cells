# 第三方资产与来源

本仓库里有一部分素材不是本项目画的。搬运时都记在这里——改这些文件之前先读一遍，别把来源与许可弄丢。

| 资产 | 来源 | 许可 | 作者 |
|---|---|---|---|
| `assets/ae2modularcells/textures/block/fe_icon.png` 与同名 `.mcmeta` | [AppFlux](https://modrinth.com/mod/appflux) 的 `assets/appflux/textures/energy/fe.png` | LGPL-3.0 | GlodBlock |

FE 那枚金色闪电是 AppFlux 画的。它用在**能量流量监控器**正面：装了 AppFlux 时直接取它的方块图集 sprite（`appflux:energy/fe`，同样是动画），没装才用仓库里这一份。两张都是 32 帧、每 2 tick 换一帧。

两张都走方块图集的 sprite（本模组那份靠 `assets/ae2modularcells/atlases/blocks.json` 进图集），所以它是**帧动画**而不是静止贴图——想改回静态的话，`MonitorRenderer#drawIcon` 里那条注释写了为什么不能直接绑纹理。
