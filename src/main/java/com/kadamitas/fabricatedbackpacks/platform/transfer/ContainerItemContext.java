package com.kadamitas.fabricatedbackpacks.platform.transfer;

import java.util.List;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.access.ItemAccess;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

public interface ContainerItemContext {
    SingleSlotStorage<ItemVariant> getMainSlot();
    List<SingleSlotStorage<ItemVariant>> getAdditionalSlots();
    default ItemVariant getItemVariant() { return getMainSlot().getResource(); }
    default long insertOverflow(ItemVariant item, long maximum, TransactionContext tx) {
        long moved = 0;
        for (var slot : getAdditionalSlots()) { moved += slot.insert(item, maximum - moved, tx); if (moved == maximum) break; }
        return moved;
    }
    default long insert(ItemVariant item, long maximum, TransactionContext tx) {
        long main = getMainSlot().insert(item, maximum, tx);
        return main + insertOverflow(item, maximum - main, tx);
    }
    default long exchange(ItemVariant replacement, long amount, TransactionContext parent) {
        try (var tx = Transaction.open(parent)) {
            long removed = getMainSlot().extract(getItemVariant(), amount, tx);
            if (removed == 0 || insert(replacement, removed, tx) != removed) return 0;
            tx.commit(); return removed;
        }
    }
    default <T> T find(ItemLookup<T, ?> lookup) { return lookup.find(getItemVariant().toStack(), this); }
    /** Marks a native access that only mirrors one of this mod's own contexts. */
    interface OriginAccess extends ItemAccess { ContainerItemContext origin(); }
    default ItemAccess nativeAccess() {
        var context = this;
        return new OriginAccess() {
            @Override public ContainerItemContext origin() { return context; }
            @Override public ItemResource getResource() { return context.getItemVariant().nativeResource(); }
            @Override public int getAmount() { return (int) Math.min(Integer.MAX_VALUE, context.getMainSlot().getAmount()); }
            @Override public int insert(ItemResource resource, int amount, TransactionContext tx) { return Math.toIntExact(context.insert(new ItemVariant(resource), amount, tx)); }
            @Override public int extract(ItemResource resource, int amount, TransactionContext tx) { return Math.toIntExact(context.getMainSlot().extract(new ItemVariant(resource), amount, tx)); }
        };
    }
    static ContainerItemContext fromNative(ItemAccess access) {
        if (access instanceof OriginAccess mirrored) return mirrored.origin();
        return new ContainerItemContext() {
            private final SingleSlotStorage<ItemVariant> slot = new SingleSlotStorage<>() {
                @Override public ItemVariant getResource() { return new ItemVariant(access.getResource()); }
                @Override public boolean isResourceBlank() { return access.getResource().isEmpty(); }
                @Override public long getAmount() { return access.getAmount(); }
                @Override public long getCapacity() { return Integer.MAX_VALUE; }
                @Override public long insert(ItemVariant resource, long amount, TransactionContext tx) { StoragePreconditions.notBlankNotNegative(resource, amount); return access.insert(resource.nativeResource(), (int) Math.min(Integer.MAX_VALUE, amount), tx); }
                @Override public long extract(ItemVariant resource, long amount, TransactionContext tx) { StoragePreconditions.notBlankNotNegative(resource, amount); return access.extract(resource.nativeResource(), (int) Math.min(Integer.MAX_VALUE, amount), tx); }
            };
            @Override public SingleSlotStorage<ItemVariant> getMainSlot() { return slot; }
            @Override public List<SingleSlotStorage<ItemVariant>> getAdditionalSlots() { return List.of(); }
            @Override public ItemAccess nativeAccess() { return access; }
        };
    }
    static ContainerItemContext ofSingleSlot(SingleSlotStorage<ItemVariant> slot) {
        return new ContainerItemContext() {
            @Override public SingleSlotStorage<ItemVariant> getMainSlot() { return slot; }
            @Override public List<SingleSlotStorage<ItemVariant>> getAdditionalSlots() { return List.of(); }
        };
    }
    static ContainerItemContext ofPlayerHand(Player player, InteractionHand hand) { return fromNative(ItemAccess.forPlayerInteraction(player, hand)); }
    static ContainerItemContext ofPlayerCursor(Player player, AbstractContainerMenu menu) { return fromNative(ItemAccess.forPlayerCursor(player, menu)); }
    /** A context whose content never changes: extraction and overflow insertion succeed virtually, as with Fabric. */
    static ContainerItemContext withConstant(ItemStack original) {
        ItemStack stack = original.copy();
        SingleSlotStorage<ItemVariant> slot = new SingleSlotStorage<>() {
            @Override public ItemVariant getResource() { return ItemVariant.of(stack); }
            @Override public boolean isResourceBlank() { return stack.isEmpty(); }
            @Override public long getAmount() { return stack.getCount(); }
            @Override public long getCapacity() { return Long.MAX_VALUE; }
            // Route every insertion through insertOverflow; pretend any extraction succeeds without doing it.
            @Override public long insert(ItemVariant resource, long amount, TransactionContext tx) { StoragePreconditions.notBlankNotNegative(resource, amount); return 0; }
            @Override public long extract(ItemVariant resource, long amount, TransactionContext tx) { StoragePreconditions.notBlankNotNegative(resource, amount); return amount; }
        };
        return new ContainerItemContext() {
            @Override public SingleSlotStorage<ItemVariant> getMainSlot() { return slot; }
            @Override public List<SingleSlotStorage<ItemVariant>> getAdditionalSlots() { return List.of(); }
            @Override public long insertOverflow(ItemVariant item, long maximum, TransactionContext tx) { StoragePreconditions.notBlankNotNegative(item, maximum); return maximum; }
        };
    }
}
