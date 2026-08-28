package com.kadamitas.fabricatedbackpacks.resource;

import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.gameplay.BackpackTraversal;
import com.kadamitas.fabricatedbackpacks.gameplay.BackpackTraversal.Node;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.upgrade.UpgradeEngine;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StoragePreconditions;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.storage.TransferVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.base.CombinedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;
import net.minecraft.core.Direction;
import team.reborn.energy.api.EnergyStorage;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Ordered resource adapters retain physical storage ownership and become inert when a child is detached. */
final class TraversalResources {
    private static final Runnable NO_CHANGE = () -> {};
    private TraversalResources() {}

    static Storage<ItemVariant> items(BagInventory root, Direction direction, BooleanSupplier available, Runnable changed) {
        Storage<ItemVariant> ordered = new DynamicStorage<>(root, ItemVariant::blank, available, changed,
                node -> {
                    Storage<ItemVariant> physical = new BackpackItemStorage(node.inventory(), direction);
                    return node.parent() == null ? physical : new VoidItemStorage(node.inventory(), physical, () -> live(root, node, available));
                },
                item -> UpgradeEngine.acceptsInput(root, item.toStack()),
                item -> UpgradeEngine.acceptsOutput(root, item.toStack()), true);
        return new VoidItemStorage(root, ordered, () -> live(root, new Node(root, null, -1), available));
    }

    static Storage<FluidVariant> fluids(BagInventory root, boolean rateLimited) {
        return fluids(root, rateLimited, () -> true, NO_CHANGE);
    }

    static Storage<FluidVariant> fluids(BagInventory root, boolean rateLimited, BooleanSupplier available, Runnable changed) {
        Storage<FluidVariant> ordered = new DynamicStorage<>(root, FluidVariant::blank, available, changed,
                node -> {
                    Storage<FluidVariant> physical = new CombinedStorage<>(node.inventory().installedUpgrades().stream()
                        .filter(upgrade -> upgrade.kind() == UpgradeKind.TANK)
                        .map(upgrade -> new BackpackTank(node.inventory(), upgrade, rateLimited)).toList());
                    return node.parent() == null ? physical : new VoidFluidStorage(node.inventory(), physical, () -> live(root, node, available));
                },
                fluid -> true, fluid -> true, false);
        return new VoidFluidStorage(root, ordered, () -> live(root, new Node(root, null, -1), available));
    }

    static EnergyStorage energy(BagInventory root) { return energy(root, () -> true, NO_CHANGE); }

    static EnergyStorage energy(BagInventory root, BooleanSupplier available, Runnable changed) {
        return new DynamicEnergy(root, available, changed);
    }

    private static boolean live(BagInventory root, Node node, BooleanSupplier available) {
        return available.getAsBoolean() && node.attached()
                && (node.parent() == null || BackpackTraversal.usesChildren(root));
    }

    /** Registered before the leaf snapshot: rollback reserializes the restored child after its leaf has unwound. */
    private static final class Persist extends SnapshotParticipant<Boolean> {
        private final Node node;
        private final Runnable changed;
        private final BagInventory processingRoot;
        private boolean inserted;
        Persist(Node node, Runnable changed) { this(node, changed, null); }
        Persist(Node node, Runnable changed, BagInventory processingRoot) { this.node = node; this.changed = changed; this.processingRoot = processingRoot; }
        void prepare(TransactionContext transaction) { updateSnapshots(transaction); }
        void inserted() { inserted = true; }
        @Override protected Boolean createSnapshot() { return inserted; }
        @Override protected void readSnapshot(Boolean previous) { inserted = previous; node.persist(); }
        @Override protected void onFinalCommit() {
            if (node.attached()) {
                node.persist();
                if (inserted) {
                    UpgradeEngine.onExternalInsertion(node.inventory());
                    if (processingRoot != null && processingRoot != node.inventory()) UpgradeEngine.onExternalInsertion(processingRoot);
                }
                changed.run();
            }
            inserted = false;
        }
    }

    private static final class DynamicStorage<T extends TransferVariant<?>> implements Storage<T> {
        private record Part<T>(Node node, Storage<T> storage, Persist persistence) {}
        private final BagInventory root;
        private final Supplier<T> blank;
        private final BooleanSupplier available;
        private final Runnable changed;
        private final Function<Node, Storage<T>> factory;
        private final Predicate<T> acceptsInput;
        private final Predicate<T> acceptsOutput;
        private final boolean itemStorage;

        DynamicStorage(BagInventory root, Supplier<T> blank, BooleanSupplier available, Runnable changed,
                       Function<Node, Storage<T>> factory, Predicate<T> acceptsInput, Predicate<T> acceptsOutput,
                       boolean itemStorage) {
            this.root = root;
            this.blank = blank;
            this.available = available;
            this.changed = changed;
            this.factory = factory;
            this.acceptsInput = acceptsInput;
            this.acceptsOutput = acceptsOutput;
            this.itemStorage = itemStorage;
        }

        private boolean carrier(T resource) {
            return itemStorage && resource instanceof ItemVariant item && BackpackRegistry.isBackpack(item.toStack());
        }

        private List<Part<T>> parts(boolean rootOnly) {
            if (!available.getAsBoolean()) return List.of();
            List<Node> nodes = rootOnly ? List.of(new Node(root, null, -1)) : BackpackTraversal.inventoryBags(root);
            return nodes.stream().filter(node -> live(root, node, available))
                    .map(node -> new Part<>(node, factory.apply(node), new Persist(node, changed, root))).toList();
        }

        @Override public long insert(T resource, long maximum, TransactionContext transaction) {
            StoragePreconditions.notBlankNotNegative(resource, maximum);
            if (maximum == 0 || !acceptsInput.test(resource)) return 0;
            long inserted = 0;
            // A newly admitted backpack is a physical root slot, never a grandchild.
            for (Part<T> part : parts(carrier(resource))) {
                if (!live(root, part.node, available)) continue;
                part.persistence.prepare(transaction);
                long accepted = part.storage.insert(resource, maximum - inserted, transaction);
                inserted += accepted;
                if (itemStorage && accepted > 0) part.persistence.inserted();
                if (inserted == maximum) break;
            }
            return inserted;
        }

        @Override public long extract(T resource, long maximum, TransactionContext transaction) {
            StoragePreconditions.notBlankNotNegative(resource, maximum);
            if (maximum == 0 || !acceptsOutput.test(resource)
                    || carrier(resource) && BackpackTraversal.usesChildren(root)) return 0;
            long extracted = 0;
            for (Part<T> part : parts(false)) {
                if (!live(root, part.node, available)) continue;
                part.persistence.prepare(transaction);
                extracted += part.storage.extract(resource, maximum - extracted, transaction);
                if (extracted == maximum) break;
            }
            return extracted;
        }

        @Override public Iterator<StorageView<T>> iterator() {
            List<StorageView<T>> views = new ArrayList<>();
            for (Part<T> part : parts(false)) for (StorageView<T> leaf : part.storage) {
                views.add(new SingleSlotStorage<>() {
                    private boolean visible() {
                        return live(root, part.node, available)
                                && !(BackpackTraversal.usesChildren(root) && carrier(leaf.getResource()));
                    }
                    @Override public boolean isResourceBlank() { return getResource().isBlank(); }
                    @Override public T getResource() { return visible() ? leaf.getResource() : blank.get(); }
                    @Override public long getAmount() { return visible() ? leaf.getAmount() : 0; }
                    @Override public long getCapacity() { return visible() ? leaf.getCapacity() : 0; }
                    @Override @SuppressWarnings("unchecked") public long insert(T resource, long maximum, TransactionContext transaction) {
                        StoragePreconditions.notBlankNotNegative(resource, maximum);
                        if (maximum == 0 || !visible() || !acceptsInput.test(resource)
                                || carrier(resource) && part.node.parent() != null || !(leaf instanceof Storage<?>)) return 0;
                        part.persistence.prepare(transaction);
                        long accepted = ((Storage<T>) leaf).insert(resource, maximum, transaction);
                        if (itemStorage && accepted > 0) part.persistence.inserted();
                        return accepted;
                    }
                    @Override public long extract(T resource, long maximum, TransactionContext transaction) {
                        StoragePreconditions.notBlankNotNegative(resource, maximum);
                        if (maximum == 0 || !visible() || !acceptsOutput.test(resource)) return 0;
                        part.persistence.prepare(transaction);
                        return leaf.extract(resource, maximum, transaction);
                    }
                });
            }
            return views.iterator();
        }
    }

    private record DynamicEnergy(BagInventory root, BooleanSupplier available, Runnable changed) implements EnergyStorage {
        private record Part(Node node, BackpackBattery battery, Persist persistence) {}

        private List<Part> parts() {
            if (!available.getAsBoolean()) return List.of();
            List<Part> parts = new ArrayList<>();
            for (Node node : BackpackTraversal.inventoryBags(root)) {
                if (!live(root, node, available)) continue;
                for (var upgrade : node.inventory().installedUpgrades()) if (upgrade.kind() == UpgradeKind.BATTERY) {
                    parts.add(new Part(node, new BackpackBattery(node.inventory(), upgrade), new Persist(node, changed)));
                }
            }
            return parts;
        }

        @Override public long getAmount() {
            long amount = 0;
            for (Part part : parts()) amount = saturatedAdd(amount, part.battery.getAmount());
            return amount;
        }
        @Override public long getCapacity() {
            long capacity = 0;
            for (Part part : parts()) capacity = saturatedAdd(capacity, part.battery.getCapacity());
            return capacity;
        }
        @Override public long insert(long maximum, TransactionContext transaction) {
            if (maximum < 0) throw new IllegalArgumentException("Negative energy insertion");
            if (maximum == 0) return 0;
            long inserted = 0;
            for (Part part : parts()) {
                if (!live(root, part.node, available)) continue;
                part.persistence.prepare(transaction);
                inserted += part.battery.insert(maximum - inserted, transaction);
                if (inserted == maximum) break;
            }
            return inserted;
        }
        @Override public long extract(long maximum, TransactionContext transaction) {
            if (maximum < 0) throw new IllegalArgumentException("Negative energy extraction");
            if (maximum == 0) return 0;
            long extracted = 0;
            for (Part part : parts()) {
                if (!live(root, part.node, available)) continue;
                part.persistence.prepare(transaction);
                extracted += part.battery.extract(maximum - extracted, transaction);
                if (extracted == maximum) break;
            }
            return extracted;
        }
        private static long saturatedAdd(long first, long second) { return first + Math.min(second, Long.MAX_VALUE - first); }
    }
}
