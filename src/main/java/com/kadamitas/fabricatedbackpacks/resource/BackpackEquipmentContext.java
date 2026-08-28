package com.kadamitas.fabricatedbackpacks.resource;

import com.kadamitas.fabricatedbackpacks.config.BackpackConfig;
import com.kadamitas.fabricatedbackpacks.equipment.BackpackEquipment;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import net.fabricmc.fabric.api.transfer.v1.context.ContainerItemContext;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StoragePreconditions;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.minecraft.server.level.ServerPlayer;
import team.reborn.energy.api.EnergyStorage;
import java.util.List;
import java.util.Objects;

/**
 * Explicit server access to one native equipped backpack through the ordinary item API lookups.
 * The physical backpack cannot be inserted, extracted or replaced through this context. Its registered
 * item/fluid/energy APIs transact on the canonical inventory and publish attachment data only on commit.
 * Replacing or unequipping the backpack permanently invalidates this context and retained API handles.
 */
public final class BackpackEquipmentContext implements ContainerItemContext {
    private final ServerPlayer player;
    private final BagInventory bag;
    private final SingleSlotStorage<ItemVariant> slot = new SingleSlotStorage<>() {
        @Override public boolean supportsInsertion() { return false; }
        @Override public boolean supportsExtraction() { return false; }
        @Override public boolean isResourceBlank() { return !current(); }
        @Override public ItemVariant getResource() { return current() ? ItemVariant.of(bag.stack()) : ItemVariant.blank(); }
        @Override public long getAmount() { return current() ? 1 : 0; }
        @Override public long getCapacity() { return 1; }
        @Override public long insert(ItemVariant item, long maximum, TransactionContext transaction) {
            StoragePreconditions.notBlankNotNegative(item, maximum);
            return 0;
        }
        @Override public long extract(ItemVariant item, long maximum, TransactionContext transaction) {
            StoragePreconditions.notBlankNotNegative(item, maximum);
            return 0;
        }
    };

    BackpackEquipmentContext(ServerPlayer player) {
        this.player = Objects.requireNonNull(player);
        bag = BackpackEquipment.inventory(player).orElse(null);
    }

    private boolean current() {
        return bag != null && !player.isRemoved() && player.isAlive() && !player.isSpectator()
                && BackpackEquipment.isCurrent(player, bag);
    }

    private void committed() {
        if (current()) { bag.save(); BackpackEquipment.setFromInventory(player, bag); }
    }

    Storage<ItemVariant> items() {
        return bag == null ? Storage.empty() : TraversalResources.items(bag, null, this::current, this::committed);
    }
    Storage<FluidVariant> fluids() {
        return bag == null ? Storage.empty() : TraversalResources.fluids(bag, true,
                () -> current() && BackpackConfig.get().storage().itemFluidAccess(), this::committed);
    }
    EnergyStorage energy() {
        return bag == null ? EnergyStorage.EMPTY : TraversalResources.energy(bag, this::current, this::committed);
    }

    @Override public SingleSlotStorage<ItemVariant> getMainSlot() { return slot; }
    @Override public List<SingleSlotStorage<ItemVariant>> getAdditionalSlots() { return List.of(); }
    @Override public long insertOverflow(ItemVariant item, long maximum, TransactionContext transaction) {
        StoragePreconditions.notBlankNotNegative(item, maximum);
        return 0;
    }
}
