package com.kadamitas.fabricatedbackpacks.admin;

import com.kadamitas.fabricatedbackpacks.storage.BackpackCopies;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.Collection;

final class AdminDelivery {
    private AdminDelivery() { }
    static int give(ItemStack snapshot, Collection<ServerPlayer> recipients) {
        int delivered = 0;
        for (ServerPlayer recipient : recipients) {
            ItemStack copy = BackpackCopies.fork(snapshot);
            BagInventory.of(copy);
            // Archive before the inventory mutates its supplied count. The recipient owns this new identity.
            ItemStack archived = copy.copy();
            recipient.getInventory().add(copy);
            if (!copy.isEmpty() && recipient.drop(copy, false) == null) continue;
            BackpackArchives.record(recipient.serverLevel(), BagInventory.of(archived), recipient);
            recipient.containerMenu.broadcastChanges();
            delivered++;
        }
        return delivered;
    }
}
