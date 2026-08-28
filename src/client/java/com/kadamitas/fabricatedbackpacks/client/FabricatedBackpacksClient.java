package com.kadamitas.fabricatedbackpacks.client;

import com.kadamitas.fabricatedbackpacks.client.render.BackpackRendering;
import com.kadamitas.fabricatedbackpacks.client.screen.BackpackScreen;
import com.kadamitas.fabricatedbackpacks.client.screen.EquipmentScreen;
import com.kadamitas.fabricatedbackpacks.client.sound.BackpackAudio;
import com.kadamitas.fabricatedbackpacks.menu.BackpackMenu;
import com.kadamitas.fabricatedbackpacks.menu.BackpackMenus;
import com.kadamitas.fabricatedbackpacks.network.BackpackNetworking;
import com.kadamitas.fabricatedbackpacks.network.BagSettings;
import com.kadamitas.fabricatedbackpacks.network.JukeboxAudio;
import com.kadamitas.fabricatedbackpacks.network.MenuAction;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.storage.BagComponents;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.MenuScreens;
import org.lwjgl.glfw.GLFW;

public final class FabricatedBackpacksClient implements ClientModInitializer {
    @Override public void onInitializeClient() {
        com.kadamitas.fabricatedbackpacks.gameplay.BackpackStashing.setClientScreenAllowed(() ->
                !(net.minecraft.client.Minecraft.getInstance().gui.screen() instanceof net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen));
        var localRules = com.kadamitas.fabricatedbackpacks.config.BackpackConfig.get();
        ClientPlayNetworking.registerGlobalReceiver(com.kadamitas.fabricatedbackpacks.network.ServerRules.TYPE, (packet, context) -> context.client().execute(() ->
                com.kadamitas.fabricatedbackpacks.config.BackpackConfig.configure(com.kadamitas.fabricatedbackpacks.config.ConfigFile.decode(packet.json()))));
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
                com.kadamitas.fabricatedbackpacks.config.BackpackConfig.configure(localRules));
        MenuScreens.register(BackpackMenus.BACKPACK, BackpackScreen::new);
        MenuScreens.register(BackpackMenus.EQUIPMENT, EquipmentScreen::new);
        BackpackRendering.initialize();
        com.kadamitas.fabricatedbackpacks.client.automation.AutomationRendering.initialize();
        com.kadamitas.fabricatedbackpacks.client.tooltip.BackpackTooltips.initialize();
        com.kadamitas.fabricatedbackpacks.client.screen.WorkstationControls.initialize();
        var category = KeyMapping.Category.register(BackpackRegistry.id("backpacks"));
        var open = key("open", GLFW.GLFW_KEY_B, category);
        var gear = key("equipment", GLFW.GLFW_KEY_G, category);
        var browser = key("browser", GLFW.GLFW_KEY_O, category);
        var transfer = key("transfer", GLFW.GLFW_KEY_C, category);
        var deposit = key("deposit", GLFW.GLFW_KEY_UNKNOWN, category);
        var restock = key("restock", GLFW.GLFW_KEY_UNKNOWN, category);
        var tool = key("tool_cycle", GLFW.GLFW_KEY_K, category);
        com.kadamitas.fabricatedbackpacks.client.screen.BackpackInput.initialize(category);
        ClientPlayNetworking.registerGlobalReceiver(JukeboxAudio.TYPE, (packet, context) -> context.client().execute(() -> BackpackAudio.receive(packet)));
        ClientPlayNetworking.registerGlobalReceiver(BagSettings.TYPE, (packet, context) -> context.client().execute(() -> {
            if (context.player().containerMenu instanceof BackpackMenu menu && menu.containerId == packet.containerId()) {
                menu.bag().stack().set(BagComponents.SETTINGS, packet.settings());
                menu.bag().stack().set(BagComponents.MEMORY, packet.memory());
            }
        }));
        com.kadamitas.fabricatedbackpacks.client.browser.RecipeBrowserClient.initialize();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            BackpackAudio.tick(client);
            if (client.player == null) return;
            while (open.consumeClick()) if (client.gui.screen() == null) send("open");
            while (gear.consumeClick()) if (client.gui.screen() == null) send("equipment");
            while (transfer.consumeClick()) if (client.gui.screen() == null) send("transfer");
            while (deposit.consumeClick()) if (client.gui.screen() == null) send("deposit");
            while (restock.consumeClick()) if (client.gui.screen() == null) send("restock");
            while (tool.consumeClick()) if (client.gui.screen() == null) send("tool_cycle");
            while (browser.consumeClick()) com.kadamitas.fabricatedbackpacks.client.browser.RecipeBrowserClient.open(client.gui.screen());
        });
    }
    private static KeyMapping key(String action, int code, KeyMapping.Category category) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping("key.fabricated_backpacks." + action, code, category));
    }
    private static void send(String action) { ClientPlayNetworking.send(new MenuAction(-1, action, 0, 0, "")); }
}
