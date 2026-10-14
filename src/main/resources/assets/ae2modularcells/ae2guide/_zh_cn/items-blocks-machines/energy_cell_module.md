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

存储 ME 能量的模块方块，三个等级，容量与槽位递增：

* **基础**：200 kAE，1 个组件槽
* **致密**：1.6 MAE，2 个槽
* **超密**：12.8 MAE，3 个槽

## 拼成结构

<GameScene zoom="3" background="transparent">
  <ImportStructure src="../assets/assemblies/module_cube_3x3x3.snbt" />
  <IsometricCamera yaw="195" pitch="30" />
</GameScene>

实心摆放成立方体即成型，边长 3×3×3 至 16×16×16，等级可混拼。容量、传输速率与输入上限均按块计算后求和，每块贡献所属等级的数值。

成型后整段结构作为一份能量存储工作，任意一面都能接入 ME 网络。

未成型的单块或组合不算结构：可以存 AE，但只能经 FE 充放，ME 网络无法连接，也无法取出其中的能量。成型时各块已有的存量并入结构。容量与速率的计算见[增益算法](../gain-algorithm.md)。

## 组件槽

每块带独立的组件槽，结构可装的组件总数等于各块槽位数之和。插入**存储组件**可提升容量与传输速率，可选：

* AE2 的 1k ~ 256k
* MEGA Cells 的 1m ~ 256m
* AppFlux 的 core_1k ~ 256m

终端按等级分组，每个等级一行 12 格，**一种组件占一格**；同种组件在该格内计数，12 格占满后自动续行。放入哪一格结果相同：新组件会分摊到该等级各块的空槽上。容量按块计入并逐块求和，插满另有数量加成。组件随所在模块一并存档。

## 模块管理终端

对**已成型**的结构点右键打开终端。

终端按等级分组：每组一条标题行（等级图标、该等级模块数、`已装配存储组件：a/b`），其下是该等级的组件行。右击标题图标选中该组，快速移动只会放入选中的组。

左侧三枚按钮：

* 打开本指南
* 切换终端样式（短 / 中等 / 长 / 最长，决定面板行数）
* 轮换数字显示方式：字母（`12.3M`）、科学（`1.23E7`）、工程（`12.3E6`）
