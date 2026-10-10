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

public final class AWAssistant implements ClientModInitializer {
    public static KeyMapping OPEN;
    public static RuleRepository RULES;
    public static boolean animations = true;
    @Override public void onInitializeClient() {
        RULES = new RuleRepository(FabricLoader.getInstance().getConfigDir());
        var category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("awassistant", "assistant"));
        OPEN = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.awassistant.open", ClientBridge.keyboard(), InputConstants.KEY_F8, category));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (OPEN.consumeClick()) if (ClientBridge.screen(client) == null || ClientBridge.screen(client) instanceof net.minecraft.client.gui.screens.TitleScreen)
                ClientBridge.screen(client, new RulesScreen(ClientBridge.screen(client)));
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> RULES.close());
    }
}
