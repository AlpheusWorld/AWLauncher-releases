package ru.aw.assistant;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import ru.aw.assistant.rules.RuleRepository;
import ru.aw.assistant.ui.RulesScreen;
import ru.aw.assistant.ui.AssistantHud;
import ru.aw.assistant.rules.SemanticSearch;

public final class AWAssistant implements ClientModInitializer {
    public static KeyMapping OPEN;
    public static KeyMapping EDIT_HUD;
    public static RuleRepository RULES;
    public static SemanticSearch SEMANTIC;
    public static AssistantHud HUD;
    public static boolean animations = true, blur = true, smartSearch=true;
    @Override public void onInitializeClient() {
        ru.aw.assistant.ui.UiRenderer.preload();
        RULES = new RuleRepository(FabricLoader.getInstance().getConfigDir());
        SEMANTIC=new SemanticSearch(java.nio.file.Path.of(System.getProperty("user.home"),".aw","cache","assistant-search-v1"));
        HUD=new AssistantHud();HUD.register();
        var category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("awassistant", "assistant"));
        OPEN = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.awassistant.open", ClientBridge.keyboard(), InputConstants.KEY_F8, category));
        EDIT_HUD=KeyMappingHelper.registerKeyMapping(new KeyMapping("key.awassistant.hud",ClientBridge.keyboard(),InputConstants.KEY_F9,category));
        RULES.reload().thenRun(()->net.minecraft.client.Minecraft.getInstance().execute(()->SEMANTIC.enabled(RULES.neural())));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            HUD.tick(client);
            while(EDIT_HUD.consumeClick())if(ClientBridge.screen(client)==null||ClientBridge.screen(client) instanceof net.minecraft.client.gui.screens.TitleScreen)ClientBridge.screen(client,HUD.editor(ClientBridge.screen(client)));
            while (OPEN.consumeClick()) if (ClientBridge.screen(client) == null || ClientBridge.screen(client) instanceof net.minecraft.client.gui.screens.TitleScreen)
                ClientBridge.screen(client, new RulesScreen(ClientBridge.screen(client)));
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> { SEMANTIC.close();RULES.close();ru.aw.assistant.ui.UiRenderer.close(); });
    }
}
