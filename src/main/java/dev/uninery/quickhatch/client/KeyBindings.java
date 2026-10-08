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

    private KeyBindings() {}
}
