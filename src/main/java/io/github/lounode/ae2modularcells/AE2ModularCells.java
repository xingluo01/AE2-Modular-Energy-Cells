package io.github.lounode.ae2modularcells;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

import io.github.lounode.ae2modularcells.api.ModularCellsApi;
import io.github.lounode.ae2modularcells.common.config.ModularCellConfig;

/**
 * 模组入口。
 *
 * <p>骨架阶段只做两件事：钉住 mod id，把注册表挂到模组总线。方块、物品、方块实体与菜单在
 * 实现阶段经 {@link AEModularCellsRegistries} 接入（实施顺序见 {@code docs/TODO.md}）。</p>
 *
 * <p>这里不写任何逻辑：多方块的结构检测、能量汇总与组件记账各归其位（见 {@code docs/ARCHITECTURE.md}），
 * 入口只负责把它们串起来。</p>
 */
@Mod(AE2ModularCells.MOD_ID)
public final class AE2ModularCells {

    public static final String MOD_ID = "ae2modularcells";

    /** 本模组命名空间下的一个 id。{@code ResourceLocation.fromNamespaceAndPath} 的短写，省得各处拼字符串。 */
    public static net.minecraft.resources.ResourceLocation makeId(String path) {
        return net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    public AE2ModularCells(IEventBus modBus, ModContainer container) {
        // 配置：schema 由 MezzConfig 托管，必须在这里就建好。配置界面靠枚举「已 build 的 schema」发现配置，
        // 而本类的静态初始化只会在第一次被碰时跑——那个人可能是很晚才发生的容量计算。
        ModularCellConfig.init();
        // 配置就绪要等**服务器启动**，不能用 FMLCommonSetupEvent：配置是 SERVER 归属（per-world 文件），
        // 模组构造期与 common setup 那一刻它还没加载——实测那里读值会抛，于是内置清单写不进去、
        // 识别只能靠兜底，用户对文件的修改也变成实际不生效。
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, event -> ModularCellConfig.onServerStarted());
        AEModularCellsRegistries.register(modBus);
        // 脚本（KubeJS）建的等级：KubeJS 自己的落盘只认「当前正在注册的注册表」，而自建注册表晚于 vanilla 那些，
        // 所以脚本里 new 出来的等级得由本模组在它自己那一次 RegisterEvent 里补注册。
        modBus.addListener(ModularCellsApi::registerScriptTiers);
    }
}
