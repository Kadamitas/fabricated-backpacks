package com.kadamitas.fabricatedbackpacks.resource;

import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.storage.InstalledUpgrade;
import com.kadamitas.fabricatedbackpacks.upgrade.UpgradeEngine;
import com.kadamitas.fabricatedbackpacks.upgrade.UpgradeFilters;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.StoragePreconditions;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;

import java.util.Iterator;
import java.util.List;
import java.util.function.BooleanSupplier;

/** The item API uses the same explicit void admission modes as native pickup; leaf storage owns rollback. */
final class VoidItemStorage implements SlottedStorage<ItemVariant> {
    private final BagInventory bag;
    private final SlottedStorage<ItemVariant> storage;
    private final BooleanSupplier available;

    VoidItemStorage(BagInventory bag, SlottedStorage<ItemVariant> storage, BooleanSupplier available) {
        this.bag = bag;
        this.storage = storage;
        this.available = available;
    }

    @Override public long insert(ItemVariant resource, long maximum, TransactionContext transaction) {
        return insertInto(null, resource, maximum, transaction);
    }

    private long insertInto(SingleSlotStorage<ItemVariant> slot, ItemVariant resource, long maximum,
                            TransactionContext transaction) {
        StoragePreconditions.notBlankNotNegative(resource, maximum);
        if (maximum == 0 || !available.getAsBoolean() || !UpgradeEngine.acceptsInput(bag, resource.toStack())) return 0;
        InstalledUpgrade selected = BackpackRegistry.isBackpack(resource.toStack()) ? null : bag.installedUpgrades().stream()
                .filter(upgrade -> upgrade.kind().family().equals("void") && UpgradeFilters.enabled(bag, upgrade)
                        && UpgradeFilters.matches(bag, upgrade, resource.toStack())).findFirst().orElse(null);
        if (selected == null) return slot == null ? storage.insert(resource, maximum, transaction) : slot.insert(resource, maximum, transaction);
        return switch (UpgradeEngine.voidMode(bag.settings(selected))) {
            case "ALWAYS" -> maximum;
            case "SLOT_OVERFLOW" -> {
                var settings = bag.settings(selected);
                long represented = 0;
                for (var view : storage) if (!view.isResourceBlank() && UpgradeFilters.same(resource.toStack(), view.getResource().toStack(),
                        "ITEM", settings.getBooleanOr("match_damage", false), settings.getBooleanOr("match_components", false))) {
                    represented += Math.min(view.getAmount(), Long.MAX_VALUE - represented);
                }
                long allowance = Math.max(0, bag.capacity(resource.toStack()) - Math.min(Integer.MAX_VALUE, represented));
                long attempt = Math.min(maximum, allowance);
                long inserted = attempt == 0 ? 0 : slot == null ? storage.insert(resource, attempt, transaction) : slot.insert(resource, attempt, transaction);
                yield represented == 0 && inserted == 0 ? 0 : maximum - (attempt - inserted);
            }
            default -> {
                if (slot == null) { storage.insert(resource, maximum, transaction); yield maximum; }
                long inserted = slot.insert(resource, maximum, transaction);
                long remaining = maximum - inserted;
                // A full selected slot is not a full backpack. Only discard genuine aggregate
                // overflow; leave anything that fits another slot for the caller to route there.
                try (Transaction probe = Transaction.openNested(transaction)) {
                    long fitsElsewhere = storage.insert(resource, remaining, probe);
                    yield maximum - fitsElsewhere;
                }
            }
        };
    }

    @Override public long extract(ItemVariant resource, long maximum, TransactionContext transaction) {
        StoragePreconditions.notBlankNotNegative(resource, maximum);
        return available.getAsBoolean() ? storage.extract(resource, maximum, transaction) : 0;
    }

    @Override public Iterator<StorageView<ItemVariant>> iterator() {
        return getSlots().stream().map(slot -> (StorageView<ItemVariant>) slot).iterator();
    }

    @Override public int getSlotCount() { return available.getAsBoolean() ? storage.getSlotCount() : 0; }
    @Override public SingleSlotStorage<ItemVariant> getSlot(int slot) {
        if (!available.getAsBoolean()) throw new IndexOutOfBoundsException("Backpack item storage is no longer available");
        return guarded(storage.getSlot(slot));
    }
    @Override public List<SingleSlotStorage<ItemVariant>> getSlots() {
        return available.getAsBoolean() ? storage.getSlots().stream().map(this::guarded).toList() : List.of();
    }

    private SingleSlotStorage<ItemVariant> guarded(SingleSlotStorage<ItemVariant> slot) {
        return new SingleSlotStorage<>() {
            @Override public ItemVariant getResource() { return slot.getResource(); }
            @Override public boolean isResourceBlank() { return slot.isResourceBlank(); }
            @Override public long getAmount() { return slot.getAmount(); }
            @Override public long getCapacity() { return slot.getCapacity(); }
            @Override public boolean supportsInsertion() { return slot.supportsInsertion(); }
            @Override public boolean supportsExtraction() { return slot.supportsExtraction(); }
            @Override public long insert(ItemVariant resource, long maximum, TransactionContext transaction) {
                return insertInto(slot, resource, maximum, transaction);
            }
            @Override public long extract(ItemVariant resource, long maximum, TransactionContext transaction) {
                return available.getAsBoolean() ? slot.extract(resource, maximum, transaction) : 0;
            }
        };
    }
}
