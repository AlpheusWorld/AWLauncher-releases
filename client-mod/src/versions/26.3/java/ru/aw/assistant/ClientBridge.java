package ru.aw.assistant;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
public final class ClientBridge {
    public static com.mojang.blaze3d.pipeline.RenderTarget renderTarget(Minecraft client) { return client.gameRenderer.mainRenderTarget(); }
    public static Screen screen(Minecraft client) { return client.gui.screen(); }
    public static void screen(Minecraft client, Screen screen) { client.gui.setScreen(screen); }
    public static boolean ready(Minecraft client) { return client.gui.overlay() == null; }
    public static InputConstants.Type keyboard() { return InputConstants.Type.KEYBOARD; }
}
