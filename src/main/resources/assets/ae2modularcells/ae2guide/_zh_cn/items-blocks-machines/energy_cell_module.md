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

模块化能量单元用来存 ME 能量。一共三个等级：

* **基础**：200 kAE，1 个组件槽
* **致密**：1.6 MAE，2 个槽
* **超密**：12.8 MAE，3 个槽

物品提示会列出这一档的基础储能、基础输入速率上限与组件槽数，按当前的计数模式显示（在管理终端里切）。

## 拼成结构

紧挨着摆成实心立方体，边长 3×3×3 起步，最大 16×16×16。等级可以混拼：每块按自己那一档的容量与传输速率计账，速率和输入的上限也是逐块相加，不是取某一档的固定值。

拼好之后，整个结构作为一份能量存储工作，一份账目、一条通道，任意一面都能接到 ME 网络上。

单块、或摆不成立方体的一堆不算结构。它照样能存 AE，但那份能量只属于它自己，只能用 FE 灌进去，ME 网络连不上它、也取不出里面的能量。拼成立方体那一刻，各块已有的存量汇进结构的池子。容量与速率怎么算，见[增益算法](../gain-algorithm.md)。

## 组件槽

每块模块自带组件槽，所以一个结构能装的组件总数 = 各块槽位数之和。插**存储组件**可以提升容量与传输速率，可选：

* AE2 的 1k ~ 256k
* MEGA Cells 的 1m ~ 256m
* AppFlux 的 core_1k ~ 256m

终端里不按块显示：一档共用一行 12 格，**一种组件占一格**（同种可以叠在一起，格子里写件数；12 格占满就自动往下延伸一行）。放进哪一格都一样，系统自己把新组件摊到该档各块的空槽上。容量按块独立计入、整个结构逐块求和，插满还有数量加成。组件跟着它所在的那块模块一起存档。

## 模块管理终端

对**已成型**的结构点右键即可打开终端。

终端按等级分组，每组一条标题行（等级图标 + 该档模块数 + `已装配存储组件：a/b`），下面跟该档的组件行。右击标题图标选中该组，之后快速移动只会把组件放进这一组。

左侧三枚按钮分别是：

* 打开本指南
* 切换终端样式（短 / 中等 / 长 / 最长，决定面板显示几条行）
* 轮换数字显示方式：字母（`12.3M`）、科学（`1.23E7`）、工程（`12.3E6`）
