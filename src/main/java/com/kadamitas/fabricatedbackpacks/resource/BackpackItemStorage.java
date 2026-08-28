package com.kadamitas.fabricatedbackpacks.resource;

import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import net.fabricmc.fabric.api.transfer.v1.item.ContainerStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StoragePreconditions;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Fabric's generic container adapter does not consult Container.canTakeItem; these views do. */
public final class BackpackItemStorage implements Storage<ItemVariant> {
    private final BagInventory bag;
    private final ContainerStorage delegate;
    private final List<View> views;

    public BackpackItemStorage(BagInventory bag, Direction direction) {
        this.bag = bag;
        delegate = ContainerStorage.of(bag, direction);
        List<View> slots = new ArrayList<>();
        for (int slot = 0; slot < bag.getContainerSize(); slot++) slots.add(new View(slot, delegate.getSlot(slot)));
        views = List.copyOf(slots);
    }

    @Override public long insert(ItemVariant resource, long maximum, TransactionContext transaction) {
        StoragePreconditions.notBlankNotNegative(resource, maximum);
        if (bag.infinityKind() == null) return delegate.insert(resource, maximum, transaction);
        long inserted = 0;
        for (View view : views) {
            inserted += view.insert(resource, maximum - inserted, transaction);
            if (inserted == maximum) break;
        }
        return inserted;
    }

    @Override public long extract(ItemVariant resource, long maximum, TransactionContext transaction) {
        StoragePreconditions.notBlankNotNegative(resource, maximum);
        long extracted = 0;
        for (StorageView<ItemVariant> view : views) {
            extracted += view.extract(resource, maximum - extracted, transaction);
            if (extracted == maximum) break;
        }
        return extracted;
    }

    @Override public Iterator<StorageView<ItemVariant>> iterator() {
        return views.stream().map(view -> (StorageView<ItemVariant>) view).iterator();
    }

    SingleSlotStorage<ItemVariant> slot(int slot) { return views.get(slot); }

    private final class View extends SnapshotParticipant<ItemStack> implements SingleSlotStorage<ItemVariant> {
        private final int slot;
        private final SingleSlotStorage<ItemVariant> storage;
        View(int slot, SingleSlotStorage<ItemVariant> storage) { this.slot = slot; this.storage = storage; }
        @Override public boolean isResourceBlank() { return getResource().isBlank(); }
        @Override public ItemVariant getResource() { return bag.infinityKind() != null ? ItemVariant.of(bag.getItem(slot)) : storage.getResource(); }
        @Override public long getAmount() { return bag.infinityKind() != null ? bag.isInfiniteSlot(slot) ? Long.MAX_VALUE : 0 : storage.getAmount(); }
        @Override public long getCapacity() { return bag.infinityKind() != null ? Long.MAX_VALUE : storage.getCapacity(); }
        @Override public long insert(ItemVariant resource, long maximum, TransactionContext transaction) {
            StoragePreconditions.notBlankNotNegative(resource, maximum);
            if (bag.infinityKind() == null) return storage.insert(resource, maximum, transaction);
            if (maximum == 0 || !bag.canPlaceItem(slot, resource.toStack())) return 0;
            int seeded = (int) Math.min(maximum, bag.capacity(resource.toStack()));
            updateSnapshots(transaction);
            bag.setItem(slot, resource.toStack(seeded));
            return seeded;
        }
        @Override public long extract(ItemVariant resource, long maximum, TransactionContext transaction) {
            StoragePreconditions.notBlankNotNegative(resource, maximum);
            if (!bag.canTakeItem(null, slot, resource.toStack())) return 0;
            if (bag.isInfiniteSlot(slot)) return resource.equals(getResource()) ? maximum : 0;
            return storage.extract(resource, maximum, transaction);
        }
        @Override protected ItemStack createSnapshot() { return bag.getItem(slot).copy(); }
        @Override protected void readSnapshot(ItemStack previous) { bag.restoreStorageSlot(slot, previous); }
        @Override protected void onFinalCommit() { bag.save(); }
    }
}
