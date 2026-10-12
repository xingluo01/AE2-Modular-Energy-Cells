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
    private final TextureAtlasSprite lightDark;
    private final TextureAtlasSprite lightMedium;
    private final TextureAtlasSprite lightBright;

    public MonitorBakedModel(TextureAtlasSprite unit, TextureAtlasSprite frontBase, TextureAtlasSprite lightDark,
            TextureAtlasSprite lightMedium, TextureAtlasSprite lightBright) {
        this.unit = unit;
        this.frontBase = frontBase;
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

        // 统一基础：六面先铺 unit；成型时正面换成基础正面贴图。这样未成型就是一张完整的空白单元。
        builder.setTexture(this.unit);
        if (formed) {
            builder.setTexture(front, this.frontBase);
        }
        builder.addCube(0, 0, 0, OUTER, OUTER, OUTER);

        // 覆盖层只画在正面上，且只有成型后才画（同 AE2：叠加层是「已拼好的监控器」的外观）。
        if (formed && side == front) {
            float intensity = fillIntensity(state);
            builder.setTexture(side, lightFor(state));
            builder.setEmissiveMaterial(true);
            builder.setColorRGB(intensity, intensity, intensity);
            builder.addCube(0, 0, 0, OUTER, OUTER, OUTER);
            // builder 是复用的：颜色与自发光标记都要归位，否则后面画的普通面也会被染色/满地发光。
            builder.setColorRGB(1.0F, 1.0F, 1.0F);
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

    /**
     * 覆盖层亮度：0 档也有底亮（覆盖层用来区分状态，不能完全看不见），满档最亮。
     *
     * <p>自发光本身不受世界光照影响，这里的系数只调「亮到什么程度」。</p>
     */
    private static float fillIntensity(BlockState state) {
        int fill = state.getOptionalValue(MonitorBlock.FILL).orElse(0);
        return 0.45F + 0.55F * (fill / 4.0F);
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
