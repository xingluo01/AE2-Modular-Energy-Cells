package io.github.lounode.ae2modularcells.client.render;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;

import net.neoforged.fml.ModList;

import io.github.lounode.ae2modularcells.AE2ModularCells;
import io.github.lounode.ae2modularcells.client.gui.NumberDisplayMode;
import io.github.lounode.ae2modularcells.common.block.MonitorBlock;
import io.github.lounode.ae2modularcells.common.block.entity.MonitorBlockEntity;

/**
 * 监控器正面的读数。
 *
 * <p>画在方块面朝的那一面（{@link MonitorBlock#FACING}，放置时朝玩家），内容是方块实体里那份「显示值」——
 * 储能百分比，以及流量监控器的近 1 秒平均吞吐。渲染不读核心、不做计算以外的换算：数字是多少就画多少，
 * 口算留给服务端。瞬时输入速率不在正面显示（它逐 tick 抖），只在终端界面里给。</p>
 *
 * <p>朝向用原版 HORIZONTAL_FACING 的同一套约定：把画布的 +Z 转到朝向上（{@code -toYRot()}），文字正对面朝的
 * 那一侧、不镜像。</p>
 *
 * <p>版式按用途分两套：储能监控器只有一行百分比、居中；流量监控器正面中间放一枚 FE 图标（正面边长的 1/4），
 * 读数压在下沿——图标与数字各占一层，正面才不挤。</p>
 */
public class MonitorRenderer implements BlockEntityRenderer<MonitorBlockEntity> {

    /** 贴面偏移：略微离开方块表面，避免与方块自身表面 z-fighting。 */
    private static final float FACE_Z = 0.502F;

    /** 行距（字体单位；1 字体单位在缩放前等于 1 个方块，所以必须配合下面的 TEXT_SCALE 看）。 */
    private static final float LINE_HEIGHT = 10.0F;

    /**
     * 字形高度（字体单位）。
     *
     * <p>取值 8 是 vanilla ASCII 字形格高（{@code Font.lineHeight} 是 9，含行间距，不是同一量）。</p>
     *
     * <p>{@code drawInBatch} 的 y 是**行框顶**：墨迹从锚点往下占 8 单位。所以 n 行墨迹占据
     * {@code [y, y + (n-1)*行距 + 8]}，要让它与方块中心同心，y 的偏移是
     * {@code -(  (n-1)*行距/2 + 字形高/2  )}——**减**半个字形，不是加。</p>
     */
    private static final float GLYPH_HEIGHT = 8.0F;

    /**
     * 字体缩放系数。
     *
     * <p>字体是按「GUI 单位」画的：**不缩放的话一行就是 10 个方块高**，数字会糊满整个世界（实测症状：
     * 监控器上的数字尺寸异常）。这里先把整个绘制坐标缩到 1/33 左右——一行约 0.3 方块，正好铺满正面大半、
     * 四周留边；流量监控器还要在同一层里腾出中间那枚图标（见 render 里的版式分支）。</p>
     */
    private static final float TEXT_SCALE = 0.03F;

    private static final int COLOR_PRIMARY = 0xFFFFFFFF;

    /**
     * 可选字体：数字用 Create 风格的 3x5 字形。
     *
     * <p>两个来源，优先活跃维护的那个：Reliable Recount（{@code reliable_recount:3x5}，是 O123456789 的活跃
     * 分支，目前主要更新它）与上游 O123456789（{@code o123456789:3x5}）。装了谁用谁，都没装就退回原版字体。</p>
     *
     * <p>只引字体 id 字符串，不引它们的类——两者都是 client-only，编译期链接会在服务端或未安装时炸。字体缺的
     * 字形由原版字体逐字回退，所以整行套上去也安全（单位与分隔符照样画得出来）。</p>
     */
    private static final ResourceLocation NUMBER_FONT =
            ResourceLocation.fromNamespaceAndPath("reliable_recount", "3x5");

    /** 上游 O123456789 的同名字体：没装 Reliable Recount 时用它。 */
    private static final ResourceLocation NUMBER_FONT_UPSTREAM =
            ResourceLocation.fromNamespaceAndPath("o123456789", "3x5");

    /** 当前可用的数字字体；两个都没装时返回 null（用原版字体）。 */
    private static ResourceLocation numberFont() {
        if (ModList.get().isLoaded("reliable_recount")) {
            return NUMBER_FONT;
        }
        if (ModList.get().isLoaded("o123456789")) {
            return NUMBER_FONT_UPSTREAM;
        }
        return null;
    }

    /**
     * 流量监控器正面那枚 FE 图标：优先用 AppFlux 表示「FE」的那张图（方块图集 sprite）。
     *
     * <p>只引一个资源 id 字符串，<b>不 import AppFlux 的任何类</b>——这是 {@code integration/<邻居>} 的红线：
     * 邻居的类名一旦进了总是加载的类，它缺席时会在加载阶段炸。AppFlux 没装（或它那块 sprite 缺失）时
     * 回退到本模组自带的 {@link #FALLBACK_ICON}。</p>
     */
    private static final ResourceLocation APPFUX_FE_SPRITE =
            ResourceLocation.fromNamespaceAndPath("appflux", "energy/fe");

    /**
     * 自带备用图标（方块图集的 sprite id，不是贴图路径）。
     *
     * <p>先前的实现是一张静态的 {@code textures/gui/fe_icon.png}，用 {@code RenderType.text(贴图路径)} 直接绑纹理
     * 画——那条路无法显示动画（贴图自己的 {@code .mcmeta} 只在图集里生效）。现在它与 AppFlux 那张一样走图集
     * sprite，于是备胎也有动画。</p>
     *
     * <p>它也保证了不依赖任何第三方模组也能画出东西：AppFlux 缺席时不会变成 missingno。</p>
     */
    private static final ResourceLocation FALLBACK_ICON =
            ResourceLocation.fromNamespaceAndPath(AE2ModularCells.MOD_ID, "block/fe_icon");

    /** 图标边长占正面边长的比例：1/4。再大就会把正面的读数挤得无处可放。 */
    private static final float ICON_FRACTION = 0.25F;

    /** 读数贴正面下沿时留的边距（像素）：不留会压到方块边缘线上。 */
    private static final float BOTTOM_INSET_PIXELS = 1.0F;

    public MonitorRenderer(BlockEntityRendererProvider.Context context) {
        // 不需要额外资源：字体从 Minecraft 单例取。
    }

    @Override
    public void render(MonitorBlockEntity monitor, float partialTick, PoseStack pose, MultiBufferSource buffers,
            int packedLight, int packedOverlay) {
        if (!(monitor.getBlockState().getBlock() instanceof MonitorBlock block)) {
            return;
        }
        // 常态（未成型）不画读数：那时它还没接进任何结构，没有可监控的对象，读数无从谈起——
        // 正面本身就是它的常态外观（monitor 贴图）。成型后才出数字。
        if (!monitor.getBlockState().getOptionalValue(MonitorBlock.FORMED).orElse(false)) {
            return;
        }
        var face = face(block, monitor);
        var lines = face.lines();
        if (lines.isEmpty()) {
            return;
        }

        Direction facing = monitor.getBlockState().getValue(MonitorBlock.FACING);
        Font font = Minecraft.getInstance().font;

        pose.pushPose();
        pose.translate(0.5, 0.5, 0.5);
        // 画布的 +Z 对准方块朝向：朝北（toYRot=180）时转 -180 度，文字就贴在朝北那一面。
        pose.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
        // 贴面偏移按**方块**单位给（缩放之前），缩放只作用于之后的文字坐标。
        pose.translate(0.0, 0.0, FACE_Z);
        // Y 取负：字体在屏幕坐标系里向下画，而世界坐标的 +Y 是向上——不翻的话正面文字会倒过来。
        // 横向也要收：最宽那行文案（例如 "+1.23M AE/t"）在 3% 缩放下约 2.5 方块宽，会伸出正面、飘到
        // 相邻方块前面。取「按高度定的 3%」与「按最宽一行算出的上限（正面可用宽度 0.8 方块）」里更小的那个——
        // 逐行改缩放会让行距与居中全部错位，所以整块共用一个缩放。
        float widest = 0.0F;
        for (Line line : lines) {
            widest = Math.max(widest, font.width(line.text()));
        }
        float textScale = Math.min(TEXT_SCALE, 0.8F / Math.max(1.0F, widest));
        pose.scale(textScale, -textScale, textScale);

        // 缩放之后所有长度都吃 textScale，所以版式按「正面在缩放坐标里的尺寸」算，不写死数字：
        // 最宽那行触发宽度自适应（textScale 变小）时，图标与读数仍按同一比例摆。
        float faceHalf = 0.5F / textScale;
        float textHeight = (lines.size() - 1) * LINE_HEIGHT + GLYPH_HEIGHT;

        // 两类监控器版式不同：储能监控器只有一行百分比、居中；流量监控器中间让给图标，读数压在下沿。
        // 居中时 y 的起点是负的半个块高（y 是行框顶、墨迹向下占 GLYPH_HEIGHT，块高就是整块的墨迹范围）。
        float textTop = -textHeight / 2.0F;
        if (face.withIcon()) {
            // 靠下：读数底边与正面下沿之间留 BOTTOM_INSET_PIXELS；像素换算成「方块单位」再换算成缩放坐标。
            float inset = (BOTTOM_INSET_PIXELS / 16.0F) / textScale;
            textTop = faceHalf - inset - textHeight;
            // 图标画在**平移之前**的原点上：drawIcon 的 y 是相对正面中心算的，而下面那句 translate 会把原点
            // 挪到读数行框顶——两句顺序颠倒，图标会跟着读数一起偏下去，正好压在读数身上。
            float iconSize = 2.0F * faceHalf * ICON_FRACTION;
            drawIcon(pose, buffers, -iconSize / 2.0F, iconSize);
        }
        pose.translate(0.0, textTop, 0.0);
        for (Line line : lines) {
            float x = -font.width(line.text()) / 2.0F;
            // 自发光：监控器上的数字不该因为天黑看不见，所以用全亮打包光。
            font.drawInBatch(line.text(), x, 0.0F, line.color(), false, pose.last().pose(), buffers,
                    Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
            pose.translate(0.0, LINE_HEIGHT, 0.0);
        }
        pose.popPose();
    }

    /** 正面要画的东西：读数行，以及中间要不要那枚 FE 图标（两类监控器版式不同）。 */
    private record Face(List<Line> lines, boolean withIcon) {}

    /**
     * 这一块该画哪几行、要不要图标。两类监控器内容不同，但都只读自己方块实体里的显示值。
     */
    private static Face face(MonitorBlock block, MonitorBlockEntity monitor) {
        var lines = new ArrayList<Line>(1);
        boolean withIcon = false;
        switch (block.kind()) {
            case ENERGY -> lines.add(new Line(Component.literal(monitor.storagePercent() + "%"), COLOR_PRIMARY));
            // 流量监控器只报一个数：整段的平均吞吐。瞬时输入速率那行撤掉了——它逐 tick 抖，方块正面又小，
            // 两行挤在一起反而看不清「这东西在以多大功率进食」。
            case FLOW -> {
                lines.add(new Line(numberText("gui.ae2modularcells.monitor.average",
                        compact(monitor.throughput())), COLOR_PRIMARY));
                // 图标是「在进食」这一件事的标记，只在流量监控器上画（见 render 里的版式分支）。
                withIcon = true;
            }
        }
        return new Face(lines, withIcon);
    }

    /** 读数：装了可选字体 mod 就用它的字形，没装就用原版字体（两种情形都是同一个组件文本）。 */
    private static Component numberText(String translationKey, String value) {
        // 用 var：withStyle 在 MutableComponent 上，而 translatable 返回的就是它——写成 Component 会丢方法。
        var text = Component.translatable(translationKey, value);
        ResourceLocation font = numberFont();
        return font == null ? text : text.withStyle(Style.EMPTY.withFont(font));
    }

    /**
     * 画正面那枚能量图标：一个贴图四边形。
     *
     * <p>亮度走 {@link LightTexture#FULL_BRIGHT}，与读数同一条「自发光」口径：天黑时方块正面的图标不该看不见。</p>
     *
     * @param y    四边形上边缘在缩放坐标里的位置（缩放坐标 +y 朝屏幕下方，所以 y 越大越靠下）
     * @param size 边长（缩放坐标）
     */
    private static void drawIcon(PoseStack pose, MultiBufferSource buffers, float y, float size) {
        var matrix = pose.last().pose();
        float half = size / 2.0F;
        // 两张都走方块图集的 sprite：装了 AppFlux 用它的，否则用自带的。
        //
        // 自带那张是帧动画（32 帧，见 textures/block/fe_icon.png.mcmeta），所以不能像以前那样用
        // RenderType.text(贴图路径) 直接绑纹理——那条路画出来是静止的第一帧。走 sprite 才是动画的：UV 取自
        // sprite，而 sprite 的 UV 每帧跟着动画更新。
        var atlas = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS);
        var sprite = atlas.apply(ModList.get().isLoaded("appflux") ? APPFUX_FE_SPRITE : FALLBACK_ICON);
        if (sprite.contents().name().equals(MissingTextureAtlasSprite.getLocation())) {
            // 缺 sprite 时 apply 会返回 missingno：索性不画，别把紫黑格印在方块正面。
            return;
        }
        float u0 = sprite.getU0();
        float u1 = sprite.getU1();
        float v0 = sprite.getV0();
        float v1 = sprite.getV1();
        VertexConsumer consumer = buffers.getBuffer(RenderType.text(InventoryMenu.BLOCK_ATLAS));
        // 贴图左上为 UV 原点、v 向下；这里从下边两角开始逆时针绕一圈。
        consumer.addVertex(matrix, -half, y + size, 0.0F).setColor(0xFFFFFFFF).setUv(u0, v1)
                .setLight(LightTexture.FULL_BRIGHT);
        consumer.addVertex(matrix, half, y + size, 0.0F).setColor(0xFFFFFFFF).setUv(u1, v1)
                .setLight(LightTexture.FULL_BRIGHT);
        consumer.addVertex(matrix, half, y, 0.0F).setColor(0xFFFFFFFF).setUv(u1, v0)
                .setLight(LightTexture.FULL_BRIGHT);
        consumer.addVertex(matrix, -half, y, 0.0F).setColor(0xFFFFFFFF).setUv(u0, v0)
                .setLight(LightTexture.FULL_BRIGHT);
    }

    /**
     * 数字格式与终端界面同一套：三位有效数字 + k/M/G 后缀（界面里那档「字母计数」的写法）。
     *
     * <p>曾经这里自己写了一份「两位小数」的版本（{@code 1.2k}），与界面的 {@code 1.23k} 不一致——
     * 同一个数字在两处长得不一样，玩家会以为是两个值。现在格式只有一个来源（{@link NumberDisplayMode}），
     * 改一处两边都跟着变。</p>
     */
    private static String compact(long value) {
        // 跟界面用同一个「当前档位」：玩家在终端里切到科学计数时，监控器上的数字写法也跟着变，
        // 否则同一个值在方块上和在界面里长得不一样（那正是这一轮修掉的问题）。
        return NumberDisplayMode.current().format(value);
    }

    /** 一行文字加颜色。 */
    private record Line(Component text, int color) {}
}
