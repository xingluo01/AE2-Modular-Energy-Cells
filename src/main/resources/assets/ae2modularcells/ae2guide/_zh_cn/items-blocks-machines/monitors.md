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

两台只显示的面板。它们参与成型判定，但不增加容量或速率，放进去既不削弱结构，也不抬高读数。

监控器不持有结构的账目，所以每一座结构至少要有一块模块：全是监控器的立方体不成型。核心由模块担任，取布局序里的第一块，位置随意。

## 储能监控器

正面显示结构的储能百分比，居中一行。

## 能量流量监控器

正面中部是一枚 FE 图标，读数在下沿：结构近一秒的平均吞吐（AE/t）。装了 Applied Flux 就取它的「FE」贴图，没装就用本模组自带的。

正面不显示瞬时输入速率——它一直在跳，挤在平均值旁边看不清。需要即时值的话，打开终端。

## 成型前后

<Row>
  <BlockImage id="energy_monitor" scale="4" p:formed="true" />
  <BlockImage id="flow_monitor" scale="4" p:formed="true" />
</Row>

未成型时它是一台独立面板：正面是自己的面板，另外五面是素面，没有边框。

成型后正面换为基础面板，颜色取 AE2 默认涂色的三档明暗，不随储能变化。五面改用另一套底色，并按邻块收边：贴着方块的一面没有边框，露在外面的一面有，两条边相交的地方补角贴图。

## 储能档位

五级：0% / 25% / 50% / 75% / 100%。核心会记录档位，但正面不随它变化。

切档可能重置正面的挖掘进度，所以要连续两次采到同一档才切。

## 读数从哪来

数字来自核心，每秒更新一次。

监控器不是 ME 设备：ME 线缆要接在模块方块上，接在监控器上不通。监控器自己不存能量；你接在它上面的 FE 连接会转给核心。
