package com.kadamitas.fabricatedbackpacks.gameplay;

import com.kadamitas.fabricatedbackpacks.compat.NbtAccess;
import com.kadamitas.fabricatedbackpacks.config.BackpackConfig;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.resource.ResourceRuntime;
import com.kadamitas.fabricatedbackpacks.storage.BagComponents;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.storage.InventorySnapshot;
import com.kadamitas.fabricatedbackpacks.upgrade.InventoryMoves;
import com.kadamitas.fabricatedbackpacks.upgrade.UpgradeEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One physical containment level, shared by processing and resource views without expanding a bag's own slots. */
public final class BackpackTraversal {
    private static final Map<MinecraftServer, TickPass> TICKS = new IdentityHashMap<>();
    private BackpackTraversal() {}

    public record Node(BagInventory inventory, BagInventory parent, int parentSlot) {
        public boolean attached() {
            if (!BackpackRegistry.isBackpack(inventory.stack()) || inventory.stack().getCount() != 1) return false;
            return parent == null || parent.has(UpgradeKind.INCEPTION) && !inventory.has(UpgradeKind.INCEPTION)
                    && parentSlot >= 0 && parentSlot < parent.getContainerSize()
                    && parent.getItem(parentSlot) == inventory.stack()
                    && !parent.identity().equals(inventory.identity());
        }

        /** Call after a successful physical child mutation, including a resource transaction's final commit. */
        public void persist() {
            if (!attached()) return;
            inventory.save();
            if (parent != null) parent.setChanged();
        }
    }

    public static boolean usesChildren(BagInventory root) {
        return root.has(UpgradeKind.INCEPTION) && BackpackConfig.get().storage().outerUsesChildren()
                && NbtAccess.getBooleanOr(root.settings(), "inception_outer_inventory", true);
    }

    public static boolean ticksChildren(BagInventory root) {
        return root.has(UpgradeKind.INCEPTION) && BackpackConfig.get().storage().childUpgrades()
                && NbtAccess.getBooleanOr(root.settings(), "inception_inner_upgrades", true);
    }

    public static boolean nestedFirst(BagInventory root) {
        return NbtAccess.getBooleanOr(root.settings(), "inception_nested_first", true);
    }

    /** Invalid, duplicate and recursively populated child bags never enter a live processing graph. */
    public static List<Node> children(BagInventory root) {
        if (!root.has(UpgradeKind.INCEPTION)) return List.of();
        List<Node> children = new ArrayList<>();
        Set<ItemStack> physical = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<String> identities = new HashSet<>();
        boolean initialized = false;
        physical.add(root.stack());
        identities.add(root.identity());
        for (int slot = 0; slot < Math.min(root.getContainerSize(), InventorySnapshot.MAX_SLOTS); slot++) {
            ItemStack candidate = root.getItem(slot);
            if (!BackpackRegistry.isBackpack(candidate) || candidate.getCount() != 1 || !physical.add(candidate)) continue;
            String identity = candidate.getOrDefault(BagComponents.IDENTITY, "");
            if (!identity.isEmpty() && identities.contains(identity)) continue;
            if (candidate.getOrDefault(BagComponents.UPGRADES, InventorySnapshot.EMPTY).entries().stream()
                    .anyMatch(entry -> BackpackRegistry.kind(entry.create()).orElse(null) == UpgradeKind.INCEPTION)) continue;
            if (candidate.getOrDefault(BagComponents.CONTENTS, InventorySnapshot.EMPTY).entries().stream()
                    .anyMatch(entry -> BackpackRegistry.isBackpack(entry.create()))) continue;
            BagInventory child = BagInventory.of(candidate);
            initialized |= identity.isEmpty();
            if (!identities.add(child.identity())) continue;
            children.add(new Node(child, root, slot));
        }
        if (initialized) root.setChanged();
        return List.copyOf(children);
    }

    public static List<Node> upgradeBags(BagInventory root) {
        Node main = new Node(root, null, -1);
        if (!ticksChildren(root)) return List.of(main);
        List<Node> ordered = new ArrayList<>(children(root));
        if (nestedFirst(root)) ordered.add(main); else ordered.addFirst(main);
        return List.copyOf(ordered);
    }

    /** Ordered nodes for an outer upgrade or external resource adapter; child ticking is a separate setting. */
    public static List<Node> inventoryBags(BagInventory root) {
        Node main = new Node(root, null, -1);
        if (!usesChildren(root)) return List.of(main);
        List<Node> ordered = new ArrayList<>(children(root));
        if (nestedFirst(root)) ordered.add(main); else ordered.addFirst(main);
        return List.copyOf(ordered);
    }

    public static Container processingInventory(BagInventory root) { return processingInventory(root, null); }

    public static Container processingInventory(BagInventory root, Player actor) {
        return usesChildren(root) ? new ProcessingInventory(root, inventoryBags(root), actor) : root;
    }

    public record SlotAddress(BagInventory inventory, int slot) {}

    public static SlotAddress address(Container inventory, int slot) {
        if (inventory instanceof ProcessingInventory view) {
            Node node = view.node(slot);
            return node == null ? null : new SlotAddress(node.inventory(), view.physicalSlot(slot));
        }
        return inventory instanceof BagInventory bag && slot >= 0 && slot < bag.getContainerSize()
                ? new SlotAddress(bag, slot) : null;
    }

    public static int resolve(Container inventory, SlotAddress address) {
        if (address == null) return -1;
        if (inventory == address.inventory()) return address.slot() < inventory.getContainerSize() ? address.slot() : -1;
        if (inventory instanceof ProcessingInventory view) {
            for (int slot = 0; slot < view.getContainerSize(); slot++) {
                Node node = view.node(slot);
                if (node != null && node.inventory() == address.inventory() && view.physicalSlot(slot) == address.slot()) return slot;
            }
        }
        return -1;
    }

    /** Read all current physical cells into a detached plan before a simulation can allocate child handles. */
    public static ItemStack insert(BagInventory root, ItemStack supplied, boolean simulate, Player actor) {
        if (supplied.isEmpty()) return ItemStack.EMPTY;
        if (!usesChildren(root) || BackpackRegistry.isBackpack(supplied)) return root.insert(supplied, simulate, actor);
        BagInventory target = root;
        if (simulate) target = simulationCopy(root);
        return InventoryMoves.insert(processingInventory(target, actor), supplied, simulate);
    }

    public static BagInventory simulationCopy(BagInventory root) {
        ItemStack copy = root.stack().copy();
        copy.set(BagComponents.CONTENTS, InventorySnapshot.capture(root));
        return BagInventory.of(copy);
    }

    public static void tick(BagInventory root, ServerLevel level, BlockPos position, LivingEntity carrier) {
        List<Node> children = children(root);
        if (ticksChildren(root)) {
            if (nestedFirst(root)) children.forEach(child -> tickOne(child, level, position, carrier));
        } else {
            for (Node child : children) {
                if (!child.attached()) continue;
                var before = child.inventory().stack().getComponentsPatch();
                UpgradeEngine.pause(child.inventory(), level.getServer());
                if (!before.equals(child.inventory().stack().getComponentsPatch())) child.persist();
            }
        }
        tickOne(new Node(root, null, -1), level, position, carrier);
        if (ticksChildren(root) && !nestedFirst(root)) children.forEach(child -> tickOne(child, level, position, carrier));
    }

    private static void tickOne(Node node, ServerLevel level, BlockPos position, LivingEntity carrier) {
        if (!node.attached()) return;
        MinecraftServer server = level.getServer();
        TickPass pass = TICKS.computeIfAbsent(server, ignored -> new TickPass());
        int now = server.getTickCount();
        if (pass.tick != now) { pass.tick = now; pass.identities.clear(); pass.physical.clear(); }
        BagInventory bag = node.inventory();
        if (!pass.physical.add(bag.stack()) || !pass.identities.add(bag.identity())) return;
        var before = bag.stack().getComponentsPatch();
        UpgradeEngine.tick(bag, level, position, carrier);
        ResourceRuntime.tick(bag, level, position, carrier);
        if (node.parent() != null && !before.equals(bag.stack().getComponentsPatch())) node.persist();
    }

    public static void stop(MinecraftServer server) { TICKS.remove(server); }

    private static final class TickPass {
        int tick = Integer.MIN_VALUE;
        final Set<String> identities = new HashSet<>();
        final Set<ItemStack> physical = Collections.newSetFromMap(new IdentityHashMap<>());
    }

    /** Stable per-operation slot mapping; never exposes a nested bag carrier as a consumable slot. */
    public static final class ProcessingInventory implements Container {
        private record Cell(Node node, int slot) {}
        private final BagInventory root;
        private final List<Node> nodes;
        private final List<Cell> cells;
        private final Player actor;

        private ProcessingInventory(BagInventory root, List<Node> nodes, Player actor) {
            this.root = root;
            this.nodes = List.copyOf(nodes);
            this.actor = actor;
            List<Cell> cells = new ArrayList<>();
            for (Node node : nodes) for (int slot = 0; slot < node.inventory().getContainerSize(); slot++) cells.add(new Cell(node, slot));
            this.cells = List.copyOf(cells);
        }

        public boolean attached() { return usesChildren(root) && nodes.stream().allMatch(Node::attached); }
        private Cell cell(int slot) { return slot >= 0 && slot < cells.size() ? cells.get(slot) : null; }
        private boolean valid(Cell cell) { return cell != null && cell.node.attached() && (cell.node.parent() == null || usesChildren(root)); }
        private boolean carrier(Cell cell) { return BackpackRegistry.isBackpack(cell.node.inventory().getItem(cell.slot)); }
        public int capacity(int slot, ItemStack stack) { Cell cell = cell(slot); return valid(cell) ? cell.node.inventory().capacity(stack) : 0; }
        public Node node(int slot) { Cell cell = cell(slot); return valid(cell) ? cell.node : null; }
        public int physicalSlot(int slot) { Cell cell = cell(slot); return valid(cell) ? cell.slot : -1; }

        @Override public int getContainerSize() { return cells.size(); }
        @Override public boolean isEmpty() { for (int slot = 0; slot < cells.size(); slot++) if (!getItem(slot).isEmpty()) return false; return true; }
        @Override public ItemStack getItem(int slot) { Cell cell = cell(slot); return valid(cell) ? cell.node.inventory().getItem(cell.slot) : ItemStack.EMPTY; }
        @Override public int getMaxStackSize() { return Integer.MAX_VALUE; }
        @Override public int getMaxStackSize(ItemStack stack) { return nodes.stream().filter(Node::attached).mapToInt(node -> node.inventory().capacity(stack)).max().orElse(0); }
        @Override public boolean canPlaceItem(int slot, ItemStack item) {
            Cell cell = cell(slot);
            return valid(cell) && !carrier(cell) && !BackpackRegistry.isBackpack(item)
                    && UpgradeEngine.acceptsInput(root, item) && cell.node.inventory().canPlaceItem(cell.slot, item, actor);
        }
        @Override public boolean canTakeItem(Container target, int slot, ItemStack item) {
            Cell cell = cell(slot);
            return valid(cell) && !carrier(cell) && UpgradeEngine.acceptsOutput(root, item)
                    && cell.node.inventory().canTakeItem(target, cell.slot, item);
        }
        @Override public void setItem(int slot, ItemStack item) {
            Cell cell = cell(slot);
            if (!valid(cell) || carrier(cell) || BackpackRegistry.isBackpack(item)) return;
            ItemStack previous = cell.node.inventory().getItem(cell.slot);
            if (ItemStack.matches(previous, item)) return;
            cell.node.inventory().setItem(cell.slot, item);
            cell.node.persist();
            if (!item.isEmpty() && (!ItemStack.isSameItemSameComponents(previous, item) || item.getCount() > previous.getCount())) {
                UpgradeEngine.onExternalInsertion(cell.node.inventory());
            }
        }
        @Override public ItemStack removeItem(int slot, int amount) {
            Cell cell = cell(slot);
            if (!valid(cell) || !canTakeItem(null, slot, getItem(slot))) return ItemStack.EMPTY;
            ItemStack removed = cell.node.inventory().removeItem(cell.slot, amount);
            if (!removed.isEmpty()) cell.node.persist();
            return removed;
        }
        @Override public ItemStack removeItemNoUpdate(int slot) { return removeItem(slot, getItem(slot).getCount()); }
        @Override public void setChanged() { nodes.stream().filter(Node::attached).forEach(Node::persist); }
        @Override public boolean stillValid(Player player) { return attached() && root.stillValid(player); }
        @Override public void clearContent() {
            for (int slot = 0; slot < cells.size(); slot++) if (canTakeItem(null, slot, getItem(slot))) setItem(slot, ItemStack.EMPTY);
        }
    }
}
