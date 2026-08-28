package com.kadamitas.fabricatedbackpacks.resource;

import com.kadamitas.fabricatedbackpacks.config.BackpackConfig;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.gameplay.BackpackTraversal;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.storage.BagComponents;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.storage.InventorySnapshot;
import net.fabricmc.fabric.api.transfer.v1.context.ContainerItemContext;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StoragePreconditions;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import team.reborn.energy.api.EnergyStorage;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Item API writes must exchange the real context item, never just mutate the lookup copy. */
final class BackpackItemAccess {
    private BackpackItemAccess() {}

    static Storage<FluidVariant> fluids(ContainerItemContext context) { return new FluidAccess(context); }
    static EnergyStorage energy(ContainerItemContext context) { return new EnergyAccess(context); }

    private static BagInventory read(ContainerItemContext context) {
        ItemVariant variant = context.getItemVariant();
        if (variant.isBlank() || context.getMainSlot().getAmount() != 1) return null;
        var stack = variant.toStack();
        return BackpackRegistry.isBackpack(stack) ? BagInventory.of(stack) : null;
    }

    @FunctionalInterface
    private interface Mutation {
        long apply(BagInventory bag, TransactionContext transaction);
    }

    private static long mutate(ContainerItemContext context, TransactionContext transaction, Mutation mutation) {
        BagInventory bag = read(context);
        if (bag == null) return 0;
        try (Transaction nested = transaction.openNested()) {
            long moved = mutation.apply(bag, nested);
            if (moved == 0) return 0;
            bag.save();
            if (context.exchange(ItemVariant.of(bag.stack()), 1, nested) != 1) return 0;
            nested.commit();
            return moved;
        }
    }

    private record FluidAccess(ContainerItemContext context) implements Storage<FluidVariant> {
        @Override public long insert(FluidVariant fluid, long maximum, TransactionContext transaction) {
            StoragePreconditions.notBlankNotNegative(fluid, maximum);
            if (!BackpackConfig.get().storage().itemFluidAccess()) return 0;
            return mutate(context, transaction, (bag, nested) -> ResourceRuntime.fluidStorage(bag).insert(fluid, maximum, nested));
        }

        @Override public long extract(FluidVariant fluid, long maximum, TransactionContext transaction) {
            StoragePreconditions.notBlankNotNegative(fluid, maximum);
            if (!BackpackConfig.get().storage().itemFluidAccess()) return 0;
            return mutate(context, transaction, (bag, nested) -> ResourceRuntime.fluidStorage(bag).extract(fluid, maximum, nested));
        }

        @Override public Iterator<StorageView<FluidVariant>> iterator() {
            if (!BackpackConfig.get().storage().itemFluidAccess()) return List.<StorageView<FluidVariant>>of().iterator();
            BagInventory bag = read(context);
            List<StorageView<FluidVariant>> views = new ArrayList<>();
            if (bag != null) for (var node : BackpackTraversal.inventoryBags(bag)) {
                BagPath path = BagPath.capture(context.getItemVariant(), node.parentSlot());
                node.inventory().installedUpgrades().stream().filter(u -> u.kind() == UpgradeKind.TANK)
                        .forEach(u -> views.add(new TankView(context, path, u.slot())));
            }
            return views.iterator();
        }
    }

    /** Paths follow legitimate item-context exchanges, but not a different bag put in the same child slot. */
    private record BagPath(String rootIdentity, ItemVariant rootSeed, int parentSlot,
                           String childIdentity, ItemVariant childSeed) {
        static BagPath capture(ItemVariant root, int parentSlot) {
            var stack = root.toStack();
            var child = child(root, parentSlot);
            return new BagPath(stack.getOrDefault(BagComponents.IDENTITY, ""), root, parentSlot,
                    child.toStack().getOrDefault(BagComponents.IDENTITY, ""), child);
        }
        private static ItemVariant child(ItemVariant root, int slot) {
            if (slot < 0) return ItemVariant.blank();
            for (var entry : root.toStack().getOrDefault(BagComponents.CONTENTS, InventorySnapshot.EMPTY).entries())
                if (entry.slot() == slot && entry.count() == 1) return ItemVariant.of(entry.create());
            return ItemVariant.blank();
        }
        private static boolean matches(ItemVariant current, String identity, ItemVariant seed) {
            if (current.isBlank()) return false;
            String present = current.toStack().getOrDefault(BagComponents.IDENTITY, "");
            return identity.isEmpty() ? present.isEmpty() && current.equals(seed) : identity.equals(present);
        }
        BackpackTraversal.Node find(ContainerItemContext context, BagInventory root) {
            ItemVariant current = context.getItemVariant();
            if (!matches(current, rootIdentity, rootSeed)) return null;
            if (parentSlot < 0) return new BackpackTraversal.Node(root, null, -1);
            if (!matches(child(current, parentSlot), childIdentity, childSeed)) return null;
            return BackpackTraversal.inventoryBags(root).stream()
                    .filter(node -> node.parentSlot() == parentSlot && node.attached()).findFirst().orElse(null);
        }
    }

    private record TankView(ContainerItemContext context, BagPath path, int upgradeSlot) implements StorageView<FluidVariant> {
        private BackpackTank tank() {
            if (!BackpackConfig.get().storage().itemFluidAccess()) return null;
            BagInventory bag = read(context);
            BackpackTraversal.Node node = bag == null ? null : path.find(context, bag);
            return node == null ? null : ResourceRuntime.tank(node.inventory(), upgradeSlot, true);
        }

        @Override public boolean isResourceBlank() { return getResource().isBlank(); }
        @Override public FluidVariant getResource() {
            BackpackTank tank = tank();
            return tank == null ? FluidVariant.blank() : tank.getResource();
        }
        @Override public long getAmount() {
            BackpackTank tank = tank();
            return tank == null ? 0 : tank.getAmount();
        }
        @Override public long getCapacity() {
            BackpackTank tank = tank();
            return tank == null ? 0 : tank.getCapacity();
        }
        @Override public long extract(FluidVariant fluid, long maximum, TransactionContext transaction) {
            StoragePreconditions.notBlankNotNegative(fluid, maximum);
            if (!BackpackConfig.get().storage().itemFluidAccess()) return 0;
            return mutate(context, transaction, (bag, nested) -> {
                BackpackTraversal.Node node = path.find(context, bag);
                BackpackTank tank = node == null ? null : ResourceRuntime.tank(node.inventory(), upgradeSlot, true);
                long moved = tank == null ? 0 : tank.extract(fluid, maximum, nested);
                if (moved > 0) node.persist();
                return moved;
            });
        }
    }

    private record EnergyAccess(ContainerItemContext context) implements EnergyStorage {
        private EnergyStorage battery() {
            BagInventory bag = read(context);
            return bag == null ? null : ResourceRuntime.energyStorage(bag);
        }
        @Override public long getAmount() {
            EnergyStorage battery = battery();
            return battery == null ? 0 : battery.getAmount();
        }
        @Override public long getCapacity() {
            EnergyStorage battery = battery();
            return battery == null ? 0 : battery.getCapacity();
        }
        @Override public long insert(long maximum, TransactionContext transaction) {
            if (maximum < 0) throw new IllegalArgumentException("Negative energy insertion");
            return mutate(context, transaction, (bag, nested) -> {
                EnergyStorage battery = ResourceRuntime.energyStorage(bag);
                return battery == null ? 0 : battery.insert(maximum, nested);
            });
        }
        @Override public long extract(long maximum, TransactionContext transaction) {
            if (maximum < 0) throw new IllegalArgumentException("Negative energy extraction");
            return mutate(context, transaction, (bag, nested) -> {
                EnergyStorage battery = ResourceRuntime.energyStorage(bag);
                return battery == null ? 0 : battery.extract(maximum, nested);
            });
        }
    }
}
