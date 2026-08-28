package com.kadamitas.fabricatedbackpacks.resource;

import com.kadamitas.fabricatedbackpacks.config.BackpackConfig;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.gameplay.BackpackTraversal;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.storage.BagComponents;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.storage.InventorySnapshot;
import com.kadamitas.fabricatedbackpacks.upgrade.UpgradeEngine;
import net.fabricmc.fabric.api.transfer.v1.context.ContainerItemContext;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StoragePreconditions;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;
import team.reborn.energy.api.EnergyStorage;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.HashSet;
import java.util.Set;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;

/** Item API writes must exchange the real context item, never just mutate the lookup copy. */
final class BackpackItemAccess {
    // Values contain no context or view objects, so they cannot keep their weak context keys alive.
    private static final Map<ContainerItemContext, Binding> BINDINGS = new WeakHashMap<>();
    private BackpackItemAccess() {}

    private static synchronized Binding binding(ContainerItemContext context) {
        Binding existing = BINDINGS.get(context);
        if (existing == null || !existing.matches(context)) {
            existing = new Binding(context);
            BINDINGS.put(context, existing);
        }
        return existing;
    }

    static Storage<ItemVariant> items(ContainerItemContext context) {
        return context instanceof BackpackEquipmentContext equipped ? equipped.items() : new ItemAccess(context, binding(context));
    }
    static Storage<FluidVariant> fluids(ContainerItemContext context) {
        return context instanceof BackpackEquipmentContext equipped ? equipped.fluids() : new FluidAccess(context, binding(context));
    }
    static EnergyStorage energy(ContainerItemContext context) {
        return context instanceof BackpackEquipmentContext equipped ? equipped.energy() : new EnergyAccess(context, binding(context));
    }

    /** Follow exchanges of this bag, not an unrelated backpack later put into the same context slot. */
    private static final class Binding extends SnapshotParticipant<String> {
        private final ItemVariant initial;
        private String identity;
        private ItemVariant cachedVariant;
        private BagInventory cachedBag;
        private final Map<BagInventory, BackpackItemStorage> cachedItems = new IdentityHashMap<>();
        private final ReferenceQueue<BagPath> expiredPaths = new ReferenceQueue<>();
        private final Set<WeakReference<BagPath>> paths = new HashSet<>();
        Binding(ContainerItemContext context) {
            initial = context.getItemVariant();
            identity = initial.toStack().getOrDefault(BagComponents.IDENTITY, "");
        }
        boolean matches(ContainerItemContext context) {
            ItemVariant current = context.getItemVariant();
            if (current.isBlank()) return false;
            String present = current.toStack().getOrDefault(BagComponents.IDENTITY, "");
            return identity.isEmpty() ? present.isEmpty() && initial.equals(current) : identity.equals(present);
        }
        BagInventory read(ContainerItemContext context) {
            if (!matches(context) || context.getMainSlot().getAmount() != 1) return null;
            ItemVariant variant = context.getItemVariant();
            if (!variant.equals(cachedVariant)) {
                var stack = variant.toStack();
                if (!BackpackRegistry.isBackpack(stack)) return null;
                cachedBag = BagInventory.of(stack);
                BackpackTraversal.inventoryBags(cachedBag);
                cachedItems.clear();
                cachedVariant = variant;
            }
            return cachedBag;
        }
        StorageView<ItemVariant> slot(BagInventory bag, int slot) {
            return cachedItems.computeIfAbsent(bag, value -> new BackpackItemStorage(value, null)).slot(slot);
        }
        BagPath path(ItemVariant root, int parentSlot) {
            drainPaths();
            BagPath path = new BagPath(root, parentSlot);
            paths.add(new WeakReference<>(path, expiredPaths));
            return path;
        }
        private void drainPaths() {
            Reference<? extends BagPath> expired;
            while ((expired = expiredPaths.poll()) != null) paths.remove(expired);
        }
        void exchanged(ItemVariant before, ItemVariant initialized, ItemVariant replacement, TransactionContext transaction) {
            String next = replacement.toStack().getOrDefault(BagComponents.IDENTITY, "");
            if (!identity.equals(next)) { updateSnapshots(transaction); identity = next; }
            drainPaths();
            for (var reference : paths) {
                BagPath path = reference.get();
                if (path != null) path.exchanged(before, initialized, replacement, transaction);
            }
        }
        @Override protected String createSnapshot() { return identity; }
        @Override protected void readSnapshot(String previous) { identity = previous; }
    }

    private static BagInventory read(ContainerItemContext context, Binding binding) {
        return binding.read(context);
    }

    @FunctionalInterface
    private interface Mutation {
        long apply(BagInventory bag, TransactionContext transaction);
    }

    private static long mutate(ContainerItemContext context, Binding binding, TransactionContext transaction, Mutation mutation) {
        BagInventory cached = read(context, binding);
        if (cached == null) return 0;
        ItemVariant before = context.getItemVariant();
        BagInventory bag = BagInventory.of(cached.stack().copy());
        // The read cache is never the mutation target. This also identifies UUIDs initialized in
        // the detached copy, so retained child paths cannot accidentally adopt a replacement child.
        ItemVariant initialized = ItemVariant.of(bag.stack());
        try (Transaction nested = transaction.openNested()) {
            long moved = mutation.apply(bag, nested);
            if (moved == 0) return 0;
            bag.save();
            ItemVariant replacement = ItemVariant.of(bag.stack());
            if (context.exchange(replacement, 1, nested) != 1) return 0;
            // Fabric's constant/creative contexts may accept an exchange without replacing their
            // fixed main variant. Only a real main-slot exchange advances that slot's identity.
            if (context.getItemVariant().equals(replacement)) binding.exchanged(before, initialized, replacement, nested);
            nested.commit();
            return moved;
        }
    }

    private record ItemAccess(ContainerItemContext context, Binding binding) implements Storage<ItemVariant> {
        @Override public long insert(ItemVariant item, long maximum, TransactionContext transaction) {
            StoragePreconditions.notBlankNotNegative(item, maximum);
            return mutate(context, binding, transaction,
                    (bag, nested) -> ResourceRuntime.itemStorage(bag, null).insert(item, maximum, nested));
        }
        @Override public long extract(ItemVariant item, long maximum, TransactionContext transaction) {
            StoragePreconditions.notBlankNotNegative(item, maximum);
            return mutate(context, binding, transaction,
                    (bag, nested) -> ResourceRuntime.itemStorage(bag, null).extract(item, maximum, nested));
        }
        @Override public Iterator<StorageView<ItemVariant>> iterator() {
            BagInventory bag = read(context, binding);
            List<StorageView<ItemVariant>> views = new ArrayList<>();
            if (bag != null) for (var node : BackpackTraversal.inventoryBags(bag)) {
                BagPath path = binding.path(context.getItemVariant(), node.parentSlot());
                for (int slot = 0; slot < node.inventory().getContainerSize(); slot++)
                    views.add(new ItemView(context, binding, path, slot));
            }
            return views.iterator();
        }
    }

    /** A physical cell address survives ordering changes without exposing a nested carrier itself. */
    private record ItemView(ContainerItemContext context, Binding binding, BagPath path, int slot) implements StorageView<ItemVariant> {
        private boolean visible(BagInventory root, BackpackTraversal.Node node) {
            return node != null && slot >= 0 && slot < node.inventory().getContainerSize()
                    && !(BackpackTraversal.usesChildren(root) && BackpackRegistry.isBackpack(node.inventory().getItem(slot)));
        }
        private StorageView<ItemVariant> leaf(BagInventory root, BackpackTraversal.Node node) {
            if (node == null || slot < 0 || slot >= node.inventory().getContainerSize()
                    || BackpackTraversal.usesChildren(root) && BackpackRegistry.isBackpack(node.inventory().getItem(slot))) return null;
            return new BackpackItemStorage(node.inventory(), null).slot(slot);
        }
        private StorageView<ItemVariant> view() {
            BagInventory bag = read(context, binding);
            var node = bag == null ? null : path.find(context, bag);
            return visible(bag, node) ? binding.slot(node.inventory(), slot) : null;
        }
        @Override public boolean isResourceBlank() { return getResource().isBlank(); }
        @Override public ItemVariant getResource() { var view = view(); return view == null ? ItemVariant.blank() : view.getResource(); }
        @Override public long getAmount() { var view = view(); return view == null ? 0 : view.getAmount(); }
        @Override public long getCapacity() { var view = view(); return view == null ? 0 : view.getCapacity(); }
        @Override public long extract(ItemVariant item, long maximum, TransactionContext transaction) {
            StoragePreconditions.notBlankNotNegative(item, maximum);
            return mutate(context, binding, transaction, (bag, nested) -> {
                if (!UpgradeEngine.acceptsOutput(bag, item.toStack())) return 0;
                var node = path.find(context, bag);
                var view = leaf(bag, node);
                long extracted = view == null ? 0 : view.extract(item, maximum, nested);
                if (extracted > 0) node.persist();
                return extracted;
            });
        }
    }

    private record FluidAccess(ContainerItemContext context, Binding binding) implements Storage<FluidVariant> {
        private Storage<FluidVariant> current() {
            if (!BackpackConfig.get().storage().itemFluidAccess()) return null;
            BagInventory bag = read(context, binding);
            return bag == null ? null : ResourceRuntime.fluidStorage(bag);
        }

        @Override public boolean supportsInsertion() {
            Storage<FluidVariant> storage = current();
            return storage != null && storage.supportsInsertion();
        }

        @Override public boolean supportsExtraction() {
            Storage<FluidVariant> storage = current();
            return storage != null && storage.supportsExtraction();
        }

        @Override public long insert(FluidVariant fluid, long maximum, TransactionContext transaction) {
            StoragePreconditions.notBlankNotNegative(fluid, maximum);
            if (!BackpackConfig.get().storage().itemFluidAccess()) return 0;
            return mutate(context, binding, transaction, (bag, nested) -> ResourceRuntime.fluidStorage(bag).insert(fluid, maximum, nested));
        }

        @Override public long extract(FluidVariant fluid, long maximum, TransactionContext transaction) {
            StoragePreconditions.notBlankNotNegative(fluid, maximum);
            if (!BackpackConfig.get().storage().itemFluidAccess()) return 0;
            return mutate(context, binding, transaction, (bag, nested) -> ResourceRuntime.fluidStorage(bag).extract(fluid, maximum, nested));
        }

        @Override public Iterator<StorageView<FluidVariant>> iterator() {
            if (!BackpackConfig.get().storage().itemFluidAccess()) return List.<StorageView<FluidVariant>>of().iterator();
            BagInventory bag = read(context, binding);
            List<StorageView<FluidVariant>> views = new ArrayList<>();
            if (bag != null) for (var node : BackpackTraversal.inventoryBags(bag)) {
                BagPath path = binding.path(context.getItemVariant(), node.parentSlot());
                node.inventory().installedUpgrades().stream().filter(u -> u.kind() == UpgradeKind.TANK)
                        .forEach(u -> views.add(new TankView(context, binding, path, u.slot())));
            }
            return views.iterator();
        }
    }

    /** Paths follow legitimate item-context exchanges, but not a different bag put in the same child slot. */
    private static final class BagPath extends SnapshotParticipant<String> {
        private final int parentSlot;
        private final ItemVariant childSeed;
        private String childIdentity;
        BagPath(ItemVariant root, int parentSlot) {
            this.parentSlot = parentSlot;
            var child = child(root, parentSlot);
            childIdentity = child.toStack().getOrDefault(BagComponents.IDENTITY, "");
            childSeed = child;
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
            if (parentSlot < 0) return new BackpackTraversal.Node(root, null, -1);
            if (!matches(child(current, parentSlot), childIdentity, childSeed)) return null;
            return BackpackTraversal.inventoryBags(root).stream()
                    .filter(node -> node.parentSlot() == parentSlot && node.attached()).findFirst().orElse(null);
        }
        void exchanged(ItemVariant before, ItemVariant initialized, ItemVariant replacement, TransactionContext transaction) {
            if (parentSlot < 0 || !childIdentity.isEmpty() || !matches(child(before, parentSlot), "", childSeed)) return;
            String generated = child(initialized, parentSlot).toStack().getOrDefault(BagComponents.IDENTITY, "");
            if (generated.isEmpty() || !matches(child(replacement, parentSlot), generated, ItemVariant.blank())) return;
            updateSnapshots(transaction);
            childIdentity = generated;
        }
        @Override protected String createSnapshot() { return childIdentity; }
        @Override protected void readSnapshot(String previous) { childIdentity = previous; }
    }

    private record TankView(ContainerItemContext context, Binding binding, BagPath path, int upgradeSlot) implements StorageView<FluidVariant> {
        private BackpackTank tank() {
            if (!BackpackConfig.get().storage().itemFluidAccess()) return null;
            BagInventory bag = read(context, binding);
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
            return mutate(context, binding, transaction, (bag, nested) -> {
                BackpackTraversal.Node node = path.find(context, bag);
                BackpackTank tank = node == null ? null : ResourceRuntime.tank(node.inventory(), upgradeSlot, true);
                long moved = tank == null ? 0 : tank.extract(fluid, maximum, nested);
                if (moved > 0) node.persist();
                return moved;
            });
        }
    }

    private record EnergyAccess(ContainerItemContext context, Binding binding) implements EnergyStorage {
        private EnergyStorage battery() {
            BagInventory bag = read(context, binding);
            return bag == null ? null : ResourceRuntime.energyStorage(bag);
        }
        @Override public boolean supportsInsertion() { EnergyStorage value = battery(); return value != null && value.supportsInsertion(); }
        @Override public boolean supportsExtraction() { EnergyStorage value = battery(); return value != null && value.supportsExtraction(); }
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
            return mutate(context, binding, transaction, (bag, nested) -> {
                EnergyStorage battery = ResourceRuntime.energyStorage(bag);
                return battery == null ? 0 : battery.insert(maximum, nested);
            });
        }
        @Override public long extract(long maximum, TransactionContext transaction) {
            if (maximum < 0) throw new IllegalArgumentException("Negative energy extraction");
            return mutate(context, binding, transaction, (bag, nested) -> {
                EnergyStorage battery = ResourceRuntime.energyStorage(bag);
                return battery == null ? 0 : battery.extract(maximum, nested);
            });
        }
    }
}
