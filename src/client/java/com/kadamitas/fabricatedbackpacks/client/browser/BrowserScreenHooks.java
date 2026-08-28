package com.kadamitas.fabricatedbackpacks.client.browser;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import com.kadamitas.fabricatedbackpacks.browser.BrowserCatalogRequest;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.inventory.StonecutterMenu;

/** Opens the browser without replacing vanilla workstation rules or consuming text input. */
final class BrowserScreenHooks {
    private BrowserScreenHooks() {}

    static void initialize() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof AbstractContainerScreen<?> container)) return;
            // Fabric recreates these per-screen events on every init, including resize.
            ScreenKeyboardEvents.allowKeyPress(screen).register((current, event) -> {
                if (event.hasAltDown() || event.hasControlDown() || event.hasShiftDown() || textInputFocused(current, 0)) return true;
                KeyMapping key = browserKey(client);
                if (key == null || !key.matches(event)) return true;
                RecipeBrowserClient.open(current);
                return false;
            });
            var menu = container.getMenu();
            if (!(menu instanceof CraftingMenu || menu instanceof StonecutterMenu || menu instanceof SmithingMenu
                    || menu instanceof AnvilMenu || menu instanceof AbstractFurnaceMenu)) return;
            Button browser = Button.builder(Component.translatable("browser.fabricated_backpacks.open"), ignored -> RecipeBrowserClient.open(screen))
                    .bounds(Math.max(8, width - 90), 6, 82, 18).build();
            KeyMapping key = browserKey(client);
            browser.setTooltip(Tooltip.create(key == null ? Component.translatable("browser.fabricated_backpacks.open")
                    : Component.translatable("browser.fabricated_backpacks.open_key", key.getTranslatedKeyMessage())));
            browser.active = ClientPlayNetworking.canSend(BrowserCatalogRequest.TYPE);
            Screens.getWidgets(screen).add(browser);
        });
    }

    private static KeyMapping browserKey(Minecraft client) {
        for (KeyMapping key : client.options.keyMappings) if (key.getName().equals("key.fabricated_backpacks.browser")) return key;
        return null;
    }

    private static boolean textInputFocused(GuiEventListener widget, int depth) {
        if (widget instanceof EditBox box && box.isFocused()) return true;
        if (depth < 16 && widget instanceof ContainerEventHandler container) {
            for (GuiEventListener child : container.children()) if (textInputFocused(child, depth + 1)) return true;
        }
        return false;
    }
}
