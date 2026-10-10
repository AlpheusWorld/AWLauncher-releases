package ru.aw.assistant;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
public final class ClientBridge {
    public static net.minecraft.client.renderer.texture.DynamicTexture smoothTexture(com.mojang.blaze3d.platform.NativeImage pixels) {
        return new net.minecraft.client.renderer.texture.DynamicTexture(()->"AWAssistant Onest atlas",pixels) {
            { this.sampler=com.mojang.blaze3d.systems.RenderSystem.getSamplerCache().getClampToEdge(com.mojang.blaze3d.textures.FilterMode.LINEAR); }
        };
    }
    public static com.mojang.blaze3d.pipeline.RenderTarget renderTarget(Minecraft client) { return client.getMainRenderTarget(); }
    public static Screen screen(Minecraft client) { return client.screen; }
    public static void screen(Minecraft client, Screen screen) { client.setScreen(screen); }
    public static boolean ready(Minecraft client) { return client.getOverlay() == null; }
    public static InputConstants.Type keyboard() { return InputConstants.Type.KEYSYM; }
}
