package io.github.lounode.ae2modularcells.integration.ae2;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

import appeng.api.integrations.igtooltip.ClientRegistration;
import appeng.api.integrations.igtooltip.CommonRegistration;
import appeng.api.integrations.igtooltip.TooltipContext;
import appeng.api.integrations.igtooltip.TooltipProvider;
import appeng.api.integrations.igtooltip.providers.BodyProvider;
import appeng.api.integrations.igtooltip.providers.ServerDataProvider;

import io.github.lounode.ae2modularcells.AE2ModularCells;
import io.github.lounode.ae2modularcells.common.block.entity.EnergyCellModuleBlockEntity;

/**
 * 浮层集成（Jade / WTHIT / TOP）：给模块方块加「结构状态 + AE 储量」两行。
 *
 * <p><b>不写 Jade 代码</b>：AE2 自己提供了一层与浮层解耦的 SPI（{@code appeng.api.integrations.igtooltip}），
 * 由它去适配 Jade。这样做同时解决两件事：本模组永远不引用 Jade 的类型（缺 Jade 时不会在加载阶段炸），
 * 装 WTHIT/TOP 时同一份代码也直接生效。</p>
 *
 * <p>注册走 Java 的 ServiceLoader：配套文件是
 * {@code META-INF/services/appeng.api.integrations.igtooltip.TooltipProvider}，内容是本类全名。缺了那个文件，
 * 这个类写什么都看不到，而且不会有任何报错——浮层是不出声的。</p>
 *
 * <p><b>「AE 储量」那行不在这里</b>：父类（{@code AENetworkedPoweredBlockEntity} → {@code AEBasePoweredBlockEntity}）
 * 已经是 {@code IAEPowerStorage}，落在 AE2 的兜底浮层登记里，只要 {@code getAEMaxPower() > 0}，
 * AE2 的 {@code PowerStorageDataProvider} 就会自己把「储量」那一行加上。这里只管它不管的部分：结构状态。</p>
 *
 * <p><b>服务端出数据、客户端出文字</b>：能被浮层读到的只有服务端在 {@code provideServerData} 里塞进 NBT 的东西
 * （浮层的 tooltip 是在客户端拼的，客户端不一定有方块实体）。所以这里两边成对：一边塞、一边读。</p>
 */
public final class ModularCellTooltipProvider implements TooltipProvider {

    /** 结构信息那几个键。它们只在一次浮层交互里用，但仍当成协议看待：改名字等于两端不配对。 */
    private static final String TAG_FORMED = "formed";
    private static final String TAG_SIDE = "side";

    private static final ResourceLocation ID_FORMED = AE2ModularCells.makeId("module_formed");
    /** 未成型那一行单独一把钥匙：与成型行共用同一个 id 会让「名字即协议」的两条内容混在一处。 */
    private static final ResourceLocation ID_NOT_FORMED = ID_FORMED.withSuffix("_not_formed");
    private static final ResourceLocation ID_STRUCTURE = AE2ModularCells.makeId("module_structure");

    @Override
    public void registerCommon(CommonRegistration registration) {
        registration.addBlockEntityData(ID_STRUCTURE, EnergyCellModuleBlockEntity.class, new StructureData());
    }

    @Override
    public void registerClient(ClientRegistration registration) {
        registration.addBlockEntityBody(EnergyCellModuleBlockEntity.class,
                io.github.lounode.ae2modularcells.common.block.EnergyCellModuleBlock.class,
                ID_STRUCTURE, new StructureBody());
    }

    /** 服务端：把「成型没有、什么等级、边长多少」写进浮层的中间数据。 */
    private static final class StructureData implements ServerDataProvider<EnergyCellModuleBlockEntity> {
        @Override
        public void provideServerData(Player player, EnergyCellModuleBlockEntity module, CompoundTag serverData) {
            boolean formed = module.isFormed();
            serverData.putBoolean(TAG_FORMED, formed);
            serverData.putInt(TAG_SIDE, formed ? module.stats().side() : 1);
        }
    }

    /** 客户端：按服务端给的数拼两行。 */
    private static final class StructureBody implements BodyProvider<EnergyCellModuleBlockEntity> {
        @Override
        public void buildTooltip(EnergyCellModuleBlockEntity module, TooltipContext context,
                appeng.api.integrations.igtooltip.TooltipBuilder tooltip) {
            var data = context.serverData();
            if (!data.contains(TAG_FORMED)) {
                return;
            }

            boolean formed = data.getBoolean(TAG_FORMED);
            if (!formed) {
                // 未成型时方块实体在场也能取到账目，但那不是「这一段」的数——浮层只说实话：还没成型。
                tooltip.addLine(Component.translatable("jade.ae2modularcells.not_formed"), ID_NOT_FORMED);
                return;
            }

            // 成型后两行：一行说成型了，一行报边长（3 表示 3×3×3）。等级不再在这里报——
            // 混拼结构报单一等级会把「这一段里有三档」说成一种，而浮层位置又与 AE2 自己的储量行相邻。
            tooltip.addLine(Component.translatable("jade.ae2modularcells.formed"), ID_FORMED);
            tooltip.addLine(Component.translatable("jade.ae2modularcells.module_size", data.getInt(TAG_SIDE)),
                    ID_STRUCTURE);
        }
    }
}
