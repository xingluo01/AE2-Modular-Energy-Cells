package io.github.lounode.ae2modularcells.client.render;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;

import io.github.lounode.ae2modularcells.client.gui.NumberDisplayMode;
import io.github.lounode.ae2modularcells.common.block.MonitorBlock;
import io.github.lounode.ae2modularcells.common.block.entity.MonitorBlockEntity;

/**
 * 监控器正面的读数。
 *
 * <p>画在方块面朝的那一面（{@link MonitorBlock#FACING}，放置时朝玩家），内容是方块实体里那份「显示值」——
 * 核心推过来的储能百分比 / 输入流量 / 近 1 秒吞吐。渲染不读核心、不做计算以外的换算：数字是多少就画多少，
 * 口算留给服务端。</p>
 *
 * <p>朝向用原版 HORIZONTAL_FACING 的同一套约定：把画布的 +Z 转到朝向上（{@code -toYRot()}），文字正对面朝的
 * 那一侧、不镜像。</p>
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
     * 监控器上的数字尺寸异常）。这里先把整个绘制坐标缩到 1/33 左右——一行约 0.3 方块，两行连行距约 0.6 方块，
     * 正好铺满正面大半、四周留边。</p>
     */
    private static final float TEXT_SCALE = 0.03F;

    private static final int COLOR_PRIMARY = 0xFFFFFFFF;
    private static final int COLOR_SECONDARY = 0xFFB0C4D0;

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
        List<Line> lines = lines(block, monitor);
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
        // 横向也要收：最宽那行文案（例如 "avg 1.23M AE/t"）在 3% 缩放下约 2.5 方块宽，会伸出正面、飘到
        // 相邻方块前面。取「按高度定的 3%」与「按最宽一行算出的上限（正面可用宽度 0.8 方块）」里更小的那个——
        // 逐行改缩放会让行距与居中全部错位，所以整块共用一个缩放。
        float widest = 0.0F;
        for (Line line : lines) {
            widest = Math.max(widest, font.width(line.text()));
        }
        float textScale = Math.min(TEXT_SCALE, 0.8F / Math.max(1.0F, widest));
        pose.scale(textScale, -textScale, textScale);
        // 竖直居中：按行数摆中，再**减去**半个字形（y 是行框顶、墨迹向下 8 单位，不减就整体偏下半个字形）。
        pose.translate(0.0, -(lines.size() - 1) * LINE_HEIGHT / 2.0F - GLYPH_HEIGHT / 2.0F, 0.0);
        for (Line line : lines) {
            float x = -font.width(line.text()) / 2.0F;
            // 自发光：监控器上的数字不该因为天黑看不见，所以用全亮打包光。
            font.drawInBatch(line.text(), x, 0.0F, line.color(), false, pose.last().pose(), buffers,
                    Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
            pose.translate(0.0, LINE_HEIGHT, 0.0);
        }
        pose.popPose();
    }

    /** 这一块该画哪几行。两类监控器内容不同，但都只读自己方块实体里的显示值。 */
    private static List<Line> lines(MonitorBlock block, MonitorBlockEntity monitor) {
        var lines = new ArrayList<Line>(2);
        switch (block.kind()) {
            case ENERGY -> lines.add(new Line(Component.literal(monitor.storagePercent() + "%"), COLOR_PRIMARY));
            case FLOW -> {
                lines.add(new Line(Component.literal("in " + compact(monitor.inputRate()) + " AE/t"), COLOR_PRIMARY));
                lines.add(new Line(Component.literal("avg " + compact(monitor.throughput()) + " AE/t"),
                        COLOR_SECONDARY));
            }
        }
        return lines;
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
