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
 * 监控器的模型：正面一块面板，其余五面是统一基础纹理。
 *
 * <p><b>照抄 AE2 合成监控器的口径</b>（{@code assets/ae2/models/block/crafting/monitor.json}：{@code north} 用
 * {@code crafting/monitor}、其余五面用 {@code crafting/unit}；覆盖层由 {@code MonitorBakedModel.addInnerCube}
 * 在正面之上再叠一层）：</p>
 *
 * <ul>
 *   <li>未成型（{@code MonitorBlock.FORMED} 为假）：六面全用 {@code unit} —— 结构件还没拼进立方体时，
 *       看起来就该是那张空白单元。</li>
 *   <li>成型：正面 = {@code monitor_base}（基础正面），正面之上再叠一层 {@code monitor_light_{dark|medium|bright}}
 *       （自发光、按整段储能档位三选一），其余五面仍是 {@code unit}。</li>
 * </ul>
 *
 * <p>没有用 AE2 的 {@code AEModelData}：它在 {@code appeng.client.render.model}（client 包），而模型的贴图槽
 * 与档位判定在本模组里都在客户端侧，所以直接用方块状态读档位、用方块状态读成型态，不引入跨源集依赖。</p>
 */
public class MonitorBakedModel implements IDynamicBakedModel {

    /** 与模块一致：只走 cutout（AE2 的合成单元也是 {@code ChunkRenderTypeSet.of(RenderType.CUTOUT)}）。 */
    private static final net.neoforged.neoforge.client.ChunkRenderTypeSet RENDER_TYPES =
            net.neoforged.neoforge.client.ChunkRenderTypeSet.of(RenderType.CUTOUT);

    private static final float OUTER = 16.0F;

    private final TextureAtlasSprite unit;
    private final TextureAtlasSprite frontBase;

    /**
     * 未成型时的正面：监控器自己的那张 {@code monitor} 贴图。
     *
     * <p>未成型不等于「空白单元」——它是一台独立放置的监控器，正面要能认出来是什么方块（与模块
     * 「未成型用自己的面板贴图」同一口径）。成型后才换成 {@link #frontBase} 与三档覆盖层。</p>
     */
    private final TextureAtlasSprite frontUnformed;
    private final TextureAtlasSprite lightDark;
    private final TextureAtlasSprite lightMedium;
    private final TextureAtlasSprite lightBright;

    public MonitorBakedModel(TextureAtlasSprite unit, TextureAtlasSprite frontBase, TextureAtlasSprite lightDark,
            TextureAtlasSprite lightMedium, TextureAtlasSprite lightBright, TextureAtlasSprite frontUnformed) {
        this.unit = unit;
        this.frontBase = frontBase;
        this.frontUnformed = frontUnformed;
        this.lightDark = lightDark;
        this.lightMedium = lightMedium;
        this.lightBright = lightBright;
    }

    /** 正面覆盖层相对整面的内缩量：1px。照 AE2 的内嵌小立方体口径。 */
    private static final float INSET = 1.0F / 16F;

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

        // 六面先铺 unit；正面单独一张——未成型用它自己的 monitor 贴图（独立放置的监控器要认得出是什么方块），
        // 成型后换成基础正面 monitor_base，再由下面的覆盖层叠三档状态。
        builder.setTexture(this.unit);
        builder.setTexture(front, formed ? this.frontBase : this.frontUnformed);
        builder.addCube(0, 0, 0, OUTER, OUTER, OUTER);

        // 覆盖层只画在正面上，且只有成型后才画（同 AE2：叠加层是「已拼好的监控器」的外观）。
        if (formed && side == front) {
            builder.setTexture(side, lightFor(state));
            builder.setEmissiveMaterial(true);
            // 正面构成照 AE2 合成监控器：底板之上再叠一个**内嵌**的小立方体（四周各收 1px），不是与底板共面的整面。
            // 共面两层会互相闪烁，也看不出「面板 + 状态灯」的结构；内嵌后正好留出一圈底板边框。
            builder.addCube(INSET, INSET, INSET, OUTER - INSET, OUTER - INSET, OUTER - INSET);
            // 亮度由 dark/medium/bright 三张贴图本身承载（AE2 就是这么分档的），这里不再按比例调暗——
            // 调暗会把贴图里已经分好的档位差异压平，也偏离了上游的上色口径。
            // builder 是复用的：自发光标记要归位，否则后面画的普通面也会满地发光。
            builder.setEmissiveMaterial(false);
        }

        return quads;
    }

    /** 三档覆盖层按整段储能档位选：0 档（空）→ dark，1~3 档 → medium，4 档（满）→ bright。 */
    private TextureAtlasSprite lightFor(BlockState state) {
        int fill = state.getOptionalValue(MonitorBlock.FILL).orElse(0);
        if (fill <= 0) {
            return this.lightDark;
        }
        return fill >= 4 ? this.lightBright : this.lightMedium;
    }

    @Override
    public net.neoforged.neoforge.client.ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource rand,
            ModelData data) {
        return RENDER_TYPES;
    }

    @Override
    public TextureAtlasSprite getParticleIcon() {
        return this.unit;
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
