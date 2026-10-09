package dev.uninery.quickhatch.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

public final class KeyBindings {

    public static final KeyMapping OPEN_SELECT = new KeyMapping(
            "key.quickhatch.open_select",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            "key.categories.quickhatch");

    /**
     * 对着"可替换方块"打开替换界面。
     *
     * <p>默认是<b>鼠标左键</b>（原版按键设置里显示成鼠标按键，可以随便改）；
     * 因为左键本身是攻击键，所以绑定为左键时会要求<b>同时按住 Ctrl</b>
     * （原版会把 Ctrl+左键当疾跑吃掉，所以这个组合只能靠轮询 GLFW 才收得到）。
     * 绑到别的键时不需要 Ctrl。</p>
     */
    public static final KeyMapping REPLACE_HATCH = new KeyMapping(
            "key.quickhatch.replace_hatch",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.MOUSE,
            GLFW.GLFW_MOUSE_BUTTON_LEFT,
            "key.categories.quickhatch");

    private KeyBindings() {}
}
