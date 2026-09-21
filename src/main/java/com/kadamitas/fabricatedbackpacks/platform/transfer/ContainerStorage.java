package com.kadamitas.fabricatedbackpacks.platform.transfer;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.stream.IntStream;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.item.ItemStack;
import com.kadamitas.fabricatedbackpacks.platform.transaction.TransactionContext;

/** Every sided view shares one participant per physical slot, preserving interleaved child rollback. */
public final class ContainerStorage implements SlottedStorage<ItemVariant> {
    private static final Map<Container, WeakReference<Journal>> JOURNALS = new WeakHashMap<>();
    private final Journal journal;
    private final Direction side;
    private final List<SingleSlotStorage<ItemVariant>> slots;

    private ContainerStorage(Journal journal, Direction side) {
        this.journal = journal;
        this.side = side;
        int[] indices = side != null && journal.container instanceof WorldlyContainer worldly
                ? worldly.getSlotsForFace(side)
                : IntStream.range(0, journal.container.getContainerSize()).toArray();
        slots = IntStream.of(indices).mapToObj(index -> (SingleSlotStorage<ItemVariant>) new Slot(index)).toList();
    }
    public static ContainerStorage of(Container container, Direction side) {
        Journal journal;
        synchronized (JOURNALS) {
            WeakReference<Journal> reference = JOURNALS.get(container);
            journal = reference == null ? null : reference.get();
            if (journal == null) { journal = new Journal(container); JOURNALS.put(container, new WeakReference<>(journal)); }
        }
        return new ContainerStorage(journal, side);
    }
    @Override public int getSlotCount() { return slots.size(); }
    @Override public SingleSlotStorage<ItemVariant> getSlot(int slot) { return slots.get(slot); }
    @Override public Iterator<StorageView<ItemVariant>> iterator() { return slots.stream().map(slot -> (StorageView<ItemVariant>)slot).iterator(); }
    @Override public long insert(ItemVariant resource, long maximum, TransactionContext transaction) {
        StoragePreconditions.notBlankNotNegative(resource, maximum);
        long moved = 0;
        // Fill existing stacks before allocating empty slots.
        for (boolean blank : new boolean[]{false, true})
            for (var slot : slots) if (slot.isResourceBlank() == blank && moved < maximum)
                moved += slot.insert(resource, maximum - moved, transaction);
        return moved;
    }
    @Override public long extract(ItemVariant resource, long maximum, TransactionContext transaction) {
        StoragePreconditions.notBlankNotNegative(resource, maximum);
        long moved = 0;
        for (var slot : slots) if (moved < maximum) moved += slot.extract(resource, maximum - moved, transaction);
        return moved;
    }
    private static final class Journal {
        private final Container container;
        private final Map<Integer, SlotJournal> slots = new HashMap<>();
        private final SnapshotParticipant<Boolean> dirty = new SnapshotParticipant<>() {
            @Override protected Boolean createSnapshot() { return true; }
            @Override protected void readSnapshot(Boolean ignored) {}
            @Override protected void onFinalCommit() { container.setChanged(); }
        };
        Journal(Container container) { this.container = container; }
        void capture(int index, TransactionContext transaction) {
            dirty.updateSnapshots(transaction);
            slots.computeIfAbsent(index, SlotJournal::new).updateSnapshots(transaction);
        }
        private final class SlotJournal extends com.kadamitas.fabricatedbackpacks.platform.transaction.SnapshotJournal<ItemStack> {
            private final int index;
            SlotJournal(int index) { this.index = index; }
            @Override protected ItemStack createSnapshot() {
                // Keep the original identity for rollback, and detach the working stack. Do not
                // snapshot unrelated child slots after another participant has mutated them.
                ItemStack original = container.getItem(index);
                container.setItem(index, original.copy());
                return original;
            }
            @Override protected void revertToSnapshot(ItemStack snapshot) { container.setItem(index, snapshot); }
            @Override protected void onRootCommit(ItemStack original) {
                container.setItem(index, StackSlot.preserveIdentity(original, container.getItem(index)));
            }
        }
    }
    private final class Slot implements SingleSlotStorage<ItemVariant> {
        private final int index;
        Slot(int index) { this.index = index; }
        private ItemStack stack() { return journal.container.getItem(index); }
        @Override public ItemVariant getResource() { return ItemVariant.of(stack()); }
        @Override public boolean isResourceBlank() { return stack().isEmpty(); }
        @Override public long getAmount() { return stack().getCount(); }
        @Override public long getCapacity() { return stack().isEmpty() ? journal.container.getMaxStackSize() : journal.container.getMaxStackSize(stack()); }
        @Override public long insert(ItemVariant resource, long maximum, TransactionContext transaction) {
            StoragePreconditions.notBlankNotNegative(resource, maximum);
            ItemStack current = stack(), proposed = resource.toStack();
            if ((!current.isEmpty() && !resource.matches(current)) || !journal.container.canPlaceItem(index, proposed)
                    || side != null && journal.container instanceof WorldlyContainer worldly && !worldly.canPlaceItemThroughFace(index, proposed, side)) return 0;
            int amount = (int)Math.min(maximum, Math.max(0, journal.container.getMaxStackSize(proposed) - current.getCount()));
            if (amount > 0) { journal.capture(index, transaction); journal.container.setItem(index, resource.toStack(current.getCount() + amount)); }
            return amount;
        }
        @Override public long extract(ItemVariant resource, long maximum, TransactionContext transaction) {
            StoragePreconditions.notBlankNotNegative(resource, maximum);
            ItemStack current = stack();
            if (!resource.matches(current) || side != null && journal.container instanceof WorldlyContainer worldly
                    && !worldly.canTakeItemThroughFace(index, current, side)) return 0;
            int amount = (int)Math.min(maximum, current.getCount());
            if (amount > 0) { journal.capture(index, transaction); journal.container.setItem(index, current.copyWithCount(current.getCount() - amount)); }
            return amount;
        }
    }
}
