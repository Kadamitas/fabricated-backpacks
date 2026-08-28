package com.kadamitas.fabricatedbackpacks.resource;

import com.kadamitas.fabricatedbackpacks.compat.NbtAccess;

import com.kadamitas.fabricatedbackpacks.block.BackpackBlockEntity;
import com.kadamitas.fabricatedbackpacks.config.BackpackConfig;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.gameplay.BackpackTraversal;
import com.kadamitas.fabricatedbackpacks.gameplay.BackpackTraversal.Node;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.storage.InstalledUpgrade;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import team.reborn.energy.api.EnergyStorage;

import java.util.IdentityHashMap;
import java.util.Map;

/** The placed source owns one rollback-safe output allowance per physical battery and server tick. */
public final class PlacedEnergyTransfer {
    private final BackpackBlockEntity entity;
    private final Map<ItemStack, OutputBudget> budgets = new IdentityHashMap<>();
    private long budgetTick = Long.MIN_VALUE;
    private int previousSupport = -1;

    public PlacedEnergyTransfer(BackpackBlockEntity entity) { this.entity = entity; }

    public void contentsReplaced() { previousSupport = -1; }

    public EnergyStorage storage(Direction side) {
        if (entity.getLevel() != null && entity.getLevel().isClientSide) return new EnergyStorage() {
            private int flags() { return entity.isRemoved() ? 0 : entity.clientEnergySupport() >>> ((side == null ? 6 : side.ordinal()) * 2); }
            @Override public boolean supportsInsertion() { return (flags() & 1) != 0; }
            @Override public boolean supportsExtraction() { return (flags() & 2) != 0; }
            @Override public long getAmount() { return 0; }
            @Override public long getCapacity() { return 0; }
            @Override public long insert(long maximum, TransactionContext transaction) {
                if (maximum < 0) throw new IllegalArgumentException("Negative energy insertion");
                return 0;
            }
            @Override public long extract(long maximum, TransactionContext transaction) {
                if (maximum < 0) throw new IllegalArgumentException("Negative energy extraction");
                return 0;
            }
        };
        BagInventory root = entity.inventory();
        return TraversalResources.externalEnergy(root, () -> current(root, side), entity::setChanged, this::battery);
    }

    private boolean current(BagInventory root, Direction side) {
        return !entity.isRemoved() && entity.getLevel() instanceof ServerLevel
                && entity.stack() == root.stack()
                && ResourceRuntime.connectionAllowed(entity.getLevel(), entity.getBlockPos(), side);
    }

    private OutputBudget budget(ItemStack upgrade) {
        long tick = entity.getLevel().getGameTime();
        if (tick != budgetTick) {
            budgetTick = tick;
            budgets.clear();
        }
        return budgets.computeIfAbsent(upgrade, ignored -> new OutputBudget());
    }

    private EnergyStorage battery(Node node, InstalledUpgrade upgrade) {
        BackpackBattery physical = new BackpackBattery(node.inventory(), upgrade, () -> {
            if (node.attached()) { node.persist(); entity.setChanged(); }
        });
        return new EnergyStorage() {
            @Override public long getAmount() { return physical.getAmount(); }
            @Override public long getCapacity() { return physical.getCapacity(); }
            @Override public boolean supportsInsertion() { return getCapacity() > 0; }
            @Override public boolean supportsExtraction() { return getCapacity() > 0 && outputEnabled(node, upgrade); }
            @Override public long insert(long maximum, TransactionContext transaction) { return physical.insert(maximum, transaction); }
            @Override public long extract(long maximum, TransactionContext transaction) {
                if (maximum < 0) throw new IllegalArgumentException("Negative energy extraction");
                if (maximum == 0 || !supportsExtraction()) return 0;
                OutputBudget allowance = budget(upgrade.stack());
                long rate = BackpackConfig.get().upgrades().battery().transfer(node.inventory().rows(), node.inventory().multiplier());
                long request = Math.min(maximum, Math.max(0, rate - allowance.used));
                if (request == 0) return 0;
                allowance.updateSnapshots(transaction);
                long extracted = physical.extract(request, transaction);
                allowance.used += extracted;
                return extracted;
            }
        };
    }

    private static boolean outputEnabled(Node node, InstalledUpgrade upgrade) {
        return node.attached() && NbtAccess.getBooleanOr(node.inventory().settings(upgrade), "external_output", true);
    }

    /** Called only by the placed block tick. Carried and equipped batteries never push to the world. */
    public void tick(ServerLevel level, BlockPos position) {
        BagInventory root = entity.inventory();
        if (entity.getLevel() != level || level.getBlockEntity(position) != entity || entity.isRemoved()) return;
        int support = supportMask(root);
        if (support != previousSupport) {
            previousSupport = support;
            // Energy API consumers cache whether a port can insert/extract independently of its current amount.
            level.updateNeighborsAt(position, entity.getBlockState().getBlock());
            entity.synchronize();
        }
        if (!current(root, null)) return;
        Direction[] sides = Direction.values();
        int firstSide = Math.floorMod(level.getGameTime(), sides.length);
        for (Node node : BackpackTraversal.inventoryBags(root)) {
            if (!node.attached()) continue;
            for (InstalledUpgrade upgrade : node.inventory().installedUpgrades()) {
                if (upgrade.kind() != UpgradeKind.BATTERY || !outputEnabled(node, upgrade)) continue;
                EnergyStorage source = battery(node, upgrade);
                for (int offset = 0; offset < sides.length && source.getAmount() > 0; offset++) {
                    Direction side = sides[(firstSide + offset) % sides.length];
                    if (!ResourceRuntime.connectionAllowed(level, position, side)) continue;
                    BlockPos neighbor = position.relative(side);
                    EnergyStorage receiver = EnergyStorage.SIDED.find(level, neighbor, side.getOpposite());
                    if (receiver == null || !receiver.supportsInsertion()) continue;
                    // An output-enabled backpack is another source, not a sink. This avoids same-tick
                    // circulation between neighboring backpacks without imposing a policy on other mods.
                    if (level.getBlockEntity(neighbor) instanceof BackpackBlockEntity && receiver.supportsExtraction()) continue;
                    ResourceContainers.moveEnergy(source, receiver, Long.MAX_VALUE);
                }
            }
        }
    }

    public int supportMask() {
        return entity.getLevel() instanceof ServerLevel ? supportMask(entity.inventory()) : 0;
    }

    private int supportMask(BagInventory root) {
        if (!current(root, null)) return 0;
        boolean input = false;
        boolean output = false;
        for (Node node : BackpackTraversal.inventoryBags(root)) for (InstalledUpgrade upgrade : node.inventory().installedUpgrades()) {
            if (upgrade.kind() != UpgradeKind.BATTERY || !node.attached()
                    || new BackpackBattery(node.inventory(), upgrade).getCapacity() <= 0) continue;
            input = true;
            output |= outputEnabled(node, upgrade);
        }
        int mask = (input ? 1 : 0) << 12 | (output ? 2 : 0) << 12;
        for (Direction side : Direction.values()) if (ResourceRuntime.connectionAllowed(entity.getLevel(), entity.getBlockPos(), side)) {
            if (input) mask |= 1 << (side.ordinal() * 2);
            if (output) mask |= 2 << (side.ordinal() * 2);
        }
        return mask;
    }

    private static final class OutputBudget extends SnapshotParticipant<Long> {
        private long used;
        @Override protected Long createSnapshot() { return used; }
        @Override protected void readSnapshot(Long value) { used = value; }
    }
}
