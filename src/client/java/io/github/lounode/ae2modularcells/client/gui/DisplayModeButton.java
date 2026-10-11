package io.github.lounode.ae2modularcells.client.gui;

import java.util.List;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import appeng.client.gui.Icon;
import appeng.client.gui.style.Blitter;
import appeng.client.gui.widgets.ITooltip;

import io.github.lounode.ae2modularcells.AE2ModularCells;

/**
 * 「显示方式」按钮：点一下把界面数字的格式轮换一档（字母 → 科学 → 工程 → 字母）。
 *
 * <p>没继承 AE2 的 {@code IconButton}：那个类的图标只能是它自己的 {@code Icon} 枚举（内置图集里的固定格子），
 * 而这三个图标在本模组自己的贴图里。所以这里只借它的观感——悬停时同样压一层工具条底色，滑块与坐标都由
 * {@code VerticalButtonBar} 排。</p>
 */
public class DisplayModeButton extends Button implements ITooltip {

    /**
     * 图标切片：三张 16×16 横排在 {@code states.png} 的左上角。按档位取图，不是按枚举序号直取数组——
     * 将来加第四档时编译器会当场报错（而不是运行期 {@code ArrayIndexOutOfBounds} 把屏幕塔掉）。
     */
    private static Blitter iconFor(NumberDisplayMode mode) {
        return switch (mode) {
            case LETTERS -> icon(0);
            case SCIENTIFIC -> icon(16);
            case ENGINEERING -> icon(32);
        };
    }

    private final Runnable onPress;

    public DisplayModeButton(Runnable onPress) {
        super(0, 0, 16, 16, Component.empty(), btn -> onPress.run(), Button.DEFAULT_NARRATION);
        this.onPress = onPress;
    }

    /**
     * 图标切片：三张 16×16 横排在 {@code states.png} 的左上角。
     *
     * <p><b>必须走 {@link ResourceLocation} 那个重载。</b>{@code Blitter.texture(String, w, h)} 内部是
     * {@code AppEng.makeId("textures/" + s)}——它把命名空间无条件封成 {@code ae2:}，所以那个重载
     * <b>只能取 AE2 自己的贴图</b>。本模组的路径传进去会得到
     * {@code ae2:textures/ae2modularcells:textures/gui/states.png}，路径里多出的 {@code :} 让
     * {@code ResourceLocation} 当场抛异常；它又落在类初始化的静态数组里，抛出来的是
     * {@code ExceptionInInitializerError}，屏幕构造整个失败——表现就是「方块右键点不开界面，客户端跟着断线」。</p>
     *
     * <p>字符串与样式文档里那两处声明逐字一致（都是 {@code textures/gui/states.png}）：文档那份由 AE2 用
     * {@code ResourceLocation.parse} 解析，与这里的 {@code makeId} 是同一个口径。</p>
     */
    private static Blitter icon(int x) {
        return Blitter.texture(AE2ModularCells.makeId("textures/gui/states.png"), 256, 256).src(x, 0, 16, 16);
    }

    /** 按钮自己的动作入口：{@code Button} 的 onPress 在构造里绑定，这里只是把语义说清楚。 */
    public void press() {
        this.onPress.run();
    }

    /** 图标跟着当前档位走：玩家从按钮上就能看出现在是哪一种。 */
    @Override
    public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partial) {
        var background = isHovered() ? Icon.TOOLBAR_BUTTON_BACKGROUND_HOVER : Icon.TOOLBAR_BUTTON_BACKGROUND;
        background.getBlitter().dest(getX() - 1, getY(), 18, 20).zOffset(2).blit(guiGraphics);

        var icon = iconFor(NumberDisplayMode.current());
        if (!this.active) {
            // 必须 copy：Blitter 的 opacity 是实例状态，直接改会把这张切片永久弄成半透明
            // （它是每次 iconFor 新建的还好，但一旦有人把它缓存起来就会串给所有按钮）。
            icon = icon.copy().opacity(0.5F);
        }
        icon.dest(getX(), getY()).zOffset(3).blit(guiGraphics);
    }

    @Override
    public List<Component> getTooltipMessage() {
        // 两行：一行说这个按钮管什么，一行说当前是哪种模式（模式名本身就是「字母计数 / 科学计数 / 工程计数」）。
        return List.of(
                Component.translatable("gui.ae2modularcells.display_mode.tooltip"),
                NumberDisplayMode.current().displayName());
    }

    @Override
    public Rect2i getTooltipArea() {
        return new Rect2i(getX(), getY(), 16, 16);
    }

    @Override
    public boolean isTooltipAreaVisible() {
        return this.visible;
    }
}
