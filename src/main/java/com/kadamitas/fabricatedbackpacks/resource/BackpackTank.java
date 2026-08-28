package com.kadamitas.fabricatedbackpacks.resource;

import com.kadamitas.fabricatedbackpacks.config.BackpackConfig;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.storage.InstalledUpgrade;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.StoragePreconditions;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;
import net.minecraft.nbt.CompoundTag;
import java.util.Objects;

/** Writes through to the upgrade item; separate API handles therefore observe one resource. */
public final class BackpackTank extends SnapshotParticipant<BackpackTank.State>
        implements SingleSlotStorage<FluidVariant> {
    private final BagInventory bag;
    private final InstalledUpgrade upgrade;
    private final boolean rateLimited;
    private final Runnable committed;

    public BackpackTank(BagInventory bag, InstalledUpgrade upgrade, boolean rateLimited, Runnable committed) {
        this.bag = Objects.requireNonNull(bag);
        this.upgrade = Objects.requireNonNull(upgrade);
        this.rateLimited = rateLimited;
        this.committed = Objects.requireNonNull(committed);
        if (upgrade.kind() != UpgradeKind.TANK) throw new IllegalArgumentException("Not a tank upgrade");
    }

    public BackpackTank(BagInventory bag, InstalledUpgrade upgrade, boolean rateLimited) {
        this(bag, upgrade, rateLimited, bag::save);
    }

    private boolean attached() {
        return upgrade.slot() >= 0 && upgrade.slot() < bag.upgrades().getContainerSize()
                && bag.upgrades().getItem(upgrade.slot()) == upgrade.stack()
                && upgrade.stack().getCount() == 1;
    }

    @Override public long getAmount() {
        if (!attached()) return 0;
        CompoundTag tag = bag.settings(upgrade);
        return new FluidAmount(tag.getLongOr("amount", 0),
                Math.toIntExact(tag.getLongOr("amount_droplets", 0))).droplets();
    }

    @Override public FluidVariant getResource() {
        return getAmount() == 0 ? FluidVariant.blank()
                : upgrade.stack().getOrDefault(ResourceComponents.TANK_FLUID, FluidVariant.blank());
    }

    @Override public boolean isResourceBlank() { return getResource().isBlank(); }

    @Override public long getCapacity() {
        return attached() ? FluidAmount.dropletsForMb(BackpackConfig.get().upgrades().tank().capacity(bag.rows(), bag.multiplier())) : 0;
    }

    @Override public long insert(FluidVariant resource, long maximum, TransactionContext transaction) {
        StoragePreconditions.notBlankNotNegative(resource, maximum);
        if (!attached() || maximum == 0) return 0;
        long stored = getAmount();
        if (stored > 0 && !resource.equals(getResource())) return 0;
        long accepted = Math.min(operationLimit(maximum), Math.max(0, getCapacity() - stored));
        if (accepted == 0) return 0;
        updateSnapshots(transaction);
        write(new State(resource, stored + accepted));
        return accepted;
    }

    @Override public long extract(FluidVariant resource, long maximum, TransactionContext transaction) {
        StoragePreconditions.notBlankNotNegative(resource, maximum);
        if (!attached() || !resource.equals(getResource())) return 0;
        long stored = getAmount();
        long extracted = Math.min(operationLimit(maximum), stored);
        if (extracted == 0) return 0;
        updateSnapshots(transaction);
        write(new State(resource, stored - extracted));
        return extracted;
    }

    private long operationLimit(long requested) {
        return rateLimited ? Math.min(requested, FluidAmount.dropletsForMb(
                BackpackConfig.get().upgrades().tank().transfer(bag.rows(), bag.multiplier()))) : requested;
    }

    @Override protected State createSnapshot() { return new State(getResource(), getAmount()); }
    @Override protected void readSnapshot(State snapshot) { write(snapshot); }
    @Override protected void onFinalCommit() { committed.run(); }

    private void write(State state) {
        if (state.droplets == 0) upgrade.stack().remove(ResourceComponents.TANK_FLUID);
        else upgrade.stack().set(ResourceComponents.TANK_FLUID, state.fluid);
        FluidAmount amount = FluidAmount.fromDroplets(state.droplets);
        bag.updateSettings(upgrade, tag -> {
            tag.putLong("amount", amount.millibuckets());
            tag.putLong("amount_droplets", amount.remainderDroplets());
        });
    }

    record State(FluidVariant fluid, long droplets) {}
}
