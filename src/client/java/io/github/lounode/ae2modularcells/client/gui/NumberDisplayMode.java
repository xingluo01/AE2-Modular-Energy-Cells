package io.github.lounode.ae2modularcells.client.gui;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

import net.minecraft.network.chat.Component;

/**
 * 界面数字的显示方式：字母计数 / 科学计数 / 工程计数。
 *
 * <p>三种都是「简化版」——数值格只有 60px 宽，还要塞下 {@code 当前/上限} 两个数，所以尾数一律三位有效数字：
 * </p>
 *
 * <pre>
 * 字母    12 345 678  ->  12.3M
 * 科学    12 345 678  ->  1.23E7
 * 工程    12 345 678  ->  12.3E6
 * </pre>
 *
 * <p>科学计数的尾数小于 10、工程计数小于 1000、字母计数小于 1000（到千就抬一档后缀）；三者都小于 1000 的
 * 数直接给原值——{@code 200000} 在科学计数下是 {@code 2E5}，但 {@code 800} 就是 {@code 800}，不该写成
 * {@code 8E2}。</p>
 *
 * <p>取整用 {@link BigDecimal} 而不是 {@code String.format("%.3g")}：后者在 999 999 这类值上会切成
 * {@code 1.00e+03k} 这种既有小数又有后缀的写法。进位判断必须在<b>舍入之后</b>再做一次——999 999 舍入成
 * 1000 时要抬指数，否则会印出 {@code 1000k}。</p>
 */
public enum NumberDisplayMode {

    /** 字母计数：{@code 12.3M}。 */
    LETTERS("letters"),

    /** 科学计数：{@code 1.23E7}。 */
    SCIENTIFIC("scientific"),

    /** 工程计数：{@code 12.3E6}（指数一律 3 的倍数）。 */
    ENGINEERING("engineering");

    /** 十个后缀够到 10^30：容量上限是 12.8 MAE × 4096 块，离它很远。 */
    private static final String[] LETTER_SUFFIXES = { "", "k", "M", "G", "T", "P", "E", "Z", "Y", "R" };

    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000L);

    /** 低于这个值的数不做任何变换——给原值最好读。 */
    private static final long PLAIN_BELOW = 1000L;

    private final String id;

    /** 当前档位（静态：这是显示偏好，不进存档、不给服务端看）。未设置过就是字母计数。 */
    private static NumberDisplayMode current = LETTERS;

    NumberDisplayMode(String id) {
        this.id = id;
    }

    /** 当前档位。 */
    public static NumberDisplayMode current() {
        return current;
    }

    /** 轮换到下一档并返回它（界面那枚按钮点一下的效果）。 */
    public static NumberDisplayMode cycle() {
        current = current.next();
        return current;
    }

    /** 界面上的名字：工具提示用。 */
    public Component displayName() {
        return Component.translatable("gui.ae2modularcells.display_mode." + id);
    }

    /** 这一档长什么样的例子（工具提示里给一个数，玩家不用逐个试）。 */
    public String example() {
        return format(12_345_678L);
    }

    /** 配置里用的短 id，也是语言键后缀。 */
    public String id() {
        return id;
    }

    /** 下一个模式（按客户端里那枚按钮轮换的顺序）。 */
    public NumberDisplayMode next() {
        var values = values();
        return values[(ordinal() + 1) % values.length];
    }

    /**
     * 把数值格式化成当前模式的写法。
     *
     * <p>负数也走同一套：符号在尾数上（{@code -1.2k}），不把它当异常——界面上的数字理论上不该为负，但这里
     * 是纯格式化，为负就照实写。</p>
     */
    public String format(long value) {
        long magnitude = Math.abs(value);
        if (magnitude < PLAIN_BELOW) {
            return Long.toString(value);
        }
        return this == LETTERS ? letterFormat(value) : exponentFormat(value, this == SCIENTIFIC ? 1 : 3);
    }

    /**
     * 字母计数：每满 1000 抬一档后缀。
     *
     * <p>后缀用完（超过 10^30）就停在最大档，不再继续除——宁可印一个大数，也不要印出一个不存在的后缀。</p>
     */
    private static String letterFormat(long value) {
        int exponent = 0;
        double scaled = value;
        while (Math.abs(scaled) >= 1000.0D && exponent < LETTER_SUFFIXES.length - 1) {
            scaled /= 1000.0D;
            exponent++;
        }
        BigDecimal mantissa = roundTo3Significant(scaled);
        // 舍入可能把 999.999 抬成 1000：这时要补一次进位，否则会印出「1000k」。
        if (mantissa.abs().compareTo(THOUSAND) >= 0 && exponent < LETTER_SUFFIXES.length - 1) {
            mantissa = roundTo3Significant(mantissa.doubleValue() / 1000.0D);
            exponent++;
        }
        return plain(mantissa) + LETTER_SUFFIXES[exponent];
    }

    /**
     * 科学/工程计数：指数是 {@code step} 的倍数（1 或 3）。
     *
     * @param step 指数步长；1 = 科学计数，3 = 工程计数
     */
    private static String exponentFormat(long value, int step) {
        // 指数取「不大于 log10 的那个 step 的倍数」，这样尾数落在 [1, 10^step)。
        int exponent = (int) (Math.floor(Math.log10(Math.abs((double) value)) / step) * step);
        long base = (long) Math.pow(10, step);
        BigDecimal mantissa = roundTo3Significant(value / Math.pow(10, exponent));
        // 同字母计数：舍入后的尾数顶到 base（科学计数的 10、工程计数的 1000）就抬一档指数。
        if (mantissa.abs().compareTo(BigDecimal.valueOf(base)) >= 0) {
            mantissa = roundTo3Significant(mantissa.doubleValue() / base);
            exponent += step;
        }
        return plain(mantissa) + "E" + exponent;
    }

    /** 三位有效数字，四舍五入。走 {@code Double.toString} 而不是原始 double 构造，避开二进制误差。 */
    private static BigDecimal roundTo3Significant(double value) {
        return new BigDecimal(Double.toString(value), new MathContext(3, RoundingMode.HALF_UP));
    }

    /**
     * 去掉多余的零与指数写法。
     *
     * <p>{@code stripTrailingZeros()} 会把 1000 变成 {@code 1E+3}，所以必须再过一道
     * {@code toPlainString()} 才拿到 {@code "1000"}。</p>
     */
    private static String plain(BigDecimal mantissa) {
        return mantissa.stripTrailingZeros().toPlainString();
    }
}
