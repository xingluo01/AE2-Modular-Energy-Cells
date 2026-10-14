package io.github.lounode.ae2modularcells.client;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

import io.github.lounode.ae2modularcells.AE2ModularCells;
import io.github.lounode.ae2modularcells.client.gui.NumberDisplayMode;
import io.github.lounode.ae2modularcells.common.block.EnergyCellModuleBlock;

/**
 * 模块方块物品的三行数值 tooltip：基础储能 / 基础输入速率 / 存储组件槽。
 *
 * <p><b>数值一律现推，不写死</b>：三个数都从物品底下的那个 {@link EnergyCellModuleBlock} 取它的
 * {@code EnergyCellTier}（{@code baseCapacity()} / {@code baseTransferRate()} / {@code componentSlots()}），
 * 语言文件里只有标签、没有数字。所以谁注册的等级都自动带上这三行——内置三档、{@code api} 包那条注册路径、
 * KubeJS 脚本注册的，都不需要额外接线。</p>
 *
 * <p><b>为什么不给物品写 {@code appendHoverText}</b>：那要求每个模块物品是一个自定义 Item 子类。而 KubeJS
 * 那边的 {@code ModuleItemBuilder} 交出来的就是普通 {@code BlockItem}，第三方也可能自己 {@code new BlockItem}——
 * 逐个去改就等于「新增一种注册方式就得记得再实现一次」。挂在事件上则只看「这个物品装的是不是模块方块」，
 * 判断口径与注册方式无关。</p>
 *
 * <p><b>数字走 {@link NumberDisplayMode}</b>：与终端里的读数同一个模式，玩家在终端那枚按钮上切了计数方式，
 * 这里跟着变。它是客户端的显示偏好（静态、不进存档），所以这个处理器也必须在客户端源集——服务端既不需要
 * 也读不到它。</p>
 *
 * <p><b>为什么在客户端而不是服务端出数据</b>：三个数都是等级定义的一部分，两端一致，没有「服务端才知道」的
 * 成分，因此不需要走 {@code ServerDataProvider} 那套（方块浮层用它是因为那里的数据要问方块实体）。</p>
 */
@EventBusSubscriber(modid = AE2ModularCells.MOD_ID, value = Dist.CLIENT)
public final class ModuleTooltipHandler {

    /** 三个 tooltip 行的键。第三行的值不是档次是 1~3 这种小数，不过 NumberDisplayMode，但标签仍走语言文件。 */
    private static final String BASE_CAPACITY = "tooltip.ae2modularcells.base_capacity";
    private static final String BASE_INPUT_RATE_CAP = "tooltip.ae2modularcells.base_input_rate_cap";
    private static final String COMPONENT_SLOTS = "tooltip.ae2modularcells.component_slots";

    private ModuleTooltipHandler() {
    }

    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        var module = moduleBlockOf(event.getItemStack());
        if (module == null) {
            return;
        }
        var tier = module.tier();
        var mode = NumberDisplayMode.current();

        var lines = event.getToolTip();
        lines.add(Component.translatable(BASE_CAPACITY, mode.format(tier.baseCapacity())));
        // 这一行给的是输入方向的封顶（V_in_max），不是基础速率 V：项目里「输入速率」一直指充能方向
        // （见终端那行 gui.ae2modularcells.input_rate 与指南里的 V_in_max），基础速率是另一个数。
        lines.add(Component.translatable(BASE_INPUT_RATE_CAP, mode.format(tier.maxInputRate())));
        // 槽位数是 1~3 这种小数，不过 NumberDisplayMode（它只管千以上的缩写），直接给原值。
        lines.add(Component.translatable(COMPONENT_SLOTS, tier.componentSlots()));
    }

    /** 物品底下的方块是模块方块时给出它，否则 {@code null}。这是「是不是模块物品」的唯一判据。 */
    private static EnergyCellModuleBlock moduleBlockOf(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        if (stack.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof EnergyCellModuleBlock module) {
            return module;
        }
        return null;
    }
}
