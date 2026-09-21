package com.kadamitas.fabricatedbackpacks.client.screen;

import com.kadamitas.fabricatedbackpacks.network.MenuAction;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/** Five rebindable upgrade shortcuts. Global backpack actions stay disabled while any screen is open. */
public final class BackpackInput {
    private BackpackInput() {}
    public static void initialize(String category) {
        KeyMapping[] toggles = new KeyMapping[5];
        for (int index = 0; index < toggles.length; index++) toggles[index] = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.fabricated_backpacks.upgrade_" + (index + 1), index == 0 ? GLFW.GLFW_KEY_Z : index == 1 ? GLFW.GLFW_KEY_X : GLFW.GLFW_KEY_UNKNOWN, category));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            boolean alt = InputConstants.isKeyDown(client.getWindow().getWindow(), GLFW.GLFW_KEY_LEFT_ALT)
                    || InputConstants.isKeyDown(client.getWindow().getWindow(), GLFW.GLFW_KEY_RIGHT_ALT);
            for (int index = 0; index < toggles.length; index++) while (toggles[index].consumeClick()) {
                if (alt && client.player != null && client.screen == null)
                    ClientPlayNetworking.send(new MenuAction(-1, "toggle_upgrade", index, 0, ""));
            }
        });
    }
}
