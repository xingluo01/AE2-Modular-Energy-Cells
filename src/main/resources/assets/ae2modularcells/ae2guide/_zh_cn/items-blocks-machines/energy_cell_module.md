---
navigation:
  parent: index.md
  title: 模块化能量单元
  icon: basic_energy_cell_module
  position: 1
item_ids:
- ae2modularcells:basic_energy_cell_module
- ae2modularcells:dense_energy_cell_module
- ae2modularcells:superdense_energy_cell_module
categories:
- devices
---

# 模块化能量单元

<Row gap="20">
  <ItemImage id="basic_energy_cell_module" scale="4" />
  <ItemImage id="dense_energy_cell_module" scale="4" />
  <ItemImage id="superdense_energy_cell_module" scale="4" />
</Row>

模块化能量单元用来存 ME 能量。一共三个等级：**基础**（200 kAE、1 个组件槽）、**致密**（1.6 MAE、
2 个槽）、**超密**（12.8 MAE、3 个槽）。

## 拼成结构

把能量单元紧挨着摆成实心立方体，边长 3×3×3 起步、最大 16×16×16。**等级可以混拼**——每块按自己那一
档的容量与传输速率计账，上限取出现过的最高档。整段随即作为一份能量存储工作：一个网络节点、一个能量池，
任意一面都能接到 ME 网络上。

单块、或摆不成立方体的一堆不算结构——它照样能独立工作，只是打不开管理终端。

## 组件槽

每块能量单元自带组件槽。插**存储组件**（AE2 的 1k ~ 256k、MEGA Cells 的 1m ~ 256m、AppFlux 的
core_1k ~ 256m）可以提升容量与传输速率。组件按块独立计入，整段逐块求和；插满还有数量加成。组件跟着它
所在的那块能量单元一起存档。

## 模块管理终端

对**已成型**的结构点右键即可打开终端。终端列出结构里的能量单元与它们的组件槽，并显示整段的储能与输入速
率。单元按等级分组，每组一条标题行（等级图标 + 该组数量）——右击标题图标选中该组，之后快速移动只会把组
件放进这一组。左侧三枚按钮分别打开本指南、切换终端样式（短 / 中等 / 长 / 最长，决定面板显示几条行）、以及
轮换数字的显示方式：字母（`12.3M`）、科学（`1.23E7`）、工程（`12.3E6`）。
