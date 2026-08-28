package com.kadamitas.fabricatedbackpacks.resource;

import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.storage.InstalledUpgrade;
import com.kadamitas.fabricatedbackpacks.upgrade.UpgradeEngine;
import com.kadamitas.fabricatedbackpacks.upgrade.UpgradeFilters;
import net.fabricmc.fabric.api.transfer.v1.context.ContainerItemContext;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StoragePreconditions;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;

import java.util.Iterator;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Explicitly filtered fluid disposal is an admission policy; stored fluid remains fully transactional. */
final class VoidFluidStorage implements Storage<FluidVariant> {
    private final BagInventory bag;
    private final Storage<FluidVariant> storage;
    private final BooleanSupplier available;

    VoidFluidStorage(BagInventory bag, Storage<FluidVariant> storage, BooleanSupplier available) {
        this.bag = bag;
        this.storage = storage;
        this.available = available;
    }

    @Override public boolean supportsInsertion() {
        if (!available.getAsBoolean()) return false;
        if (storage.supportsInsertion()) return true;
        for (InstalledUpgrade upgrade : bag.installedUpgrades()) {
            if (!upgrade.kind().family().equals("void") || !UpgradeFilters.enabled(bag, upgrade)) continue;
            String policy = UpgradeEngine.voidMode(bag.settings(upgrade));
            String filter = bag.settings(upgrade).getStringOr("filter_mode", "ALLOW");
            if (policy.equals("SLOT_OVERFLOW") || filter.equals("CONTENTS")) {
                // Slot overflow requires a stored representation, even when the filter names a fluid.
                for (var view : storage) if (view.getAmount() > 0 && !view.isResourceBlank()
                        && matches(upgrade, view.getResource())) return true;
            } else if (filter.equals("BLOCK") || hasSelectedFluid(upgrade)) return true;
        }
        return false;
    }

    @Override public boolean supportsExtraction() {
        return available.getAsBoolean() && storage.supportsExtraction();
    }

    private boolean hasSelectedFluid(InstalledUpgrade upgrade) {
        if (upgrade.stack().getOrDefault(ResourceComponents.VOID_FLUID_FILTERS, List.<FluidVariant>of()).stream()
                .limit(ResourceComponents.MAX_FLUID_FILTERS).anyMatch(fluid -> !fluid.isBlank())) return true;
        for (var ghost : bag.filterItems(upgrade)) {
            Storage<FluidVariant> contained = ContainerItemContext.withConstant(ghost.copyWithCount(1)).find(FluidStorage.ITEM);
            if (contained == null) continue;
            for (var view : contained) if (view.getAmount() > 0 && !view.isResourceBlank()) return true;
        }
        return false;
    }

    @Override public long insert(FluidVariant resource, long maximum, TransactionContext transaction) {
        StoragePreconditions.notBlankNotNegative(resource, maximum);
        if (maximum == 0 || !available.getAsBoolean()) return 0;
        InstalledUpgrade selected = bag.installedUpgrades().stream()
                .filter(upgrade -> upgrade.kind().family().equals("void") && UpgradeFilters.enabled(bag, upgrade)
                        && matches(upgrade, resource)).findFirst().orElse(null);
        if (selected == null) return storage.insert(resource, maximum, transaction);
        return switch (UpgradeEngine.voidMode(bag.settings(selected))) {
            case "ALWAYS" -> maximum;
            case "SLOT_OVERFLOW" -> insertOne(resource, maximum, transaction);
            default -> {
                long capacity = freeCapacity(resource);
                long inserted = storage.insert(resource, maximum, transaction);
                yield withOverflow(maximum, inserted, capacity);
            }
        };
    }

    private boolean matches(InstalledUpgrade upgrade, FluidVariant fluid) {
        String mode = bag.settings(upgrade).getStringOr("filter_mode", "ALLOW");
        if (mode.equals("CONTENTS")) {
            for (var view : storage) if (view.getAmount() > 0 && view.getResource().equals(fluid)) return true;
            return false;
        }
        boolean selected = upgrade.stack().getOrDefault(ResourceComponents.VOID_FLUID_FILTERS, List.<FluidVariant>of()).stream()
                .limit(ResourceComponents.MAX_FLUID_FILTERS).anyMatch(filter -> !filter.isBlank() && filter.equals(fluid));
        if (!selected) for (var ghost : bag.filterItems(upgrade)) {
            Storage<FluidVariant> contained = ContainerItemContext.withConstant(ghost.copyWithCount(1)).find(FluidStorage.ITEM);
            if (contained == null) continue;
            for (var view : contained) if (view.getAmount() > 0 && view.getResource().equals(fluid)) { selected = true; break; }
            if (selected) break;
        }
        return mode.equals("BLOCK") ? !selected : selected;
    }

    /** Rate-limited admission is not capacity overflow: preserve the part which could fit on a later operation. */
    private static long withOverflow(long requested, long inserted, long freeCapacity) {
        long overflow = Math.max(0, requested - freeCapacity);
        return inserted + Math.min(requested - inserted, overflow);
    }

    private long freeCapacity(FluidVariant fluid) {
        long free = 0;
        for (var view : storage) if (view.isResourceBlank() || view.getResource().equals(fluid)) {
            long room = Math.max(0, view.getCapacity() - view.getAmount());
            free += Math.min(room, Long.MAX_VALUE - free);
        }
        return free;
    }

    private long insertOne(FluidVariant fluid, long maximum, TransactionContext transaction) {
        for (var view : storage) if (view.getAmount() > 0 && view.getResource().equals(fluid)) {
            long room = Math.max(0, view.getCapacity() - view.getAmount());
            return withOverflow(maximum, insertView(view, fluid, maximum, transaction), room);
        }
        for (var view : storage) if (view.isResourceBlank() && view.getCapacity() > 0) {
            long capacity = view.getCapacity();
            long inserted = insertView(view, fluid, maximum, transaction);
            // Without even a first stored representation, SLOT_OVERFLOW is not permission to erase a fluid.
            if (inserted > 0) return withOverflow(maximum, inserted, capacity);
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private static long insertView(StorageView<FluidVariant> view, FluidVariant fluid, long maximum, TransactionContext transaction) {
        return view instanceof Storage<?> writable ? ((Storage<FluidVariant>) writable).insert(fluid, maximum, transaction) : 0;
    }

    @Override public long extract(FluidVariant resource, long maximum, TransactionContext transaction) {
        StoragePreconditions.notBlankNotNegative(resource, maximum);
        return available.getAsBoolean() ? storage.extract(resource, maximum, transaction) : 0;
    }

    @Override public Iterator<StorageView<FluidVariant>> iterator() {
        return available.getAsBoolean() ? storage.iterator() : List.<StorageView<FluidVariant>>of().iterator();
    }
}
