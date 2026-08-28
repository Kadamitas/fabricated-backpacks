package com.kadamitas.fabricatedbackpacks.resource;

import com.kadamitas.fabricatedbackpacks.config.BackpackConfig;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.storage.InstalledUpgrade;
import net.fabricmc.fabric.api.transfer.v1.context.ContainerItemContext;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.ContainerStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageUtil;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import team.reborn.energy.api.EnergyStorage;
import java.util.List;

final class ResourceContainers {
    private ResourceContainers() {}

    static void tank(BagInventory bag, InstalledUpgrade upgrade) {
        ContainerStorage inventory = ContainerStorage.of(bag.upgradeInventory(upgrade), null);
        Storage<FluidVariant> tank = ResourceRuntime.tankStorage(bag, upgrade.slot(), false);
        ContainerItemContext drain = outputContext(inventory.getSlot(0), inventory.getSlot(2));
        ContainerItemContext fill = outputContext(inventory.getSlot(1), inventory.getSlot(3));
        if (!PumpRuntime.sameBag(bag, drain.getItemVariant().toStack())) moveFluid(drain.find(FluidStorage.ITEM), tank);
        if (!PumpRuntime.sameBag(bag, fill.getItemVariant().toStack())) moveFluid(tank, fill.find(FluidStorage.ITEM));
    }

    static void battery(BagInventory bag, InstalledUpgrade upgrade) {
        ContainerStorage inventory = ContainerStorage.of(bag.upgradeInventory(upgrade), null);
        BackpackBattery battery = new BackpackBattery(bag, upgrade);
        long rate = BackpackConfig.get().upgrades().battery().transfer(bag.rows(), bag.multiplier());
        ContainerItemContext discharge = ContainerItemContext.ofSingleSlot(inventory.getSlot(0));
        ContainerItemContext charge = ContainerItemContext.ofSingleSlot(inventory.getSlot(1));
        if (inventory.getSlot(0).getAmount() == 1 && !PumpRuntime.sameBag(bag, discharge.getItemVariant().toStack()))
            moveEnergy(discharge.find(EnergyStorage.ITEM), battery, rate);
        if (inventory.getSlot(1).getAmount() == 1 && !PumpRuntime.sameBag(bag, charge.getItemVariant().toStack()))
            moveEnergy(battery, charge.find(EnergyStorage.ITEM), rate);
    }

    static void exchangeCursor(Storage<FluidVariant> tank, ContainerItemContext context) {
        Storage<FluidVariant> item = context.find(FluidStorage.ITEM);
        if (moveFluid(tank, item) == 0) moveFluid(item, tank);
    }

    static void exchangeEnergy(EnergyStorage battery, ContainerItemContext context) {
        if (context.getMainSlot().getAmount() != 1) return;
        EnergyStorage item = context.find(EnergyStorage.ITEM);
        if (moveEnergy(item, battery, Long.MAX_VALUE) == 0) moveEnergy(battery, item, Long.MAX_VALUE);
    }

    static long moveFluid(Storage<FluidVariant> from, Storage<FluidVariant> to) {
        if (from == null || to == null || from == to) return 0;
        try (Transaction transaction = Transaction.openOuter()) {
            long moved = StorageUtil.move(from, to, fluid -> true, FluidConstants.BUCKET, transaction);
            if (moved > 0) transaction.commit();
            return moved;
        }
    }

    static long moveEnergy(EnergyStorage from, EnergyStorage to, long maximum) {
        if (maximum < 0) throw new IllegalArgumentException("Negative energy transfer");
        if (from == null || to == null || from == to || maximum == 0) return 0;
        try (Transaction transaction = Transaction.openOuter()) {
            long available;
            try (Transaction simulation = transaction.openNested()) {
                available = from.extract(maximum, simulation);
            }
            long inserted = to.insert(available, transaction);
            if (inserted <= 0 || from.extract(inserted, transaction) != inserted) return 0;
            transaction.commit();
            return inserted;
        }
    }

    private static ContainerItemContext outputContext(SingleSlotStorage<ItemVariant> input,
                                                       SingleSlotStorage<ItemVariant> output) {
        return new ContainerItemContext() {
            @Override public SingleSlotStorage<ItemVariant> getMainSlot() { return input; }
            @Override public long insert(ItemVariant variant, long maximum, TransactionContext transaction) {
                return output.insert(variant, maximum, transaction);
            }
            @Override public long insertOverflow(ItemVariant variant, long maximum, TransactionContext transaction) {
                return output.insert(variant, maximum, transaction);
            }
            @Override public List<SingleSlotStorage<ItemVariant>> getAdditionalSlots() { return List.of(output); }
        };
    }
}
