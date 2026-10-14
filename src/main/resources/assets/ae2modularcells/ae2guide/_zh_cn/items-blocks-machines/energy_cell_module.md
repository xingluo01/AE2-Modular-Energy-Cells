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

存储 ME 能量的模块方块。三个等级，容量与组件槽逐级递增：

* **基础**：200 kAE，1 个组件槽
* **致密**：1.6 MAE，2 个槽
* **超密**：12.8 MAE，3 个槽

## 拼成结构

<GameScene zoom="3" background="transparent">
  <ImportStructure src="../assets/assemblies/module_cube_3x3x3.snbt" />
  <IsometricCamera yaw="195" pitch="30" />
</GameScene>

把模块贴在一起摆成实心立方体，边长 3×3×3 到 16×16×16，你可以混拼等级。每块贡献自己那一等级的容量、传输速率与输入上限，结构把它们加起来。

成型后整段就是一份能量存储。模块方块的任意一面都能接进你的 ME 网络。

单独一块，或者永远摆不成立方体的一堆，都不算结构。它照样存 AE，但只能经 FE 充放：你的 ME 网络既连不上它，也取不出里面的能量。立方体成型那一刻，各块已有的存量并入结构。公式见[增益算法](../gain-algorithm.md)。

## 组件槽

每块自带组件槽，结构能装的总数就是各块槽位之和。插**存储组件**可以提升容量与传输速率：

* AE2 的 1k ~ 256k
* MEGA Cells 的 1m ~ 256m
* AppFlux 的 core_1k ~ 256m

终端按等级分组：每个等级 12 格，一种组件占一格。同种会在格子里叠起来并显示件数，一行占满就续到下一行。放进哪一格都一样——新组件会分到该等级各块的空槽上。容量按块计入再求和，插满还有数量加成。你插进去的组件随那块模块一并存档。

## 模块管理终端

对**已成型**的结构点右键，打开终端。

终端按等级分组。每组一条标题行，写着等级图标、该等级模块数和 `已装配存储组件：a/b`，下面是该等级的组件行。右击标题图标选中这一组，之后快速移动只会放进这一组。

左侧三枚按钮：

* 打开本指南
* 切换终端样式（短 / 中等 / 长 / 最长）
* 轮换数字显示方式：字母（`12.3M`）、科学（`1.23E7`）、工程（`12.3E6`）
