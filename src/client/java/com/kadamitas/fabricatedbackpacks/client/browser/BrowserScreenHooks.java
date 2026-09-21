package com.kadamitas.fabricatedbackpacks.client.browser;

import com.kadamitas.fabricatedbackpacks.platform.network.ClientPlayNetworking;
import net.minecraftforge.client.event.ScreenEvent;
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
        ScreenEvent.Init.Post.BUS.addListener((ScreenEvent.Init.Post event) -> {
            var screen = event.getScreen();
            if (!(screen instanceof AbstractContainerScreen<?> container)) return;
            var menu = container.getMenu();
            if (!(menu instanceof CraftingMenu || menu instanceof StonecutterMenu || menu instanceof SmithingMenu
                    || menu instanceof AnvilMenu || menu instanceof AbstractFurnaceMenu)) return;
            Button browser = Button.builder(Component.translatable("browser.fabricated_backpacks.open"), ignored -> RecipeBrowserClient.open(screen))
                    .bounds(Math.max(8, screen.width - 90), 6, 82, 18).build();
            browser.setTooltip(Tooltip.create(Component.translatable("browser.fabricated_backpacks.open")));
            browser.active = ClientPlayNetworking.canSend(BrowserCatalogRequest.TYPE);
            event.addListener(browser);
        });
    }
}
