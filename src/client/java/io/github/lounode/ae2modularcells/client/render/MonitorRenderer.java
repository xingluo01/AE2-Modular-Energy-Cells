package io.github.lounode.ae2modularcells.client.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

    /** 行距（像素）。 */
    private static final float LINE_HEIGHT = 10.0F;

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
        pose.translate(0.0, -(lines.size() - 1) * LINE_HEIGHT / 2.0F, FACE_Z);
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
     * 压缩单位：监控器上的数字要一眼可读，1.2k / 3.4M 比 1234567 有用。
     *
     * <p>k / M / G 走 1000 进制——只做显示，不参与任何计算。</p>
     */
    private static String compact(long value) {
        long abs = Math.abs(value);
        if (abs < 1000L) {
            return Long.toString(value);
        }
        if (abs < 1000000L) {
            return String.format(Locale.ROOT, "%.1fk", value / 1000.0);
        }
        if (abs < 1000000000L) {
            return String.format(Locale.ROOT, "%.1fM", value / 1000000.0);
        }
        return String.format(Locale.ROOT, "%.1fG", value / 1000000000.0);
    }

    /** 一行文字加颜色。 */
    private record Line(Component text, int color) {}
}
