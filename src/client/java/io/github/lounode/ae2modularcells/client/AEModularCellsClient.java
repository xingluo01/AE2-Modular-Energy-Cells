package io.github.lounode.ae2modularcells.client;

import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

import appeng.client.gui.style.StyleManager;

import io.github.lounode.ae2modularcells.AE2ModularCells;
import io.github.lounode.ae2modularcells.AEModularCellsRegistries;
import io.github.lounode.ae2modularcells.client.gui.EnergyModuleTerminalScreen;
import io.github.lounode.ae2modularcells.client.render.ConnectedModuleBakedModel;
import io.github.lounode.ae2modularcells.client.render.MonitorRenderer;
import io.github.lounode.ae2modularcells.common.menu.EnergyModuleTerminalMenu;

/**
 * 客户端入口：只挂两件事——菜单对应的屏幕、连接状态模型。
 *
 * <p>与 {@code common} 源集分开是刻意的：公共代码编译期就看不到客户端类，方块与方块实体也就不可能误引
 * 渲染相关的东西。</p>
 */
@Mod(value = AE2ModularCells.MOD_ID, dist = Dist.CLIENT)
public final class AEModularCellsClient {

    /** 样式文档路径：AE2 按 {@code assets/ae2/screens/...} 解析，见 {@code StyleManager.loadStyleDoc}。 */
    private static final String STYLE_DOC = "/screens/" + AE2ModularCells.MOD_ID + "/energy_module_terminal.json";

    public AEModularCellsClient(IEventBus modBus, ModContainer container) {
        modBus.addListener(this::registerScreens);
        modBus.addListener(this::installConnectedModels);
        modBus.addListener(this::registerRenderers);
        modBus.addListener(this::registerBlockColors);
    }

    /**
     * 监控器正面三层的颜色。
     *
     * <p>走原版 tint 而不是顶点色：方块模型这条管线上 BakedQuad 自带的顶点色会被渲染器的传参盖掉（实测就是
     * 灰白面板），而 tintIndex≥0 的 quad 会向这里要颜色。颜色取 AE2 默认涂色（fluix 紫）的三档变体——
     * 它的三张 {@code monitor_light_*} 是灰阶互补图层，本来就配这三个亮度层次用（与 {@code ColorableBlockEntityBlockColor}
     * 同一条路）。tintIndex 由 {@code MonitorBakedModel.drawFrontLight} 打在正面三层的 quad 上。</p>
     */
    private void registerBlockColors(net.neoforged.neoforge.client.event.RegisterColorHandlersEvent.Block event) {
        var color = appeng.api.util.AEColor.TRANSPARENT;
        event.register((state, level, pos, tintIndex) -> switch (tintIndex) {
            case 1 -> color.blackVariant;
            case 2 -> color.mediumVariant;
            case 3 -> color.whiteVariant;
            default -> -1;
        }, AEModularCellsRegistries.ENERGY_MONITOR.get(), AEModularCellsRegistries.FLOW_MONITOR.get());
    }

    private void registerScreens(RegisterMenuScreensEvent event) {
        event.register(AEModularCellsRegistries.MENU_ENERGY_MODULE_TERMINAL.get(),
                new MenuScreens.ScreenConstructor<EnergyModuleTerminalMenu, EnergyModuleTerminalScreen>() {
                    @Override
                    public EnergyModuleTerminalScreen create(EnergyModuleTerminalMenu menu, Inventory inventory,
                            Component title) {
                        // 样式文档与屏幕一一对应：文档里缺一个控件 id，构造期就会抛异常把玩家踢下线。
                        var style = StyleManager.loadStyleDoc(STYLE_DOC);
                        return new EnergyModuleTerminalScreen(menu, inventory, title, style);
                    }
                });
    }

    /** 监控器的正面读数渲染器：两类监控器共用一个渲染类，类型不同、外观一致。 */
    private void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(AEModularCellsRegistries.monitorBlockEntity(AEModularCellsRegistries.ENERGY_MONITOR),
                MonitorRenderer::new);
        event.registerBlockEntityRenderer(AEModularCellsRegistries.monitorBlockEntity(AEModularCellsRegistries.FLOW_MONITOR),
                MonitorRenderer::new);
    }

    private void installConnectedModels(ModelEvent.ModifyBakingResult event) {
        ConnectedModuleBakedModel.install(event);
    }
}
