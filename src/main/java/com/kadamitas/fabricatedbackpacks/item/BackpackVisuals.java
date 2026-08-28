package com.kadamitas.fabricatedbackpacks.item;

import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.storage.BagComponents;
import com.kadamitas.fabricatedbackpacks.storage.InventorySnapshot;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import com.kadamitas.fabricatedbackpacks.compat.ItemStackTemplate;
import net.minecraft.world.item.component.CustomData;

import java.util.List;

/** Public appearance is constructed from an allowlist, never from a copy of private storage. */
public final class BackpackVisuals {
    private BackpackVisuals() {}

    public static ItemStack snapshot(ItemStack source) {
        if (!BackpackRegistry.isBackpack(source)) return ItemStack.EMPTY;
        ItemStack visual = icon(source);
        BackpackDisplay.from(source).ifPresent(display -> {
            var settings = new CompoundTag();
            settings.putInt("display_slot", 0);
            settings.putInt("display_rotation", display.rotation());
            settings.putInt("display_depth", display.depth());
            visual.set(BagComponents.SETTINGS, CustomData.of(settings));
            visual.set(BagComponents.CONTENTS, new InventorySnapshot(1, List.of(new InventorySnapshot.Entry(
                    0, ItemStackTemplate.fromNonEmptyStack(icon(display.icon())), 1))));
        });
        return visual;
    }

    private static ItemStack icon(ItemStack source) {
        ItemStack icon = new ItemStack(source.getItem());
        copy(source, icon, BagComponents.COLORS);
        copy(source, icon, DataComponents.CUSTOM_MODEL_DATA);
        copy(source, icon, DataComponents.DYED_COLOR);
        copy(source, icon, DataComponents.POTION_CONTENTS);
        copy(source, icon, DataComponents.MAP_COLOR);
        copy(source, icon, DataComponents.TRIM);
        copy(source, icon, DataComponents.BANNER_PATTERNS);
        copy(source, icon, DataComponents.BASE_COLOR);
        copy(source, icon, DataComponents.POT_DECORATIONS);
        copy(source, icon, DataComponents.PROFILE);
        if (source.hasFoil()) icon.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        return icon;
    }

    private static <T> void copy(ItemStack source, ItemStack target, DataComponentType<T> type) {
        T value = source.get(type);
        if (value != null) target.set(type, value);
    }
}
