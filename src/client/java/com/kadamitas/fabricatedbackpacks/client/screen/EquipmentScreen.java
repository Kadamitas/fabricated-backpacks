package com.kadamitas.fabricatedbackpacks.client.screen;

import com.kadamitas.fabricatedbackpacks.menu.EquipmentMenu;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public final class EquipmentScreen extends AbstractContainerScreen<EquipmentMenu> {
    public EquipmentScreen(EquipmentMenu menu, Inventory inventory, Component title) { super(menu, inventory, title, 176, 166); }
    @Override public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractBackground(graphics, mouseX, mouseY, delta);
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xffcab894);
        graphics.outline(leftPos, topPos, imageWidth, imageHeight, 0xff4b3426);
        graphics.text(font, Component.translatable("screen.fabricated_backpacks.armor_kept"), leftPos + 8, topPos + 60, 0xff493829, false);
        for (var slot : menu.slots) {
            graphics.fill(leftPos + slot.x - 1, topPos + slot.y - 1, leftPos + slot.x + 17, topPos + slot.y + 17, 0xff6e5c48);
            graphics.fill(leftPos + slot.x, topPos + slot.y, leftPos + slot.x + 16, topPos + slot.y + 16, 0xffa79579);
        }
    }
}
