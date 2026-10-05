package dev.mcai.partner;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.PauseScreen;

/** The multiplayer Esc overlay does not own the AI's world controls. */
public final class ScreenPolicy {
    private ScreenPolicy() {}
    public static boolean blocksWorld(Screen screen) { return screen != null && !(screen instanceof PauseScreen); }
}
