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
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import com.kadamitas.fabricatedbackpacks.platform.network.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;


public final class FabricatedBackpacksClient {
    public static void initialize(IEventBus modBus) {
        com.kadamitas.fabricatedbackpacks.gameplay.BackpackStashing.setClientScreenAllowed(() ->
                !(net.minecraft.client.Minecraft.getInstance().gui.screen() instanceof net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen));
        ClientPlayNetworking.registerGlobalReceiver(com.kadamitas.fabricatedbackpacks.network.ServerRules.TYPE, (packet, context) -> context.client().execute(() ->
                com.kadamitas.fabricatedbackpacks.config.BackpackConfig.configure(com.kadamitas.fabricatedbackpacks.config.ConfigFile.decode(packet.json()))));
        // Common configuration is loaded during registry bootstrap, after the
        // mod constructor. Capture it only once that bootstrap has completed.
        modBus.addListener((net.neoforged.fml.event.lifecycle.FMLClientSetupEvent setup) -> {
            var localRules = com.kadamitas.fabricatedbackpacks.config.BackpackConfig.get();
            NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) ->
                    com.kadamitas.fabricatedbackpacks.config.BackpackConfig.configure(localRules));
        });
        modBus.addListener((RegisterMenuScreensEvent event) -> {
            event.register(BackpackMenus.BACKPACK, BackpackScreen::new);
            event.register(BackpackMenus.EQUIPMENT, EquipmentScreen::new);
        });
        BackpackRendering.initialize(modBus);
        com.kadamitas.fabricatedbackpacks.client.automation.AutomationRendering.initialize(modBus);
        com.kadamitas.fabricatedbackpacks.client.tooltip.BackpackTooltips.initialize(modBus);
        com.kadamitas.fabricatedbackpacks.client.screen.WorkstationControls.initialize();
        modBus.addListener(FabricatedBackpacksClient::registerKeys);
        ClientPlayNetworking.registerGlobalReceiver(JukeboxAudio.TYPE, (packet, context) -> context.client().execute(() -> BackpackAudio.receive(packet)));
        ClientPlayNetworking.registerGlobalReceiver(BagSettings.TYPE, (packet, context) -> context.client().execute(() -> {
            if (context.player().containerMenu instanceof BackpackMenu menu && menu.containerId == packet.containerId()) {
                menu.bag().stack().set(BagComponents.SETTINGS, packet.settings());
                menu.bag().stack().set(BagComponents.MEMORY, packet.memory());
            }
        }));
        com.kadamitas.fabricatedbackpacks.client.browser.RecipeBrowserClient.initialize(modBus);
    }
    private static void registerKeys(RegisterKeyMappingsEvent event) {
        var category = new KeyMapping.Category(BackpackRegistry.id("backpacks"));
        event.registerCategory(category);
        var open = key(event, "open", com.mojang.blaze3d.platform.InputConstants.KEY_B, category);
        var gear = key(event, "equipment", com.mojang.blaze3d.platform.InputConstants.KEY_G, category);
        var browser = key(event, "browser", com.mojang.blaze3d.platform.InputConstants.KEY_O, category);
        var transfer = key(event, "transfer", com.mojang.blaze3d.platform.InputConstants.KEY_C, category);
        var deposit = key(event, "deposit", com.mojang.blaze3d.platform.InputConstants.UNKNOWN.getValue(), category);
        var restock = key(event, "restock", com.mojang.blaze3d.platform.InputConstants.UNKNOWN.getValue(), category);
        var tool = key(event, "tool_cycle", com.mojang.blaze3d.platform.InputConstants.KEY_K, category);
        com.kadamitas.fabricatedbackpacks.client.screen.BackpackInput.initialize(event, category);
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post tick) -> {
            Minecraft client = Minecraft.getInstance();
            BackpackAudio.tick(client);
            consumeInWorld(client, open, () -> send("open"));
            consumeInWorld(client, gear, () -> send("equipment"));
            consumeInWorld(client, transfer, () -> send("transfer"));
            consumeInWorld(client, deposit, () -> send("deposit"));
            consumeInWorld(client, restock, () -> send("restock"));
            consumeInWorld(client, tool, () -> send("tool_cycle"));
            consumeInWorld(client, browser, () -> com.kadamitas.fabricatedbackpacks.client.browser.RecipeBrowserClient.open(null));
        });
    }
    private static KeyMapping key(RegisterKeyMappingsEvent event, String action, int code, KeyMapping.Category category) {
        KeyMapping key = new KeyMapping("key.fabricated_backpacks." + action, code, category);
        event.register(key);
        return key;
    }
    private static void consumeInWorld(Minecraft client, KeyMapping mapping, Runnable action) {
        while (mapping.consumeClick()) if (client.player != null && client.gui.screen() == null) action.run();
    }
    private static void send(String action) { ClientPlayNetworking.send(new MenuAction(-1, action, 0, 0, "")); }
}
