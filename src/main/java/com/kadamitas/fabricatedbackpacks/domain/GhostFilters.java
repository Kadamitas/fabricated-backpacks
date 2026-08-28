package com.kadamitas.fabricatedbackpacks.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Bounded configuration slots. Lowering the configured count does not delete saved entries. */
public record GhostFilters(int configuredSlots, List<Optional<ItemDescriptor>> slots) {
    public static final int MAX_SLOTS = 64;

    public GhostFilters {
        Objects.requireNonNull(slots, "slots");
        if (configuredSlots < 0 || configuredSlots > MAX_SLOTS || slots.size() > MAX_SLOTS) {
            throw new IllegalArgumentException("Ghost slot count is out of range");
        }
        List<Optional<ItemDescriptor>> normalized = new ArrayList<>(slots);
        for (int index = 0; index < normalized.size(); index++) {
            Optional<ItemDescriptor> entry = Objects.requireNonNull(normalized.get(index), "slot");
            if (entry.isPresent() && entry.get().isEmpty()) normalized.set(index, Optional.empty());
        }
        while (normalized.size() < configuredSlots) normalized.add(Optional.empty());
        for (int left = 0; left < normalized.size(); left++) {
            if (normalized.get(left).isEmpty()) continue;
            for (int right = left + 1; right < normalized.size(); right++) {
                if (normalized.get(right).isPresent()
                        && normalized.get(left).get().sameItemAndComponents(normalized.get(right).get())) {
                    throw new IllegalArgumentException("Duplicate ghost item");
                }
            }
        }
        slots = List.copyOf(normalized);
    }

    public static GhostFilters empty(int configuredSlots) {
        return new GhostFilters(configuredSlots, List.of());
    }

    public GhostFilters with(int slot, ItemDescriptor item) {
        Objects.checkIndex(slot, slots.size());
        Objects.requireNonNull(item, "item");
        if (item.isEmpty()) return clear(slot);
        for (int index = 0; index < slots.size(); index++) {
            if (index != slot && slots.get(index).isPresent()
                    && slots.get(index).get().sameItemAndComponents(item)) return this;
        }
        List<Optional<ItemDescriptor>> changed = new ArrayList<>(slots);
        changed.set(slot, Optional.of(item));
        return new GhostFilters(configuredSlots, changed);
    }

    public GhostFilters clear(int slot) {
        Objects.checkIndex(slot, slots.size());
        if (slots.get(slot).isEmpty()) return this;
        List<Optional<ItemDescriptor>> changed = new ArrayList<>(slots);
        changed.set(slot, Optional.empty());
        return new GhostFilters(configuredSlots, changed);
    }

    public GhostFilters withConfiguredSlots(int count) {
        return new GhostFilters(count, slots);
    }

    public List<ItemDescriptor> entries() {
        return slots.stream().flatMap(Optional::stream).toList();
    }
}
