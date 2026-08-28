package com.kadamitas.fabricatedbackpacks.menu;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import java.util.function.Consumer;

/** The standard 27 inventory cells and nine hotbar cells used by 1.21.1 menus. */
public final class MenuSlots {
    private MenuSlots() {}
    public static void addInventory(Consumer<Slot> add, Inventory inventory, int x, int y) {
        for (int row = 0; row < 3; row++) for (int column = 0; column < 9; column++)
            add.accept(new Slot(inventory, column + row * 9 + 9, x + column * 18, y + row * 18));
        for (int column = 0; column < 9; column++)
            add.accept(new Slot(inventory, column, x + column * 18, y + 58));
    }
}
