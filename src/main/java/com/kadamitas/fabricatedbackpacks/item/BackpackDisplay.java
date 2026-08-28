package com.kadamitas.fabricatedbackpacks.item;

import com.kadamitas.fabricatedbackpacks.config.BackpackConfig;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.storage.BagComponents;
import com.kadamitas.fabricatedbackpacks.storage.InventorySnapshot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.Optional;

/** Read-only exterior selection shared by native renderers and common tests. */
public record BackpackDisplay(ItemStack icon, int rotation, int depth) {
    public BackpackDisplay {
        icon = icon.copyWithCount(1);
        rotation = Math.floorMod(rotation, 360) / 45 * 45;
        depth = Math.clamp(depth, -16, 16);
    }

    public static Optional<BackpackDisplay> from(ItemStack backpack) {
        if (!BackpackConfig.get().storage().displayItems() || !BackpackRegistry.isBackpack(backpack)) return Optional.empty();
        var settings = backpack.getOrDefault(BagComponents.SETTINGS, CustomData.EMPTY).copyTag();
        int slot = settings.getIntOr("display_slot", -1);
        InventorySnapshot contents = backpack.getOrDefault(BagComponents.CONTENTS, InventorySnapshot.EMPTY);
        int slots = Math.clamp(Math.max(contents.size(),
                BackpackConfig.get().capacity(BackpackRegistry.tier(backpack).orElseThrow()).slots()), 1, InventorySnapshot.MAX_SLOTS);
        if (slot < 0 || slot >= slots) return Optional.empty();
        ItemStack selected = itemAt(contents, slot);
        if (selected.isEmpty()) selected = itemAt(backpack.getOrDefault(BagComponents.MEMORY, InventorySnapshot.EMPTY), slot);
        return selected.isEmpty() ? Optional.empty()
                : Optional.of(new BackpackDisplay(selected, settings.getIntOr("display_rotation", 0), settings.getIntOr("display_depth", 0)));
    }

    private static ItemStack itemAt(InventorySnapshot snapshot, int slot) {
        for (var entry : snapshot.entries()) if (entry.slot() == slot && entry.count() > 0) return entry.item().withCount(1).create();
        return ItemStack.EMPTY;
    }
}
