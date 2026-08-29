package com.kadamitas.fabricatedbackpacks.client.browser;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import com.kadamitas.fabricatedbackpacks.browser.BrowserCatalogRequest;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.inventory.StonecutterMenu;

/** Adds an explicit browser button without binding menu keystrokes or consuming text input. */
final class BrowserScreenHooks {
    private BrowserScreenHooks() {}

    static void initialize() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof AbstractContainerScreen<?> container)) return;
            var menu = container.getMenu();
            if (!(menu instanceof CraftingMenu || menu instanceof StonecutterMenu || menu instanceof SmithingMenu
                    || menu instanceof AnvilMenu || menu instanceof AbstractFurnaceMenu)) return;
            Button browser = Button.builder(Component.translatable("browser.fabricated_backpacks.open"), ignored -> RecipeBrowserClient.open(screen))
                    .bounds(Math.max(8, width - 90), 6, 82, 18).build();
            browser.setTooltip(Tooltip.create(Component.translatable("browser.fabricated_backpacks.open")));
            browser.active = ClientPlayNetworking.canSend(BrowserCatalogRequest.TYPE);
            Screens.getWidgets(screen).add(browser);
        });
    }
}
