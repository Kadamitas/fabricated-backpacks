package com.kadamitas.fabricatedbackpacks.item;

import com.kadamitas.fabricatedbackpacks.config.BackpackConfig;
import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.storage.BagComponents;
import com.kadamitas.fabricatedbackpacks.storage.InventorySnapshot;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;

/** Immutable physical contents for a tooltip; enhanced counts remain separate from item templates. */
public record BackpackTooltip(int columns, InventorySnapshot contents) implements TooltipComponent {
    public BackpackTooltip {
        columns = Math.clamp(columns, 1, 12);
        int size = Math.clamp(contents.size(), 0, InventorySnapshot.MAX_SLOTS);
        var occupied = new HashSet<Integer>();
        var safe = new ArrayList<InventorySnapshot.Entry>();
        for (var entry : contents.entries()) {
            if (entry.slot() >= 0 && entry.slot() < size && entry.count() > 0 && occupied.add(entry.slot())) safe.add(entry);
        }
        contents = new InventorySnapshot(size, safe);
    }

    public static BackpackTooltip from(ItemStack backpack) {
        BackpackTier tier = BackpackRegistry.tier(backpack).orElse(BackpackTier.LEATHER);
        InventorySnapshot snapshot = backpack.getOrDefault(BagComponents.CONTENTS, InventorySnapshot.EMPTY);
        int configured = BackpackConfig.get().capacity(tier).slots();
        boolean widens = snapshot.size() > 0 && snapshot.size() <= 81 && configured > 81;
        int retained = widens ? Math.ceilDiv(snapshot.size(), 9) * 12 : snapshot.size();
        int size = Math.clamp(Math.max(configured, retained), 1, InventorySnapshot.MAX_SLOTS);
        var entries = widens ? snapshot.entries().stream().map(entry -> new InventorySnapshot.Entry(
                entry.slot() / 9 * 12 + entry.slot() % 9, entry.item(), entry.count())).toList() : snapshot.entries();
        return new BackpackTooltip(size <= 81 ? 9 : 12, new InventorySnapshot(size, entries));
    }

    public long itemCount() {
        return contents.entries().stream().mapToLong(InventorySnapshot.Entry::count).sum();
    }

    public int occupiedSlots() { return contents.entries().size(); }
}
