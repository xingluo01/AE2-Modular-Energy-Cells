// 最小示例：一个值都不写，全部走默认（等同内置「基础」档）。
//
// 放在 kubejs/startup_scripts/ 下。
//
// 资源可以照抄 examples/industrial/ 那套（相对本文件是 ../../../industrial/kubejs/），把文件名里的
// industrial 换成 simple_tier 即可
// （等级名由方块名去掉 _energy_cell_module 推出来，所以这里推出来是 simple_tier）。
//
// 注意别把方块名取成内置那三个（basic / dense / superdense_energy_cell_module），
// 那会撞上重复键。

StartupEvents.registry('block', event => {
    event.create('simple_tier_energy_cell_module', 'ae2modularcells:module')
})
