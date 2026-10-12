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
