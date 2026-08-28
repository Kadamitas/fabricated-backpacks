package com.kadamitas.fabricatedbackpacks.gametest;

import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.storage.InstalledUpgrade;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/** Connected server players are test fixtures, not claims of real client coverage. */
final class BackpackTestSupport {
    private BackpackTestSupport() {}

    static BagInventory bag(BackpackTier tier, UpgradeKind... upgrades) {
        BagInventory bag = BagInventory.of(new ItemStack(BackpackRegistry.item(tier)));
        for (int slot = 0; slot < upgrades.length; slot++) {
            ItemStack item = new ItemStack(BackpackRegistry.item(upgrades[slot]));
            if (!bag.canInstall(slot, item)) throw new IllegalArgumentException("Invalid test upgrade " + upgrades[slot]);
            bag.upgrades().setItem(slot, item);
        }
        return bag;
    }

    static InstalledUpgrade upgrade(BagInventory bag, int slot) {
        return bag.installedUpgrades().stream().filter(upgrade -> upgrade.slot() == slot).findFirst().orElseThrow();
    }

    static ItemStack roundTrip(ServerLevel level, ItemStack stack) {
        var ops = RegistryOps.create(NbtOps.INSTANCE, level.registryAccess());
        var encoded = ItemStack.CODEC.encodeStart(ops, stack).getOrThrow();
        return ItemStack.CODEC.parse(ops, encoded).getOrThrow();
    }

    static ServerPlayer player(GameTestHelper helper) {
        UUID id = UUID.randomUUID();
        var cookie = CommonListenerCookie.createInitial(new GameProfile(id, "bp_test_" + id.toString().substring(0, 8)), false);
        ServerPlayer player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        var channel = new EmbeddedChannel(connection);
        helper.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        player.setGameMode(GameType.SURVIVAL);
        Vec3 position = helper.absoluteVec(new Vec3(6.5, 1, 6.5));
        player.connection.teleport(position.x, position.y, position.z, 0, 0);
        // Acknowledge the actual native teleport ID, including the initial login
        // teleport. 1.21.1 has no separate player-loaded acknowledgement packet.
        Object outbound;
        while ((outbound = channel.readOutbound()) != null) {
            if (outbound instanceof ClientboundPlayerPositionPacket teleport) {
                player.connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(teleport.getId()));
            }
        }
        return player;
    }

    static int count(Container inventory, Item item) {
        int total = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) if (inventory.getItem(slot).is(item)) total += inventory.getItem(slot).getCount();
        return total;
    }

    static int menuSlot(AbstractContainerMenu menu, Container inventory, int inventorySlot) {
        for (int slot = 0; slot < menu.slots.size(); slot++) {
            var candidate = menu.slots.get(slot);
            if (candidate.container == inventory && candidate.getContainerSlot() == inventorySlot) return slot;
        }
        throw new IllegalArgumentException("Missing player slot " + inventorySlot);
    }

    static void assertStack(GameTestHelper helper, ItemStack actual, ItemStack expected, String message) {
        helper.assertTrue(ItemStack.matches(actual, expected), message + ": expected " + expected + ", got " + actual);
    }

    static void assertStack(GameTestHelper helper, ItemStack actual, Item expected, int count, String message) {
        assertStack(helper, actual, new ItemStack(expected, count), message);
    }
}
