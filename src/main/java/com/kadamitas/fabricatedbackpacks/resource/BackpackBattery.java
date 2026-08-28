package com.kadamitas.fabricatedbackpacks.resource;

import com.kadamitas.fabricatedbackpacks.config.BackpackConfig;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.storage.InstalledUpgrade;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;
import team.reborn.energy.api.EnergyStorage;
import java.util.Objects;

/** Team Reborn Energy storage with rollback-safe item-component persistence. */
public final class BackpackBattery extends SnapshotParticipant<Long> implements EnergyStorage {
    private final BagInventory bag;
    private final InstalledUpgrade upgrade;
    private final Runnable committed;

    public BackpackBattery(BagInventory bag, InstalledUpgrade upgrade, Runnable committed) {
        this.bag = Objects.requireNonNull(bag);
        this.upgrade = Objects.requireNonNull(upgrade);
        this.committed = Objects.requireNonNull(committed);
        if (upgrade.kind() != UpgradeKind.BATTERY) throw new IllegalArgumentException("Not a battery upgrade");
    }

    public BackpackBattery(BagInventory bag, InstalledUpgrade upgrade) { this(bag, upgrade, bag::save); }

    private boolean attached() {
        return upgrade.slot() >= 0 && upgrade.slot() < bag.upgrades().getContainerSize()
                && bag.upgrades().getItem(upgrade.slot()) == upgrade.stack()
                && upgrade.stack().getCount() == 1;
    }

    @Override public long getAmount() {
        long stored = attached() ? bag.settings(upgrade).getLongOr("amount", 0) : 0;
        if (stored < 0) throw new IllegalStateException("Negative stored energy");
        return stored;
    }

    @Override public long getCapacity() {
        return attached() ? BackpackConfig.get().upgrades().battery().capacity(bag.rows(), bag.multiplier()) : 0;
    }

    @Override public long insert(long maximum, TransactionContext transaction) {
        if (maximum < 0) throw new IllegalArgumentException("Negative energy insertion");
        if (!attached()) return 0;
        long amount = getAmount();
        long accepted = Math.min(rate(maximum), Math.max(0, getCapacity() - amount));
        if (accepted == 0) return 0;
        updateSnapshots(transaction);
        write(amount + accepted);
        return accepted;
    }

    @Override public long extract(long maximum, TransactionContext transaction) {
        if (maximum < 0) throw new IllegalArgumentException("Negative energy extraction");
        if (!attached()) return 0;
        long extracted = Math.min(rate(maximum), getAmount());
        if (extracted == 0) return 0;
        updateSnapshots(transaction);
        write(getAmount() - extracted);
        return extracted;
    }

    private long rate(long requested) {
        return Math.min(requested, BackpackConfig.get().upgrades().battery().transfer(bag.rows(), bag.multiplier()));
    }

    private void write(long amount) { bag.updateSettings(upgrade, tag -> tag.putLong("amount", amount)); }
    @Override protected Long createSnapshot() { return getAmount(); }
    @Override protected void readSnapshot(Long amount) { write(amount); }
    @Override protected void onFinalCommit() { committed.run(); }
}
