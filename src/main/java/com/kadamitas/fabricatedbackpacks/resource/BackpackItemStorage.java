package com.kadamitas.fabricatedbackpacks.resource;

import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.domain.StackCapacity;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage;
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

/** Native backpack limits and output policies exceed Fabric's vanilla inventory adapter contract. */
public final class BackpackItemStorage implements SlottedStorage<ItemVariant> {
    private final BagInventory bag;
    private final List<View> views;

    public BackpackItemStorage(BagInventory bag, Direction direction) {
        this.bag = bag;
        List<View> slots = new ArrayList<>();
        for (int slot = 0; slot < bag.getContainerSize(); slot++) slots.add(new View(slot));
        views = List.copyOf(slots);
    }

    @Override public long insert(ItemVariant resource, long maximum, TransactionContext transaction) {
        StoragePreconditions.notBlankNotNegative(resource, maximum);
        long inserted = 0;
        // Preserve native insertion preference: grow matching stacks before occupying empty slots.
        for (boolean empty : new boolean[]{false, true}) {
            for (View view : views) {
                if (view.isResourceBlank() != empty || !empty && !view.getResource().equals(resource)) continue;
                inserted += view.insert(resource, maximum - inserted, transaction);
                if (inserted == maximum) return inserted;
            }
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

    @Override public int getSlotCount() { return views.size(); }
    @Override public SingleSlotStorage<ItemVariant> getSlot(int slot) { return views.get(slot); }

    SingleSlotStorage<ItemVariant> slot(int slot) { return getSlot(slot); }

    private final class View extends SnapshotParticipant<ItemStack> implements SingleSlotStorage<ItemVariant> {
        private final int slot;
        View(int slot) { this.slot = slot; }
        @Override public boolean isResourceBlank() { return getResource().isBlank(); }
        @Override public ItemVariant getResource() { return ItemVariant.of(bag.getItem(slot)); }
        @Override public long getAmount() { return bag.isInfiniteSlot(slot) ? Long.MAX_VALUE : bag.getItem(slot).getCount(); }
        @Override public long getCapacity() {
            if (bag.blocked(slot)) return 0;
            if (bag.infinityKind() != null) return Long.MAX_VALUE;
            ItemStack current = bag.getItem(slot);
            return current.isEmpty() ? StackCapacity.itemLimit(64, bag.multiplier()) : bag.capacity(current);
        }
        @Override public long insert(ItemVariant resource, long maximum, TransactionContext transaction) {
            StoragePreconditions.notBlankNotNegative(resource, maximum);
            ItemStack template = resource.toStack();
            if (maximum == 0 || !bag.canPlaceItem(slot, template)) return 0;
            ItemStack current = bag.getItem(slot);
            if (!current.isEmpty() && !resource.matches(current)) return 0;
            int inserted = (int) Math.min(maximum, Math.max(0L, (long) bag.capacity(template) - current.getCount()));
            if (inserted == 0) return 0;
            updateSnapshots(transaction);
            bag.setItem(slot, resource.toStack(current.getCount() + inserted));
            return inserted;
        }
        @Override public long extract(ItemVariant resource, long maximum, TransactionContext transaction) {
            StoragePreconditions.notBlankNotNegative(resource, maximum);
            if (maximum == 0 || !bag.canTakeItem(null, slot, resource.toStack())) return 0;
            if (bag.isInfiniteSlot(slot)) return resource.equals(getResource()) ? maximum : 0;
            ItemStack current = bag.getItem(slot);
            if (current.isEmpty() || !resource.matches(current)) return 0;
            int extracted = (int) Math.min(maximum, current.getCount());
            updateSnapshots(transaction);
            bag.setItem(slot, current.copyWithCount(current.getCount() - extracted));
            return extracted;
        }
        @Override protected ItemStack createSnapshot() { return bag.getItem(slot).copy(); }
        @Override protected void readSnapshot(ItemStack previous) { bag.restoreStorageSlot(slot, previous); }
        @Override protected void onFinalCommit() { bag.save(); }
    }
}
