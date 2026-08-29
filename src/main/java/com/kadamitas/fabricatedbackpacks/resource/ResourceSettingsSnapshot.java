package com.kadamitas.fabricatedbackpacks.resource;

import com.kadamitas.fabricatedbackpacks.storage.BagComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.List;

/** Rollback preserves owned tags exactly, including their absence, without reverting other preferences. */
record ResourceSettingsSnapshot(CustomData settings, List<String> fields) {
    static ResourceSettingsSnapshot capture(ItemStack upgrade, String... fields) {
        return new ResourceSettingsSnapshot(upgrade.get(BagComponents.SETTINGS), List.of(fields));
    }

    void restore(ItemStack upgrade) {
        CompoundTag current = upgrade.getOrDefault(BagComponents.SETTINGS, CustomData.EMPTY).copyTag();
        CompoundTag previous = settings == null ? new CompoundTag() : settings.copyTag();
        for (String field : fields) {
            Tag original = previous.get(field);
            if (original == null) current.remove(field);
            else current.put(field, original.copy());
        }
        if (current.isEmpty() && settings == null) upgrade.remove(BagComponents.SETTINGS);
        else upgrade.set(BagComponents.SETTINGS, CustomData.of(current));
    }
}
