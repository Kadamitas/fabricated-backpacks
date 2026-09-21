package com.kadamitas.fabricatedbackpacks.client.screen;

import com.kadamitas.fabricatedbackpacks.network.MenuAction;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraftforge.event.TickEvent.ClientTickEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import com.kadamitas.fabricatedbackpacks.platform.network.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;


/** Five rebindable upgrade shortcuts. Global backpack actions stay disabled while any screen is open. */
public final class BackpackInput {
    private BackpackInput() {}
    public static void initialize(RegisterKeyMappingsEvent event, KeyMapping.Category category) {
        KeyMapping[] toggles = new KeyMapping[5];
        for (int index = 0; index < toggles.length; index++) {
            toggles[index] = new KeyMapping(
                    "key.fabricated_backpacks.upgrade_" + (index + 1), index == 0 ? InputConstants.KEY_Z : index == 1 ? InputConstants.KEY_X : InputConstants.UNKNOWN.getValue(), category);
            event.register(toggles[index]);
        }
        ClientTickEvent.Post.BUS.addListener((ClientTickEvent.Post tick) -> {
            Minecraft client = Minecraft.getInstance();
            boolean alt = InputConstants.isKeyDown(InputConstants.KEY_LALT)
                    || InputConstants.isKeyDown(InputConstants.KEY_RALT);
            for (int index = 0; index < toggles.length; index++) while (toggles[index].consumeClick()) {
                if (alt && client.player != null && client.gui.screen() == null)
                    ClientPlayNetworking.send(new MenuAction(-1, "toggle_upgrade", index, 0, ""));
            }
        });
    }
}
