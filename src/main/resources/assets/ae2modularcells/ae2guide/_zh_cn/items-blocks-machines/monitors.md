---
navigation:
  parent: index.md
  title: 监控器
  icon: flow_monitor
  position: 2
item_ids:
- ae2modularcells:energy_monitor
- ae2modularcells:flow_monitor
categories:
- devices
---

# 监控器

<Row>
  <BlockImage id="energy_monitor" scale="4" />
  <BlockImage id="flow_monitor" scale="4" />
</Row>

两台只显示、不产能的面板。参与成型判定，但不增加容量或速率，因此放入结构既不影响成型，也不改变读数。

监控器不持有结构的账目，结构内至少需要一块模块：全部由监控器组成的立方体不成型。核心由模块担任，位置任意，取布局序中的第一块模块。

## 储能监控器

正面显示结构的储能百分比，居中一行。

## 能量流量监控器

正面中部为 FE 图标，读数位于下沿：结构近一秒的平均吞吐（AE/t）。图标取自 Applied Flux 的「FE」贴图，未安装该模组时使用本模组自带贴图。

瞬时输入速率不显示在正面：它逐 tick 波动，正面空间不足，两行难以辨识。需要瞬时值请查看管理终端。

## 成型前后

<Row>
  <BlockImage id="energy_monitor" scale="4" p:formed="true" />
  <BlockImage id="flow_monitor" scale="4" p:formed="true" />
</Row>

未成型时为独立面板：正面是自己的面板贴图，其余五面为素面，无边框，此时没有可监控的对象。

成型后正面换为基础面板，颜色取 AE2 默认涂色的三档明暗，不随储能变化。五面改用另一套底色，并按邻接情况收边：紧贴方块的一侧无边框，暴露的一侧有边框，两边相交处使用角贴图。

## 储能档位

档位分五级（0% / 25% / 50% / 75% / 100%），由核心记录，不影响正面外观。档位切换需连续两次采样都落在新档位，否则比例在档位边界波动时会反复刷新，而每次刷新都会重置客户端的挖掘进度。

## 读数从哪来

核心每 20 tick 计算一次结构的储能比例与输入均值，写入监控器的方块实体，再同步给客户端。监控器没有 ticker，也没有网格节点。

监控器不存储能量；接在其上的 FE 连接原样转交给核心。
