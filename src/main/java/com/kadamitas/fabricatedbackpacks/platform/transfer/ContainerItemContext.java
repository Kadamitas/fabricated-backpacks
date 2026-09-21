package com.kadamitas.fabricatedbackpacks.platform.transfer;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import com.kadamitas.fabricatedbackpacks.platform.transaction.Transaction;
import com.kadamitas.fabricatedbackpacks.platform.transaction.TransactionContext;

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
    static ContainerItemContext ofSingleSlot(SingleSlotStorage<ItemVariant> slot) { return ofSlots(slot, List.of()); }
    static ContainerItemContext ofSlots(SingleSlotStorage<ItemVariant> slot, List<SingleSlotStorage<ItemVariant>> additional) {
        return new ContainerItemContext() {
            @Override public SingleSlotStorage<ItemVariant> getMainSlot() { return slot; }
            @Override public List<SingleSlotStorage<ItemVariant>> getAdditionalSlots() { return additional; }
        };
    }
    static ContainerItemContext ofPlayerHand(Player player, InteractionHand hand) {
        int main = hand == InteractionHand.MAIN_HAND ? player.getInventory().getSelectedSlot() : net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND;
        var inventory = ContainerStorage.of(player.getInventory(), null);
        List<SingleSlotStorage<ItemVariant>> additional = new ArrayList<>();
        for (int slot = 0; slot < 36; slot++) if (slot != main) additional.add(inventory.getSlot(slot));
        return ofSlots(inventory.getSlot(main), List.copyOf(additional));
    }
    static ContainerItemContext ofPlayerCursor(Player player, AbstractContainerMenu menu) {
        return ofSlots(new StackSlot(menu::getCarried, menu::setCarried), ContainerStorage.of(player.getInventory(), null).getSlots());
    }
    static ContainerItemContext ofStack(ItemStack original) {
        ItemStack[] holder = { original };
        return ofSingleSlot(new StackSlot(() -> holder[0], value -> {
            holder[0] = value;
            if (value.getItem() == original.getItem()) { original.setCount(value.getCount()); original.applyComponents(value.getComponentsPatch()); }
        }));
    }
    static ContainerItemContext withConstant(ItemStack original) {
        ItemStack stack = original.copy();
        SingleSlotStorage<ItemVariant> slot = new SingleSlotStorage<>() {
            @Override public ItemVariant getResource() { return ItemVariant.of(stack); }
            @Override public boolean isResourceBlank() { return stack.isEmpty(); }
            @Override public long getAmount() { return stack.getCount(); }
            @Override public long getCapacity() { return Long.MAX_VALUE; }
            @Override public long insert(ItemVariant resource, long amount, TransactionContext tx) { StoragePreconditions.notBlankNotNegative(resource, amount); return 0; }
            @Override public long extract(ItemVariant resource, long amount, TransactionContext tx) { StoragePreconditions.notBlankNotNegative(resource, amount); return amount; }
        };
        // A constant context is a virtual probe: exchanges succeed without modifying its input.
        return new ContainerItemContext() {
            @Override public SingleSlotStorage<ItemVariant> getMainSlot() { return slot; }
            @Override public List<SingleSlotStorage<ItemVariant>> getAdditionalSlots() { return List.of(); }
            @Override public long insertOverflow(ItemVariant item, long maximum, TransactionContext tx) { StoragePreconditions.notBlankNotNegative(item, maximum); return maximum; }
        };
    }
}
