package io.github.lounode.ae2modularcells.client.render;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

import net.neoforged.neoforge.client.model.IDynamicBakedModel;
import net.neoforged.neoforge.client.model.data.ModelData;

import appeng.client.render.cablebus.CubeBuilder;

import io.github.lounode.ae2modularcells.common.block.MonitorBlock;

import org.jetbrains.annotations.Nullable;

/**
 * 监控器的模型：正面一块面板、其余五面是底色 + 按连接收边（与模块同规格）。
 *
 * <p><b>贴图归属是用户指定的，不要按上游自行调换</b>（这两张极易搞反，曾经先后错过两次）：</p>
 * <ul>
 *   <li>{@code monitor}（彩色屏）：<b>未成型</b>时的正面——独立放置的监控器要认得出是什么方块。</li>
 *   <li>{@code monitor_base}（空面板）：<b>成型</b>时的正面底座，其上再叠正面光板。</li>
 *   <li>其余五面：未成型用 {@code unit}，成型后用 {@code energy_cell_light_base}（与模块的基础层同一张）。</li>
 * </ul>
 *
 * <p><b>正面光板由三张互补图层叠成</b>（照 AE2 合成监控器的 {@code MonitorBakedModel.addInnerCube}）：
 * {@code monitor_light_bright} 是中间的填充面、{@code monitor_light_medium} 是四个角、
 * {@code monitor_light_dark} 是外框。三张图必须**一起画**，各自配一个亮度变体；只取其中一张，正面就只剩
 * 面板的一部分——这正是实机上「覆盖层不对」的原因。</p>
 *
 * <p><b>档位不参与外观</b>（用户口径）：光板固定用 AE2 默认涂色（fluix 紫）的三档变体，不随储能档位变色。
 * 方块状态里的 {@code FILL} 仍照常由核心写入（它记录的是整段储能档位，留给将来的读取方），只是这里不读它。</p>
 */
public class MonitorBakedModel implements IDynamicBakedModel {

    /** 与模块一致：只走 cutout（AE2 的合成单元也是 {@code ChunkRenderTypeSet.of(RenderType.CUTOUT)}）。 */
    private static final net.neoforged.neoforge.client.ChunkRenderTypeSet RENDER_TYPES =
            net.neoforged.neoforge.client.ChunkRenderTypeSet.of(RenderType.CUTOUT);

    /** 正面三层的色调由客户端注册的 BlockColor 给出（见 {@code AEModularCellsClient}），这里只声明档位。 */
    /**
     * 正面三层的 tint 档位，与 AE2 的 {@code AEColor#getVariantByTintIndex} 对齐：1 = 最暗、2 = 中间、
     * 3 = 最亮。0 不能占用——那一位在 AE2 里留给破坏粒子。
     */
    private static final int TINT_DARK = 1;
    private static final int TINT_MEDIUM = 2;
    private static final int TINT_BRIGHT = 3;

    /** 成型后五个侧面的底色（用户指定：`unit_base`）。 */
    private final TextureAtlasSprite sideBase;

    /** 未成型时五个侧面的底色（用户指定：`unit`，与空白单元同一张）。 */
    private final TextureAtlasSprite sideUnformed;

    /** 未成型时的正面（用户指定：`monitor`，彩色屏）——独立摆着时要认得出是什么方块。 */
    private final TextureAtlasSprite frontUnformed;

    /** 成型时的正面底座（用户指定：`monitor_base`，空面板），其上再叠三层光板。 */
    private final TextureAtlasSprite frontFormed;
    private final TextureAtlasSprite ringCorner;
    private final TextureAtlasSprite ringHor;
    private final TextureAtlasSprite ringVer;
    private final TextureAtlasSprite lightDark;
    private final TextureAtlasSprite lightMedium;
    private final TextureAtlasSprite lightBright;

    public MonitorBakedModel(TextureAtlasSprite sideBase, TextureAtlasSprite sideUnformed,
            TextureAtlasSprite frontUnformed, TextureAtlasSprite frontFormed, TextureAtlasSprite ringCorner,
            TextureAtlasSprite ringHor, TextureAtlasSprite ringVer, TextureAtlasSprite lightDark,
            TextureAtlasSprite lightMedium, TextureAtlasSprite lightBright) {
        this.sideBase = sideBase;
        this.sideUnformed = sideUnformed;
        this.frontUnformed = frontUnformed;
        this.frontFormed = frontFormed;
        this.ringCorner = ringCorner;
        this.ringHor = ringHor;
        this.ringVer = ringVer;
        this.lightDark = lightDark;
        this.lightMedium = lightMedium;
        this.lightBright = lightBright;
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand,
            ModelData extraData, @Nullable RenderType renderType) {
        // 物品与破坏粒子不由这里出（物品另有模型、粒子用 getParticleIcon）。
        if (side == null || state == null) {
            return List.of();
        }

        var quads = new ArrayList<BakedQuad>(8);
        var builder = new CubeBuilder(quads);
        // 每次只出当前这一面的几何：六个面各调一次。
        builder.setDrawFaces(EnumSet.of(side));

        Direction front = state.getValue(MonitorBlock.FACING);
        boolean formed = state.getOptionalValue(MonitorBlock.FORMED).orElse(false);

        if (formed) {
            // 边框先画（它占内面外圈），再画内面内容；两者用同一套收边坐标——底座若画满 0..16，
            // 会把内缩的光板与边框一起盖住，实机上就是「只看到一块灰面板」。
            var connections = ConnectedModuleBakedModel.getConnections(extraData);
            ConnectedModuleBakedModel.addCornerCaps(builder, connections, side, this.ringCorner);
            ConnectedModuleBakedModel.addRingStrips(builder, connections, side, this.ringHor, this.ringVer);
            float[] bounds = innerBounds(connections, side);
            if (side == front) {
                // 底座与三层共用同一组坐标（同深度、靠绘制顺序叠色），照 AE2 的 addInnerCube。
                builder.setTexture(this.frontFormed);
                builder.addCube(bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5]);
                drawFrontLight(builder, quads, bounds);
            } else {
                builder.setTexture(this.sideBase);
                builder.addCube(bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5]);
            }
        } else {
            // 未成型：六面铺底色（unit），正面换成监控器自己的彩色屏，独立放置时要认得出是什么方块。
            builder.setTexture(this.sideUnformed);
            builder.setTexture(front, this.frontUnformed);
            builder.addCube(0, 0, 0, ConnectedModuleBakedModel.OUTER, ConnectedModuleBakedModel.OUTER,
                    ConnectedModuleBakedModel.OUTER);
        }

        return quads;
    }

    /**
     * 成型结构里的一块侧面：底色 + 按连接收边。
     *
     * <p>几何与 {@link ConnectedModuleBakedModel} 同构（同一组内缩量、同一套「当前轴铺满」的口径）；
     * 边框收边直接复用它的两个方法，免得同一套收边算法存在两份实现。</p>
     *
     * <p><b>只用于成型态</b>。未成型时侧面是整块素面、不补边框——与模块未成型同一口径（用户指定）：
     * 那时它还没拼进立方体，收边只会让外观显得零碎。</p>
     */
    /**
     * 一面在「有连接就铺满、没连接就收进 3px」口径下的内面坐标（{@code x1,y1,z1,x2,y2,z2}）。
     *
     * <p>与 {@link ConnectedModuleBakedModel} 同构：当前轴铺满，另外两轴按连接情况决定要不要收边。
     * 边框、底座、光板三层都拿这一组坐标——它们必须同深度叠，靠绘制顺序决定谁在上面。</p>
     *
     * <p><b>只用于成型态</b>。未成型时侧面是整块素面、不补边框（与模块未成型同一口径，用户指定）。</p>
     */
    private static float[] innerBounds(EnumSet<Direction> connections, Direction side) {
        float x1 = connections.contains(Direction.WEST) ? 0 : ConnectedModuleBakedModel.INNER_MIN;
        float x2 = connections.contains(Direction.EAST) ? ConnectedModuleBakedModel.OUTER
                : ConnectedModuleBakedModel.INNER_MAX;
        float y1 = connections.contains(Direction.DOWN) ? 0 : ConnectedModuleBakedModel.INNER_MIN;
        float y2 = connections.contains(Direction.UP) ? ConnectedModuleBakedModel.OUTER
                : ConnectedModuleBakedModel.INNER_MAX;
        float z1 = connections.contains(Direction.NORTH) ? 0 : ConnectedModuleBakedModel.INNER_MIN;
        float z2 = connections.contains(Direction.SOUTH) ? ConnectedModuleBakedModel.OUTER
                : ConnectedModuleBakedModel.INNER_MAX;

        switch (side) {
            case DOWN, UP -> {
                y1 = 0;
                y2 = ConnectedModuleBakedModel.OUTER;
            }
            case NORTH, SOUTH -> {
                z1 = 0;
                z2 = ConnectedModuleBakedModel.OUTER;
            }
            case WEST, EAST -> {
                x1 = 0;
                x2 = ConnectedModuleBakedModel.OUTER;
            }
        }
        return new float[] { x1, y1, z1, x2, y2, z2 };
    }

    /**
     * 正面光板：三张互补图层各画一次，顺序与 AE2 一致（bright → medium → dark）。
     *
     * <p><b>颜色走原版 tint（BlockColor），不走顶点色</b>。实机验证过：在方块模型的这条管线上
     * BakedQuad 自带的顶点色会被渲染器的传参盖掉（总是白），所以画出来是灰白面板。
     * AE2 自己也是这么干的：三张贴图是灰阶，颜色由 {@code ColorableBlockEntityBlockColor} 按 tintIndex
     * 取 {@code AEColor} 的变体注入。这里只用同样的机制：三层收集完后把它们的 tintIndex 依次改成 0/1/2，
     * 颜色由客户端注册的 BlockColor 给出（见 {@code AEModularCellsClient}）。</p>
     */
    private void drawFrontLight(CubeBuilder builder, List<BakedQuad> quads, float[] bounds) {
        // 记下起点：下面三层新加的 quad 就是本方法要打标记的那三个。
        int mark = quads.size();
        builder.setEmissiveMaterial(true);

        builder.setTexture(this.lightBright);
        builder.addCube(bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5]);
        builder.setTexture(this.lightMedium);
        builder.addCube(bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5]);
        builder.setTexture(this.lightDark);
        builder.addCube(bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5]);

        // builder 是复用的：自发光标记要归位，否则后面画的普通面也会背着它。
        builder.setEmissiveMaterial(false);

        for (int i = mark; i < quads.size(); i++) {
            var quad = quads.get(i);
            // tintIndex 1/2/3 分别对应亮/中/暗三档（0 是 AE2 留给破坏粒子的槽，撞上会染色挖掘粒子）。
            // 顶点色在这条管线上会被渲染器传参盖掉，所以颜色走 tint、由客户端注册的 BlockColor 给出。
            quads.set(i, new BakedQuad(quad.getVertices(), TINT_BRIGHT - (i - mark), quad.getDirection(),
                    quad.getSprite(), quad.isShade()));
        }
    }

    @Override
    public net.neoforged.neoforge.client.ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource rand,
            ModelData data) {
        return RENDER_TYPES;
    }

    @Override
    public TextureAtlasSprite getParticleIcon() {
        return this.sideUnformed;
    }

    @Override
    public boolean useAmbientOcclusion() {
        // 照 AE2 合成单元/监控器：不吃世界光、也不用 AO。
        return false;
    }

    @Override
    public boolean isGui3d() {
        return true;
    }

    @Override
    public boolean usesBlockLight() {
        return false;
    }

    @Override
    public boolean isCustomRenderer() {
        return false;
    }

    @Override
    public net.minecraft.client.renderer.block.model.ItemOverrides getOverrides() {
        return net.minecraft.client.renderer.block.model.ItemOverrides.EMPTY;
    }
}
