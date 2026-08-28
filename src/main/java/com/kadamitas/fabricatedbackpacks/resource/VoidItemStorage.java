package com.kadamitas.fabricatedbackpacks.resource;

import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.storage.InstalledUpgrade;
import com.kadamitas.fabricatedbackpacks.upgrade.UpgradeEngine;
import com.kadamitas.fabricatedbackpacks.upgrade.UpgradeFilters;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StoragePreconditions;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;

import java.util.Iterator;
import java.util.List;
import java.util.function.BooleanSupplier;

/** The item API uses the same explicit void admission modes as native pickup; leaf storage owns rollback. */
final class VoidItemStorage implements Storage<ItemVariant> {
    private final BagInventory bag;
    private final Storage<ItemVariant> storage;
    private final BooleanSupplier available;

    VoidItemStorage(BagInventory bag, Storage<ItemVariant> storage, BooleanSupplier available) {
        this.bag = bag;
        this.storage = storage;
        this.available = available;
    }

    @Override public long insert(ItemVariant resource, long maximum, TransactionContext transaction) {
        StoragePreconditions.notBlankNotNegative(resource, maximum);
        if (maximum == 0 || !available.getAsBoolean() || !UpgradeEngine.acceptsInput(bag, resource.toStack())) return 0;
        InstalledUpgrade selected = BackpackRegistry.isBackpack(resource.toStack()) ? null : bag.installedUpgrades().stream()
                .filter(upgrade -> upgrade.kind().family().equals("void") && UpgradeFilters.enabled(bag, upgrade)
                        && UpgradeFilters.matches(bag, upgrade, resource.toStack())).findFirst().orElse(null);
        if (selected == null) return storage.insert(resource, maximum, transaction);
        return switch (UpgradeEngine.voidMode(bag.settings(selected))) {
            case "ALWAYS" -> maximum;
            case "SLOT_OVERFLOW" -> {
                var settings = bag.settings(selected);
                long represented = 0;
                for (var view : storage) if (!view.isResourceBlank() && UpgradeFilters.same(resource.toStack(), view.getResource().toStack(),
                        "ITEM", settings.getBooleanOr("match_damage", false), settings.getBooleanOr("match_components", false))) {
                    represented += Math.min(view.getAmount(), Long.MAX_VALUE - represented);
                }
                long allowance = Math.max(0, bag.capacity(resource.toStack()) - Math.min(Integer.MAX_VALUE, represented));
                long attempt = Math.min(maximum, allowance);
                long inserted = attempt == 0 ? 0 : storage.insert(resource, attempt, transaction);
                yield represented == 0 && inserted == 0 ? 0 : maximum - (attempt - inserted);
            }
            default -> { storage.insert(resource, maximum, transaction); yield maximum; }
        };
    }

    @Override public long extract(ItemVariant resource, long maximum, TransactionContext transaction) {
        StoragePreconditions.notBlankNotNegative(resource, maximum);
        return available.getAsBoolean() ? storage.extract(resource, maximum, transaction) : 0;
    }

    @Override public Iterator<StorageView<ItemVariant>> iterator() {
        return available.getAsBoolean() ? storage.iterator() : List.<StorageView<ItemVariant>>of().iterator();
    }
}
