package com.kadamitas.fabricatedbackpacks.platform.transfer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.VanillaContainerWrapper;
import net.neoforged.neoforge.transfer.item.WorldlyContainerWrapper;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/**
 * Slotted transactional access to a container. Foreign containers go through NeoForge's own
 * wrappers; this mod's containers ({@link OwnedContainer}) use one shared participant per slot,
 * exactly like Fabric's inventory storage, so their reverts interleave correctly with every
 * other participant of this mod.
 */
public final class ContainerStorage {
    private static final Map<Container, Owned> OWNED = Collections.synchronizedMap(new WeakHashMap<>());

    private ContainerStorage() {}

    public static SlottedStorage<ItemVariant> of(Container container, Direction side) {
        if (container instanceof OwnedContainer) {
            Owned owned = OWNED.computeIfAbsent(container, Owned::new);
            owned.resize();
            return side != null && container instanceof WorldlyContainer worldly ? owned.sided(worldly, side) : owned;
        }
        ResourceHandler<ItemResource> handler = side != null && container instanceof WorldlyContainer worldly
                ? new WorldlyContainerWrapper(worldly, side) : VanillaContainerWrapper.of(container);
        return new NativeStorage<>(handler, ItemVariant::new, ItemVariant::nativeResource, 1);
    }

    private static final class Owned implements SlottedStorage<ItemVariant> {
        private final Container container;
        private final List<Slot> slots = new ArrayList<>();
        private List<SingleSlotStorage<ItemVariant>> exposed = List.of();
        private final SnapshotParticipant<Boolean> dirty = new SnapshotParticipant<>() {
            @Override protected Boolean createSnapshot() { return Boolean.TRUE; }
            @Override protected void readSnapshot(Boolean snapshot) {}
            @Override protected void onFinalCommit() { container.setChanged(); }
        };

        Owned(Container container) { this.container = container; }

        void resize() {
            int size = container.getContainerSize();
            if (size == exposed.size()) return;
            while (slots.size() < size) slots.add(new Slot(slots.size()));
            exposed = Collections.unmodifiableList(new ArrayList<>(slots.subList(0, size)));
        }

        SlottedStorage<ItemVariant> sided(WorldlyContainer worldly, Direction side) {
            int[] faces = worldly.getSlotsForFace(side);
            List<SingleSlotStorage<ItemVariant>> views = new ArrayList<>(faces.length);
            for (int face : faces) views.add(new SidedSlot(slots.get(face), worldly, side));
            List<SingleSlotStorage<ItemVariant>> sided = Collections.unmodifiableList(views);
            return new SlottedStorage<>() {
                @Override public int getSlotCount() { return sided.size(); }
                @Override public SingleSlotStorage<ItemVariant> getSlot(int slot) { return sided.get(slot); }
                @Override public List<SingleSlotStorage<ItemVariant>> getSlots() { return sided; }
                @Override public long insert(ItemVariant resource, long maximum, TransactionContext tx) { return insertInto(sided, resource, maximum, tx); }
                @Override public long extract(ItemVariant resource, long maximum, TransactionContext tx) { return extractFrom(sided, resource, maximum, tx); }
                @Override public Iterator<StorageView<ItemVariant>> iterator() { return sided.stream().map(slot -> (StorageView<ItemVariant>) slot).iterator(); }
            };
        }

        private static long insertInto(List<SingleSlotStorage<ItemVariant>> views, ItemVariant resource, long maximum, TransactionContext tx) {
            StoragePreconditions.notBlankNotNegative(resource, maximum);
            long moved = 0;
            for (var view : views) { moved += view.insert(resource, maximum - moved, tx); if (moved == maximum) break; }
            return moved;
        }

        private static long extractFrom(List<SingleSlotStorage<ItemVariant>> views, ItemVariant resource, long maximum, TransactionContext tx) {
            StoragePreconditions.notBlankNotNegative(resource, maximum);
            long moved = 0;
            for (var view : views) { moved += view.extract(resource, maximum - moved, tx); if (moved == maximum) break; }
            return moved;
        }

        @Override public int getSlotCount() { return exposed.size(); }
        @Override public SingleSlotStorage<ItemVariant> getSlot(int slot) { return exposed.get(slot); }
        @Override public List<SingleSlotStorage<ItemVariant>> getSlots() { return exposed; }
        @Override public long insert(ItemVariant resource, long maximum, TransactionContext tx) { return insertInto(exposed, resource, maximum, tx); }
        @Override public long extract(ItemVariant resource, long maximum, TransactionContext tx) { return extractFrom(exposed, resource, maximum, tx); }
        @Override public Iterator<StorageView<ItemVariant>> iterator() { return exposed.stream().map(slot -> (StorageView<ItemVariant>) slot).iterator(); }

        /** One journaled stack slot; mutations write through {@link Container#setItem} exactly as direct edits do. */
        private final class Slot extends SnapshotParticipant<ItemStack> implements SingleSlotStorage<ItemVariant> {
            private final int index;
            Slot(int index) { this.index = index; }
            private ItemStack stack() { return container.getItem(index); }
            private void write(ItemStack stack) { container.setItem(index, stack); }
            private int capacity(ItemVariant resource) {
                return resource.isBlank() ? container.getMaxStackSize() : container.getMaxStackSize(resource.toStack());
            }
            @Override public ItemVariant getResource() { return ItemVariant.of(stack()); }
            @Override public boolean isResourceBlank() { return stack().isEmpty(); }
            @Override public long getAmount() { return stack().getCount(); }
            @Override public long getCapacity() { return capacity(getResource()); }
            @Override public long insert(ItemVariant resource, long maximum, TransactionContext tx) {
                StoragePreconditions.notBlankNotNegative(resource, maximum);
                ItemStack current = stack();
                if (!(current.isEmpty() || resource.matches(current)) || !container.canPlaceItem(index, resource.toStack())) return 0;
                int inserted = (int) Math.min(maximum, capacity(resource) - current.getCount());
                if (inserted <= 0) return 0;
                journal(tx);
                current = stack();
                if (current.isEmpty()) current = resource.toStack(inserted);
                else current.grow(inserted);
                write(current);
                return inserted;
            }
            @Override public long extract(ItemVariant resource, long maximum, TransactionContext tx) {
                StoragePreconditions.notBlankNotNegative(resource, maximum);
                ItemStack current = stack();
                if (!resource.matches(current)) return 0;
                int extracted = (int) Math.min(current.getCount(), maximum);
                if (extracted <= 0) return 0;
                journal(tx);
                current = stack();
                current.shrink(extracted);
                write(current);
                return extracted;
            }
            private void journal(TransactionContext tx) {
                dirty.updateSnapshots(tx);
                updateSnapshots(tx);
            }
            @Override protected ItemStack createSnapshot() {
                ItemStack original = stack();
                write(original.copy());
                return original;
            }
            @Override protected void readSnapshot(ItemStack snapshot) { write(snapshot); }
        }

        /** A face-filtered view over a shared slot participant. */
        private record SidedSlot(Slot slot, WorldlyContainer worldly, Direction side) implements SingleSlotStorage<ItemVariant> {
            @Override public ItemVariant getResource() { return slot.getResource(); }
            @Override public boolean isResourceBlank() { return slot.isResourceBlank(); }
            @Override public long getAmount() { return slot.getAmount(); }
            @Override public long getCapacity() { return slot.getCapacity(); }
            @Override public long insert(ItemVariant resource, long maximum, TransactionContext tx) {
                StoragePreconditions.notBlankNotNegative(resource, maximum);
                return worldly.canPlaceItemThroughFace(slot.index, resource.toStack(), side) ? slot.insert(resource, maximum, tx) : 0;
            }
            @Override public long extract(ItemVariant resource, long maximum, TransactionContext tx) {
                StoragePreconditions.notBlankNotNegative(resource, maximum);
                return worldly.canTakeItemThroughFace(slot.index, resource.toStack(), side) ? slot.extract(resource, maximum, tx) : 0;
            }
        }
    }
}
