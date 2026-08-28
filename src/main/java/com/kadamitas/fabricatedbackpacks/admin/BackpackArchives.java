package com.kadamitas.fabricatedbackpacks.admin;

import com.kadamitas.fabricatedbackpacks.storage.BagComponents;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.storage.InventorySnapshot;
import com.kadamitas.fabricatedbackpacks.world.WorldComponents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;

/** Latest-access snapshots are independent of the lifetime of the accessed inventory handle. */
public final class BackpackArchives {
    private BackpackArchives() { }

    public static void record(ServerLevel level, BagInventory bag, ServerPlayer owner) {
        if (!AdminNames.isIdentity(bag.identity()) || bag.stack().getCount() != 1) return;
        AdminSavedData data = AdminSavedData.of(level.getServer());
        BackpackArchive previous = data.archive(bag.identity()).orElse(null);
        String ownerId = owner != null ? owner.getUUID().toString() : previous == null ? "" : previous.ownerId();
        String ownerName = owner != null ? owner.getGameProfile().name() : previous == null ? "" : previous.ownerName();
        long now = System.currentTimeMillis();
        // A periodic scan refreshes access at most once per minute unless contents or ownership change.
        if (previous != null && previous.sameContents(bag.stack()) && previous.ownerId().equals(ownerId)
                && previous.ownerName().equals(ownerName) && now - previous.accessedAt() < 60_000) return;
        CustomModelData colors = bag.stack().getOrDefault(DataComponents.CUSTOM_MODEL_DATA, CustomModelData.EMPTY);
        int body = colors.getColor(0) == null ? 0xB97843 : colors.getColor(0);
        int trim = colors.getColor(1) == null ? 0x503B36 : colors.getColor(1);
        data.record(new BackpackArchive(bag.identity(), ownerId, ownerName, bag.stack().getHoverName().getString(),
                body & 0xffffff, trim & 0xffffff, Math.max(now, previous == null ? 0 : previous.accessedAt()), bag.stack()));
    }

    public static boolean isEmpty(ItemStack backpack) {
        for (var type : java.util.List.of(BagComponents.CONTENTS, BagComponents.UPGRADES, WorldComponents.EXTRA_ITEMS))
            if (!backpack.getOrDefault(type, InventorySnapshot.EMPTY).entries().isEmpty()) return false;
        return !backpack.has(WorldComponents.DEFERRED_LOOT)
                && backpack.getOrDefault(BagComponents.SETTINGS, CustomData.EMPTY).copyTag().getListOrEmpty("captured_entities").isEmpty();
    }
}
