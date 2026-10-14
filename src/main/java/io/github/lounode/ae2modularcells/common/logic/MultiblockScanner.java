package io.github.lounode.ae2modularcells.common.logic;

import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import io.github.lounode.ae2modularcells.common.ModularCellConstants;
import io.github.lounode.ae2modularcells.common.block.StructurePart;

/**
 * 多方块结构检测：从一块模块出发，找它所在的「实心立方体」。
 *
 * <p><b>等级不参与几何判定</b>：一段立方体里允许混拼基础/致密/超致密（用户口径），所以这里只认「是不是
 * 模块方块」。等级差异走账目（每块按自己那一档算容量与速率），不走结构判定——把等级塞进判定就会得出
 * 「混拼的一堆不算结构」，而那不是几何问题。</p>
 *
 * <p>只做几何判定，不碰能量、不碰菜单、不写任何方块实体——容量汇总由调用方拿着 {@link Layout} 自己做。
 * 这样这个类能单独读懂、能单独验，也让「检测」与「记账」两件事各归其位。</p>
 *
 * <p>为什么不返回方块列表：一处 16³ 的结构有 4096 块，返回列表就等于每次检测都分配 4096 个
 * {@link BlockPos}。这里只回一个「最小角 + 边长」，其余位置由调用方用 {@link Layout#modulePos} 就地算——
 * 遍历用的是可复用游标，与方块数无关的临时对象只有两块定长数组。</p>
 *
 * <p><b>结论是三态而不是布尔</b>：邻居没加载完时「看不到」不等于「不成型」。把这两件事混成一个 false，
 * 一次跨区块的加载就能把一段好端端的结构判没（存档载入时方块实体先到、附近区块还没就绪，正是这种时刻）。
 * 所以 {@link Status#INDETERMINATE} 是一个独立结论，调用方对它要「什么都不做」，见
 * {@code EnergyCellModuleBlockEntity#rebuildStructure()}。</p>
 *
 * <p>触发时机：方块被放置、被破坏、某方块的组件槽变化，外加玩家右键（见
 * {@code EnergyCellModuleBlock#useWithoutItem}——那是用户动作，不是轮询）。绝不上 tick 扫描。</p>
 */
public final class MultiblockScanner {

    /** 扩张窗口的半宽：结构边长最大 16，起点在结构内时任何一块的偏移都不会超过 15。 */
    private static final int REACH = ModularCellConstants.MAX_SIDE;

    /** 窗口一条边的格数（±{@link #REACH}）。 */
    private static final int SPAN = REACH * 2 + 1;

    /** 访问位图的位数上界。 */
    private static final int CELLS = SPAN * SPAN * SPAN;

    /** 六向的固定数组：{@link Direction#values()} 每次调用都会新建一个数组，而这里是逐块展开的热循环。 */
    private static final Direction[] DIRECTIONS = Direction.values();

    /**
     * 复用的访问位图与队列。
     *
     * <p>原先每次检测都新建它们（562 个 long ≈ 4.5KB、4096 个 int = 16KB，共 ≈ 20KB）。连续放置时一次
     * 几百次检测就是几 MB 垃圾，而它们全部落在服务端 tick 里。检测只跑在服务端主线程、也不会重入
     * （{@link #scan} 不调自己），所以两个缓冲可以共用。</p>
     *
     * <p>位图每轮都要清：它记的是「本次访问过」，不是「历史上访问过」。</p>
     */
    private static final long[] VISITED = new long[(CELLS + 63) >> 6];
    private static final int[] QUEUE = new int[ModularCellConstants.MAX_BLOCKS];

    private MultiblockScanner() {
    }

    /** 一次检测的结论。 */
    public enum Status {
        /** 结构成立，{@link Result#layout()} 给出最小角与边长。 */
        FORMED,
        /** 确定不成立（三边不等、非实心、越界、过大）。 */
        NOT_FORMED,
        /** 判不了：扩张路上撞到没加载的邻居。调用方<b>必须保留原状态</b>，等下一次触发再算。 */
        INDETERMINATE
    }

    /** 一段成立的结构：最小角与边长。 */
    public record Layout(BlockPos origin, int side) {

        /**
         * 非空校验：{@code origin} 为 {@code null} 且 {@code side} 仍有效（例如 1）是「身份字段没恢复」的典型
         * 形状，而 record 不会自己拦。放过它的话，错误要拖到 {@link #modulePos} 里才以一句 NPE 暴出来，堆栈里
         * 看不出是哪个入参坏的。
         */
        public Layout {
            Objects.requireNonNull(origin, "origin");
        }

        /** 结构内的方块数，也就是模块数。 */
        public int moduleCount() {
            return side * side * side;
        }

        /**
         * 把结构内的第 {@code index} 块写进游标。
         *
         * <p>遍历顺序固定为 y → z → x。顺序本身不重要，重要的是它确定：菜单窗口按它取模块、结构变更后
         * 重算，两次结果必须一致，否则滑动窗口里的槽位会指到别的方块上。</p>
         */
        public void modulePos(int index, BlockPos.MutableBlockPos cursor) {
            int side = this.side;
            int dy = index / (side * side);
            int rest = index % (side * side);
            int dz = rest / side;
            int dx = rest % side;
            cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
        }
    }

    /**
     * 检测结果。{@link Status#FORMED} 之外 {@link #layout()} 为 null。
     *
     * <p>{@link #detail()} 是「为什么不成型」的人话说明，<b>只给日志看</b>——调用方的行为不看它。
     * 没有它，「结构自己散了」这条路径没有任何异常可拓，排查只能靠盯方块发呆。</p>
     */
    public record Result(Status status, @Nullable Layout layout, String detail) {

        public static Result formed(Layout layout) {
            return new Result(Status.FORMED, layout, "成立 " + layout.side() + "³ @ " + layout.origin());
        }

        public static Result notFormed(String detail) {
            return new Result(Status.NOT_FORMED, null, detail);
        }

        public static Result indeterminate(String detail) {
            return new Result(Status.INDETERMINATE, null, detail);
        }

        public boolean isFormed() {
            return this.status == Status.FORMED;
        }
    }

    /** 这一块是不是模块方块（不分等级）。判定本体在方块类上，检测与渲染共用同一个口径。 */
    public static boolean isStructurePart(@Nullable BlockState state) {
        return state != null && state.getBlock() instanceof StructurePart;
    }

    /**
     * 从 {@code start} 出发检测结构。
     *
     * @return 成立、确定不成立、或「邻居没加载完，判不了」——三种结论见 {@link Status}
     */
    public static Result scan(Level level, BlockPos start) {
        if (!isStructurePart(level.getBlockState(start))) {
            return Result.notFormed("起点不是结构件：" + level.getBlockState(start).getBlock());
        }

        // 复用两个定长缓冲（见 VISITED / QUEUE 的说明）：位图每轮清空，队列不需要清——尾部由 tail 管。
        long[] visited = VISITED;
        int[] queue = QUEUE;
        java.util.Arrays.fill(visited, 0L);
        var cursor = new BlockPos.MutableBlockPos();

        int minX = 0;
        int minY = 0;
        int minZ = 0;
        int maxX = 0;
        int maxY = 0;
        int maxZ = 0;

        int head = 0;
        int tail = 0;
        int count = 0;

        queue[tail++] = pack(0, 0, 0);
        markVisited(visited, 0, 0, 0);

        while (head < tail) {
            int packed = queue[head++];
            int dx = unpack(packed, 0);
            int dy = unpack(packed, 1);
            int dz = unpack(packed, 2);
            count++;

            if (count > ModularCellConstants.MAX_BLOCKS) {
                // 已经超过最大结构的方块数，这一片无论如何都不成立——是确定的结论，不是「判不了」。
                return Result.notFormed("连通块已超过 " + ModularCellConstants.MAX_BLOCKS + " 块上限");
            }

            for (var direction : DIRECTIONS) {
                int nx = dx + direction.getStepX();
                int ny = dy + direction.getStepY();
                int nz = dz + direction.getStepZ();

                // 越界必须先判：visited 位图是按 ±REACH 定长的，拿窗口外的坐标去索引会直接下标越界
                // （实测：服务端 tick 世界时 ArrayIndexOutOfBoundsException: Index 570 out of bounds for length 562）。
                // 语义不变，只是提前：窗口外的坐标不能记 visited，但「越界的是不是模块」仍要判——越界的模块
                // 说明这一段超了边长上界（判不成立），越界的普通方块与本次检测无关。
                if (Math.abs(nx) > REACH || Math.abs(ny) > REACH || Math.abs(nz) > REACH) {
                    cursor.set(start.getX() + nx, start.getY() + ny, start.getZ() + nz);
                    if (!level.isLoaded(cursor)) {
                        return Result.indeterminate("越界方向有未加载区块：偏移 (" + nx + "," + ny + "," + nz + ")");
                    }
                    if (isStructurePart(level.getBlockState(cursor))) {
                        return Result.notFormed("边长超出上限：偏移 (" + nx + "," + ny + "," + nz + ") 处还有结构件");
                    }
                    continue;
                }

                if (isVisited(visited, nx, ny, nz)) {
                    continue;
                }

                cursor.set(start.getX() + nx, start.getY() + ny, start.getZ() + nz);
                // 邻居没加载就读不到它的方块状态。这时不能当它是空气（会得出一个假的实心立方体），
                // 也不能当它「不是模块」（会把一段跨区块的结构判没）——只能承认判不了。
                if (!level.isLoaded(cursor)) {
                    return Result.indeterminate("相邻区块未加载：偏移 (" + nx + "," + ny + "," + nz + ")");
                }
                if (!isStructurePart(level.getBlockState(cursor))) {
                    continue;
                }
                if (tail >= queue.length) {
                    // 队列满了却没触发上面的上界判定，说明边界条件被改坏了；宁可当场失败也不要静默截断。
                    throw new IllegalStateException("结构检测的队列溢出：上界判定与队列容量不一致");
                }

                markVisited(visited, nx, ny, nz);
                queue[tail++] = pack(nx, ny, nz);

                minX = Math.min(minX, nx);
                minY = Math.min(minY, ny);
                minZ = Math.min(minZ, nz);
                maxX = Math.max(maxX, nx);
                maxY = Math.max(maxY, ny);
                maxZ = Math.max(maxZ, nz);
            }
        }

        int sideX = maxX - minX + 1;
        int sideY = maxY - minY + 1;
        int sideZ = maxZ - minZ + 1;
        if (sideX != sideY || sideY != sideZ) {
            return Result.notFormed("包围盒不是立方体：" + sideX + "×" + sideY + "×" + sideZ
                    + "，连通 " + count + " 块");
        }
        if (!ModularCellConstants.isValidSide(sideX)) {
            return Result.notFormed("边长不合法：" + sideX);
        }
        // 连通体方块数 == 边长³ 等价于「实心」：连通体已经把包围盒填满了，否则数量对不上。
        if (count != sideX * sideX * sideX) {
            return Result.notFormed("不实心：连通 " + count + " 块，而 " + sideX + "³ = "
                    + (sideX * sideX * sideX));
        }

        return Result.formed(new Layout(
                new BlockPos(start.getX() + minX, start.getY() + minY, start.getZ() + minZ),
                sideX));
    }

    // ---- 窗口内的坐标打包：三条轴各 6 位（0..32），一个 int 装得下 ----

    private static int pack(int dx, int dy, int dz) {
        return (dx + REACH) | ((dy + REACH) << 6) | ((dz + REACH) << 12);
    }

    private static int unpack(int packed, int axis) {
        return ((packed >> (axis * 6)) & 0x3F) - REACH;
    }

    private static boolean isVisited(long[] visited, int dx, int dy, int dz) {
        int index = cellIndex(dx, dy, dz);
        return (visited[index >> 6] & (1L << (index & 63))) != 0L;
    }

    private static void markVisited(long[] visited, int dx, int dy, int dz) {
        int index = cellIndex(dx, dy, dz);
        visited[index >> 6] |= 1L << (index & 63);
    }

    private static int cellIndex(int dx, int dy, int dz) {
        return (dx + REACH) + (dy + REACH) * SPAN + (dz + REACH) * SPAN * SPAN;
    }
}
