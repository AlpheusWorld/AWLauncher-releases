package ru.aw.assistant;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
public final class ClientBridge {
    public static net.minecraft.client.renderer.texture.DynamicTexture smoothTexture(com.mojang.blaze3d.platform.NativeImage pixels) {
        return new net.minecraft.client.renderer.texture.DynamicTexture(()->"AWAssistant Onest atlas",pixels) {
            { this.sampler=com.mojang.blaze3d.systems.RenderSystem.getSamplerCache().getClampToEdge(com.mojang.renderpearl.api.textures.FilterMode.LINEAR); }
        };
    }
    public static com.mojang.blaze3d.pipeline.RenderTarget renderTarget(Minecraft client) { return client.gameRenderer.mainRenderTarget(); }
    public static Screen screen(Minecraft client) { return client.gui.screen(); }
    public static void screen(Minecraft client, Screen screen) { client.gui.setScreen(screen); }
    public static boolean ready(Minecraft client) { return client.gui.overlay() == null; }
    public static InputConstants.Type keyboard() { return InputConstants.Type.KEYBOARD; }
}
