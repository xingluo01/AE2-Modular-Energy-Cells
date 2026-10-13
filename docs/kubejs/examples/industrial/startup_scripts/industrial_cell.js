// 完整示例：加一个「工业」等级的模块方块。
//
// 放在 kubejs/startup_scripts/ 下。配套资源见同目录的 assets/ 与 data/，
// 另有贴图两张要自备（说明在 ../README.md）。
//
// 数值取在「致密」（1 600 000 / 8 000 / 2 / 128 000 / 64 000）与
// 「超密」（12 800 000 / 64 000 / 3 / 1 024 000 / 512 000）之间。

StartupEvents.registry('block', event => {
    event.create('industrial_energy_cell_module', 'ae2modularcells:module')
        // 单模块基础容量 A（AE）
        .capacity(6400000)
        // 单模块基础速率 V（AE/t）
        .transferRate(32000)
        // 每块提供的组件槽位数（内置三档是 1 / 2 / 3）
        .componentSlots(2)
        // 每块提供的速率上限 V_max（AE/t）；整段上限是逐块之和，所以越建越快
        .maxTransferRate(512000)
        // 每块提供的输入速率上限 V_in_max（AE/t）；整段同样逐块求和
        .maxInputRate(256000)
        // 等级名。不写会从方块名去掉 _energy_cell_module 推出来，这里显式写是为了
        // 让「存档位号 / 语言键 / 贴图文件名都按 industrial 走」这件事一眼可见。
        .tierId('industrial')
})
