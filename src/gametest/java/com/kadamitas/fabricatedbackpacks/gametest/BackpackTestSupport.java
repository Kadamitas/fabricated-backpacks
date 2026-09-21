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
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
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

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraftforge.network.ForgePayload;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import com.kadamitas.fabricatedbackpacks.platform.network.NativeNetworking;

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
        new EmbeddedChannel(connection);
        helper.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        player.setGameMode(GameType.SURVIVAL);
        player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
        Vec3 position = helper.absoluteVec(new Vec3(6.5, 1, 6.5));
        player.setPos(position);
        return player;
    }

    /**
     * The vanilla {@code minecraft:register} payload a real client sends to advertise its channels,
     * routed through Forge's own registration handler. Forge's payload record is internal, so this
     * test-only helper writes the same NUL-separated channel list the client would.
     */
    static CustomPacketPayload registerChannels(Set<Identifier> channels) {
        FriendlyByteBuf data = new FriendlyByteBuf(Unpooled.buffer());
        for (Identifier channel : channels) {
            data.writeBytes(channel.toString().getBytes(StandardCharsets.UTF_8));
            data.writeByte(0);
        }
        return ForgePayload.create(Identifier.parse("minecraft:register"), data);
    }

    /** Exercise the actual Forge encoder and receiver, as a real packet's wire bytes do. */
    static void send(ServerPlayer player, CustomPacketPayload payload) {
        if (payload instanceof ForgePayload) {
            player.connection.handleCustomPayload(new ServerboundCustomPayloadPacket(payload));
            return;
        }
        var data = new RegistryFriendlyByteBuf(Unpooled.buffer(), player.registryAccess());
        try {
            NativeNetworking.channel().encode(data, payload);
            player.connection.handleCustomPayload(new ServerboundCustomPayloadPacket(ForgePayload.create(payload.type().id(), data)));
        } finally { data.release(); }
    }

    /** The embedded fixture has no client decoder pipeline; decode only payloads asserted by tests. */
    static CustomPacketPayload decode(ServerPlayer player, CustomPacketPayload payload) {
        if (!(payload instanceof ForgePayload wire)) return payload;
        net.minecraft.network.codec.StreamCodec<? super RegistryFriendlyByteBuf, ? extends CustomPacketPayload> codec = switch (wire.id().getPath()) {
            case "browser_catalog_page" -> com.kadamitas.fabricatedbackpacks.browser.BrowserCatalogPage.STREAM_CODEC;
            case "browser_context" -> com.kadamitas.fabricatedbackpacks.browser.BrowserContext.STREAM_CODEC;
            case "browser_catalog_invalidated" -> com.kadamitas.fabricatedbackpacks.browser.BrowserCatalogInvalidated.STREAM_CODEC;
            case "browser_transfer_result" -> com.kadamitas.fabricatedbackpacks.browser.BrowserTransferResult.STREAM_CODEC;
            case "jukebox_audio" -> com.kadamitas.fabricatedbackpacks.network.JukeboxAudio.STREAM_CODEC;
            default -> null;
        };
        if (codec == null || !wire.id().getNamespace().equals("fabricated_backpacks")) return payload;
        var data = new RegistryFriendlyByteBuf(Unpooled.buffer(), player.registryAccess());
        try { wire.write(data); return codec.decode(data); }
        finally { data.release(); }
    }

    static int count(Container inventory, Item item) {
        int total = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) if (inventory.getItem(slot).is(item)) total += inventory.getItem(slot).getCount();
        return total;
    }

    static int count(com.kadamitas.fabricatedbackpacks.block.BackpackBlockEntity backpack, Item item) {
        return count(backpack.inventory(), item);
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
