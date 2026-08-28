package com.kadamitas.fabricatedbackpacks.storage;

import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.component.CustomData;

import java.util.List;
import java.util.UUID;

/** Deliberate creative/recovery copies keep their contents but have independent identities. */
public final class BackpackCopies {
    private BackpackCopies() {}
    public static ItemStack fork(ItemStack original) { return fork(original, 0); }

    private static ItemStack fork(ItemStack original, int depth) {
        if (depth > 8) throw new IllegalArgumentException("Backpack component nesting is too deep");
        ItemStack copy = original.copy();
        if (BackpackRegistry.isBackpack(copy)) copy.set(BagComponents.IDENTITY, UUID.randomUUID().toString());
        for (var type : List.of(BagComponents.CONTENTS, BagComponents.UPGRADES,
                com.kadamitas.fabricatedbackpacks.world.WorldComponents.EXTRA_ITEMS)) {
            InventorySnapshot contents = copy.get(type);
            if (contents == null) continue;
            var entries = contents.entries().stream().map(entry -> {
                ItemStack item = entry.item().withCount(1).create();
                if (!BackpackRegistry.isBackpack(item) && !item.has(BagComponents.CONTENTS)
                        && !item.has(BagComponents.UPGRADES) && !item.has(com.kadamitas.fabricatedbackpacks.world.WorldComponents.EXTRA_ITEMS)) return entry;
                ItemStack unique = fork(item, depth + 1);
                return new InventorySnapshot.Entry(entry.slot(), ItemStackTemplate.fromNonEmptyStack(unique), entry.count());
            }).toList();
            copy.set(type, new InventorySnapshot(contents.size(), entries));
        }
        var settings = copy.getOrDefault(BagComponents.SETTINGS, CustomData.EMPTY).copyTag();
        var captures = settings.getListOrEmpty("captured_entities");
        if (!captures.isEmpty()) {
            for (int index = 0; index < captures.size(); index++) {
                var entity = captures.getCompoundOrEmpty(index).getCompoundOrEmpty("entity");
                // Captured entities cannot have passengers; each deliberate clone gets its own UUID.
                entity.remove("UUID");
            }
            copy.set(BagComponents.SETTINGS, CustomData.of(settings));
        }
        return copy;
    }
}
