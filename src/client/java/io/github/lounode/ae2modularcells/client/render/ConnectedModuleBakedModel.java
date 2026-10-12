package io.github.lounode.ae2modularcells.client.render;

/*
 * 本文件里的收边算法（边框宽度、八个角、横/竖贴图的选法、相邻方向让位的缩进）改编自 Applied Energistics 2 的
 * appeng.client.render.crafting.CraftingCubeBakedModel（LGPL-3.0，同一许可证），以便“按合成单元的规则收边”
 * 不是一句口头描述。方块侧的数据流（ModelData 现算 + requestModelDataUpdate）同样照它的做法。
 * 上游改动时，这里要跟着看。
 */

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.IDynamicBakedModel;
import net.neoforged.neoforge.client.model.data.ModelData;

import appeng.client.render.cablebus.CubeBuilder;

import io.github.lounode.ae2modularcells.AE2ModularCells;
import io.github.lounode.ae2modularcells.AEModularCellsRegistries;
import io.github.lounode.ae2modularcells.common.EnergyCellTier;
import io.github.lounode.ae2modularcells.common.block.entity.EnergyCellModuleModelData;

/**
 * 连接状态渲染：照 AE2 合成单元（crafting cube）那套规则画边框与内面。
 *
 * <p>规则只有两条，都只看「六个方向上有没有模块」（不分等级：与结构判定同口径）：</p>
 * <ul>
 * <li>有连接的方向：内面一直铺到方块边界，那一侧的边框省掉；</li>
 * <li>没有连接的方向：内面往里收 3px，空出来的 3px 用边框贴图补；两条边框相遇的角再用角贴图补。</li>
 * </ul>
 *
 * <p>成型与否不改方块模型（成型不替换模型）：这个类只按连接位画，所以一块孤零零的模块四周是完整边框，
 * 拼起来之后内缝自然消失——与合成单元拼成 CPU 时的观感一致。</p>
 *
 * <p>注册方式没走「自定义模型加载器」：NeoForge 给内建模型开的口子是 {@code ModelBakery} 的 mixin（AE2 自己
 * 就是那么做的），本模组不引 mixin，改用公开的 {@link ModelEvent.ModifyBakingResult}——模型照常由 blockstate
 * 与 JSON 烘出来，烘完之后把对应那几条换成这里这份。JSON 里的贴图声明因此仍然有效：这份替换万一没装上，方块
 * 只是退回一个普通立方体，不会变成紫黑格。</p>
 */
public final class ConnectedModuleBakedModel implements IDynamicBakedModel {

    private static final Logger LOG = LoggerFactory.getLogger(ConnectedModuleBakedModel.class);

    private static final ChunkRenderTypeSet RENDER_TYPES = ChunkRenderTypeSet.of(RenderType.CUTOUT);

    /** 边框宽度：内面往里收的量，与 AE2 合成单元同值。 */
    private static final float RING = 3.0F;

    /** 方块外沿尺寸（单位与模型坐标系一致：16 = 一整格）。 */
    private static final float OUTER = 16.0F;

    /**
     * 内面在没有连接的方向上收进约 3px，把那一圈让给边框贴图。
     *
     * <p>取 2.99 / 13.01 而不是 3 / 13，与上游一致：这 0.01 的尾巴是避让共面（内面边缘正好压在条纹内边）用的。
     * 取值照抄 {@code CraftingCubeBakedModel#getQuads}，不要“四舍五入”成整数。</p>
     */
    private static final float INNER_MIN = 2.99F;

    /** 内面在无连接方向上的外沿，见 {@link #INNER_MIN}。 */
    private static final float INNER_MAX = 13.01F;

    private final TextureAtlasSprite face;
    private final TextureAtlasSprite ringCorner;
    private final TextureAtlasSprite ringHor;
    private final TextureAtlasSprite ringVer;

    /**
     * 发光层贴图：三档各自的 {@code <tier>_energy_cell_light}。
     *
     * <p>发光层与基础面共用同一套几何，差别只在于它不吃世界光照（见 {@code getQuads} 里的
     * {@code setEmissiveMaterial}）——这是 AE2 控制器那种泛光的做法，不是自写 RenderType。</p>
     */
    private final TextureAtlasSprite light;

    private ConnectedModuleBakedModel(TextureAtlasSprite face, TextureAtlasSprite ringCorner,
            TextureAtlasSprite ringHor, TextureAtlasSprite ringVer, TextureAtlasSprite light) {
        this.face = face;
        this.ringCorner = ringCorner;
        this.ringHor = ringHor;
        this.ringVer = ringVer;
        this.light = light;
    }

    /**
     * 把三档模块方块的模型换成这一份。
     *
     * <p>匹配口径是这一段的坑：烘焙表（{@code ModifyBakingResult}）的键是
     * <b>{@code <方块 id>#<状态串>}</b>——取自 {@code BlockModelShaper.stateToModelLocation}，不是模型文件名
     * （{@code block/<名>}）。按模型文件名找会一个都匹配不上，而且不会有任何报错，表现为“方块就是普通立方体”。</p>
     *
     * <p>同一张表里还有物品模型 {@code <方块 id>#inventory}——它与方块模型<b>共用 id</b>，只靠变体区分。
     * 不过滤掉它的话，物品会拿到这份模型，而这份模型不出无朝向的面（{@code side == null} 返回空），物品会变透明。</p>
     *
     * <p>替换是按 id 全量匹配：这个方块将来若真的加了别的状态变体（例如「成型换模型」），它们也会被一并换成这份
     * ——那时要回来按变体过滤。当前只有一个 {@code ""} 变体。</p>
     */
    public static void install(ModelEvent.ModifyBakingResult event) {
        Map<ResourceLocation, ConnectedModuleBakedModel> byBlockId = new HashMap<>();
        for (var tier : EnergyCellTier.values()) {
            String name = AEModularCellsRegistries.blockName(tier);
            byBlockId.put(
                    ResourceLocation.fromNamespaceAndPath(AE2ModularCells.MOD_ID, name),
                    new ConnectedModuleBakedModel(
                            sprite(event, name + "_face"),
                            sprite(event, name + "_ring_corner"),
                            sprite(event, name + "_ring_hor"),
                            sprite(event, name + "_ring_ver"),
                            sprite(event, tier.id() + "_energy_cell_light")));
        }

        int[] replaced = { 0 };
        event.getModels().replaceAll((location, model) -> {
            if (ModelResourceLocation.INVENTORY_VARIANT.equals(location.variant())) {
                return model;
            }
            var replacement = byBlockId.get(location.id());
            if (replacement == null) {
                return model;
            }
            replaced[0]++;
            return replacement;
        });

        if (replaced[0] == 0) {
            // 这条日志是故意的：匹配不上时没有任何报错，只顾“方块看起来正常”，排查会从渲染一路查到方块实体。
            LOG.warn("没找到任何模块方块的烘焙模型（预期 {} 个）——连接边框不会生效", byBlockId.size());
        } else {
            // 故意用 INFO：这一条是「连接渲染到底接上了没有」的唯一直接证据（资源重载只有几次，不至于刷屏）。
            LOG.info("连接边框模型已替换 {} 个方块状态变体", replaced[0]);
        }
    }

    /**
     * 取图集里的贴图，顺手确认它真的进了图集。
     *
     * <p>这份模型的边框贴图不是由任何 {@code elements} 引用的（几何是手写的），所以它们靠
     * {@code models/block/*.json} 的 {@code textures} 段声明入图集。声明漏了不会报错，只会拿到 missingno
     * （紫黑格），那比编译器报错难查得多。</p>
     */
    private static TextureAtlasSprite sprite(ModelEvent.ModifyBakingResult event, String textureName) {
        var texture = ResourceLocation.fromNamespaceAndPath(AE2ModularCells.MOD_ID, "block/" + textureName);
        var sprite = event.getTextureGetter().apply(new Material(TextureAtlas.LOCATION_BLOCKS, texture));
        if (!texture.equals(sprite.contents().name())) {
            LOG.warn("贴图 {} 没进方块图集（拿到的是 {}）——检查 models/block/*.json 的 textures 段有没有声明它",
                    texture, sprite.contents().name());
        }
        return sprite;
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand,
            ModelData extraData, @Nullable RenderType renderType) {
        // 没有朝向的面（物品栏、破坏粒子）不由这里出：物品用的是另一份普通立方体模型。
        if (side == null) {
            return List.of();
        }

        var connections = getConnections(extraData);
        var quads = new ArrayList<BakedQuad>(8);
        var builder = new CubeBuilder(quads);
        // 每次只为当前这一面出几何：六个面各调一次，在这里算别的面是白花的。
        builder.setDrawFaces(EnumSet.of(side));

        addCornerCaps(builder, connections, side);
        addRingStrips(builder, connections, side);

        // 内面：没有连接的方向收进 3px，有连接的方向铺到边界（铺到正 16，与上游同值：
        // 两块的内面本来就会在接缝处共面，上游也没有为此向外多铺）；当前这一轴本来就占满整个面。
        float x1 = connections.contains(Direction.WEST) ? 0 : INNER_MIN;
        float x2 = connections.contains(Direction.EAST) ? OUTER : INNER_MAX;
        float y1 = connections.contains(Direction.DOWN) ? 0 : INNER_MIN;
        float y2 = connections.contains(Direction.UP) ? OUTER : INNER_MAX;
        float z1 = connections.contains(Direction.NORTH) ? 0 : INNER_MIN;
        float z2 = connections.contains(Direction.SOUTH) ? OUTER : INNER_MAX;

        switch (side) {
            case DOWN, UP -> {
                y1 = 0;
                y2 = OUTER;
            }
            case NORTH, SOUTH -> {
                z1 = 0;
                z2 = OUTER;
            }
            case WEST, EAST -> {
                x1 = 0;
                x2 = OUTER;
            }
        }

        // 发光层要先画：它与基础面完全共面，后画的贴图会盖住先画的——基础面不透明的地方就该盖住发光层，
        // 只有基础面上透明的窗口才露出下面这层光。
        builder.setTexture(this.light);
        builder.setEmissiveMaterial(true);
        builder.addCube(x1, y1, z1, x2, y2, z2);
        // 这个 builder 是复用的：不清掉自发光标记，后面画的普通面也会变成满地发光。
        builder.setEmissiveMaterial(false);

        builder.setTexture(this.face);
        builder.addCube(x1, y1, z1, x2, y2, z2);

        return quads;
    }

    /** 角贴图只用在「三条面都没有连接」的角上。 */
    private void addCornerCaps(CubeBuilder builder, EnumSet<Direction> connections, Direction side) {
        builder.setTexture(this.ringCorner);
        for (var y : new Direction[] { Direction.UP, Direction.DOWN }) {
            for (var x : new Direction[] { Direction.EAST, Direction.WEST }) {
                for (var z : new Direction[] { Direction.NORTH, Direction.SOUTH }) {
                    // 一个角由「上/下、东/西、南/北」各取一条面确定，八种组合正好八个角；
                    // 正在画的这一面必须是这个角的三条面之一，否则这个角不在当前面上。
                    if (side != y && side != x && side != z) {
                        continue;
                    }
                    if (connections.contains(y) || connections.contains(x) || connections.contains(z)) {
                        continue;
                    }
                    float x1 = x == Direction.WEST ? 0 : OUTER - RING;
                    float y1 = y == Direction.DOWN ? 0 : OUTER - RING;
                    float z1 = z == Direction.NORTH ? 0 : OUTER - RING;
                    builder.addCube(x1, y1, z1, x1 + RING, y1 + RING, z1 + RING);
                }
            }
        }
    }

    /**
     * 四条边框条纹：没有连接的方向才补，且在与「相邻方向也没连接」的那一端缩进 3px，把角让给角贴图。
     *
     * <p>条纹贴图分工：面内横向的条用 {@code *_ring_hor}，纵向的用 {@code *_ring_ver}——与 AE2 合成单元同一
     * 套取值口径，贴图可以照那套资源的结构画。</p>
     */
    private void addRingStrips(CubeBuilder builder, EnumSet<Direction> connections, Direction side) {
        for (var direction : Direction.values()) {
            if (direction == side || direction == side.getOpposite()) {
                continue;
            }
            builder.setTexture(isVerticalStrip(side, direction) ? this.ringVer : this.ringHor);

            // 有连接的那一侧，内面已经铺到边界，不需要再补条纹。
            if (connections.contains(direction)) {
                continue;
            }

            float x1 = 0;
            float y1 = 0;
            float z1 = 0;
            float x2 = OUTER;
            float y2 = OUTER;
            float z2 = OUTER;
            switch (direction) {
                case DOWN -> {
                    y1 = 0;
                    y2 = RING;
                }
                case UP -> {
                    y1 = OUTER - RING;
                    y2 = OUTER;
                }
                case WEST -> {
                    x1 = 0;
                    x2 = RING;
                }
                case EAST -> {
                    x1 = OUTER - RING;
                    x2 = OUTER;
                }
                case NORTH -> {
                    z1 = 0;
                    z2 = RING;
                }
                case SOUTH -> {
                    z1 = OUTER - RING;
                    z2 = OUTER;
                }
            }

            // 与条纹垂直的那条轴上，如果对面也没连接，说明那里要画角贴图，条纹让出 3px。
            var perpendicular = rotateAround(direction, side);
            for (var neighbour : new Direction[] { perpendicular, perpendicular.getOpposite() }) {
                if (connections.contains(neighbour)) {
                    continue;
                }
                switch (neighbour) {
                    case DOWN -> y1 = RING;
                    case UP -> y2 = OUTER - RING;
                    case NORTH -> z1 = RING;
                    case SOUTH -> z2 = OUTER - RING;
                    case WEST -> x1 = RING;
                    case EAST -> x2 = OUTER - RING;
                }
            }

            builder.addCube(x1, y1, z1, x2, y2, z2);
        }
    }

    /** 在 {@code side} 这个面上，条纹沿哪个方向延伸——决定取横贴图还是竖贴图。与 AE2 合成单元同判据。 */
    private static boolean isVerticalStrip(Direction side, Direction direction) {
        if (side.getAxis() != Direction.Axis.Y
                && (direction == Direction.NORTH || direction == Direction.EAST || direction == Direction.WEST
                        || direction == Direction.SOUTH)) {
            return true;
        }
        return side.getAxis() == Direction.Axis.Y
                && (direction == Direction.EAST || direction == Direction.WEST);
    }

    /** 把 {@code a} 绕 {@code side} 转 90°：得到同在该面上、与 {@code a} 垂直的那条方向。 */
    private static Direction rotateAround(Direction a, Direction side) {
        int x = side.getStepY() * a.getStepZ() - side.getStepZ() * a.getStepY();
        int y = side.getStepZ() * a.getStepX() - side.getStepX() * a.getStepZ();
        int z = side.getStepX() * a.getStepY() - side.getStepY() * a.getStepX();
        for (var candidate : Direction.values()) {
            if (candidate.getStepX() == x && candidate.getStepY() == y && candidate.getStepZ() == z) {
                return candidate;
            }
        }
        // 理论上到不了这里：side × a 一定是六向之一，且与 a 垂直。
        return a;
    }

    private static EnumSet<Direction> getConnections(ModelData modelData) {
        if (modelData.has(EnergyCellModuleModelData.CONNECTIONS)) {
            var connections = modelData.get(EnergyCellModuleModelData.CONNECTIONS);
            if (connections != null) {
                return connections;
            }
        }
        return EnumSet.noneOf(Direction.class);
    }

    // 光照口径是有意与上游不同的地方：上游合成单元是 AO=false / usesBlockLight=false（它的边框是一圈薄框，
    // 按自己那套发光处理），本模组画的是一整块带内面的立方体，所以按普通方块走 true / true。
    // 实机若发现边框与内面明暗不一致，再按上游改（已记在 docs/TODO.md）。

    @Override
    public boolean useAmbientOcclusion() {
        return true;
    }

    @Override
    public boolean isGui3d() {
        return false;
    }

    @Override
    public boolean usesBlockLight() {
        return true;
    }

    @Override
    public boolean isCustomRenderer() {
        return false;
    }

    @Override
    public TextureAtlasSprite getParticleIcon() {
        return this.face;
    }

    @Override
    public ItemOverrides getOverrides() {
        return ItemOverrides.EMPTY;
    }

    @Override
    public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource rand, ModelData data) {
        // 边框贴图带透明像素，必须走 CUTOUT，否则透明处会画成黑块。
        return RENDER_TYPES;
    }
}
