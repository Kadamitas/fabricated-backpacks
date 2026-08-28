package com.kadamitas.fabricatedbackpacks.client.screen;

import com.kadamitas.fabricatedbackpacks.client.mixin.ContainerScreenAccess;
import com.kadamitas.fabricatedbackpacks.network.MenuAction;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.lwjgl.glfw.GLFW;

/** Rebindable inventory opening, mouse bindings, and five explicit upgrade shortcuts. */
public final class BackpackInput {
    private BackpackInput() {}
    public static void initialize(KeyMapping.Category category) {
        KeyMapping[] toggles = new KeyMapping[5];
        for (int index = 0; index < toggles.length; index++) toggles[index] = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.fabricated_backpacks.upgrade_" + (index + 1), index == 0 ? GLFW.GLFW_KEY_Z : index == 1 ? GLFW.GLFW_KEY_X : GLFW.GLFW_KEY_UNKNOWN, category));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            boolean alt = InputConstants.isKeyDown(client.getWindow(), GLFW.GLFW_KEY_LEFT_ALT)
                    || InputConstants.isKeyDown(client.getWindow(), GLFW.GLFW_KEY_RIGHT_ALT);
            for (int index = 0; index < toggles.length; index++) while (toggles[index].consumeClick()) {
                if (alt && client.player != null && client.gui.screen() == null)
                    ClientPlayNetworking.send(new MenuAction(-1, "toggle_upgrade", index, 0, ""));
            }
        });
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof AbstractContainerScreen<?> container)) return;
            ScreenKeyboardEvents.allowKeyPress(screen).register((current, event) -> {
                KeyMapping open = KeyMapping.get("key.fabricated_backpacks.open");
                if (open == null || !open.matches(event) || textInputFocused(current, 0)) return true;
                activate(container);
                return false;
            });
            ScreenMouseEvents.allowMouseClick(screen).register((current, event) -> {
                KeyMapping open = KeyMapping.get("key.fabricated_backpacks.open");
                if (open == null || !open.matchesMouse(event) || textInputFocused(current, 0)) return true;
                activate(container);
                return false;
            });
        });
    }
    private static void activate(AbstractContainerScreen<?> screen) {
        if (!screen.getMenu().getCarried().isEmpty()) return;
        var hovered = ((ContainerScreenAccess) screen).fabricatedBackpacks$hoveredSlot();
        if (hovered != null && hovered.isActive() && BackpackRegistry.isBackpack(hovered.getItem())) {
            int index = screen.getMenu().slots.indexOf(hovered);
            if (index >= 0) ClientPlayNetworking.send(new MenuAction(screen.getMenu().containerId, "open_slot", index, 0, ""));
        } else if (screen instanceof BackpackScreen) screen.onClose();
        else if (Minecraft.getInstance().player != null) ClientPlayNetworking.send(new MenuAction(-1, "open", 0, 0, ""));
    }
    private static boolean textInputFocused(GuiEventListener widget, int depth) {
        if (widget instanceof EditBox box && box.isFocused()) return true;
        if (depth < 16 && widget instanceof ContainerEventHandler parent)
            for (GuiEventListener child : parent.children()) if (textInputFocused(child, depth + 1)) return true;
        return false;
    }
}
