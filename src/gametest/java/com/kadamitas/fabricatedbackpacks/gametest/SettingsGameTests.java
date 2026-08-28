package com.kadamitas.fabricatedbackpacks.gametest;

import com.kadamitas.fabricatedbackpacks.compat.NbtAccess;
import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.menu.BackpackMenu;
import com.kadamitas.fabricatedbackpacks.menu.BackpackMenus;
import com.kadamitas.fabricatedbackpacks.resource.BackpackTank;
import com.kadamitas.fabricatedbackpacks.settings.SettingsRuntime;
import com.kadamitas.fabricatedbackpacks.settings.SettingsTemplate;
import com.kadamitas.fabricatedbackpacks.storage.BagComponents;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.util.UUID;

import static com.kadamitas.fabricatedbackpacks.gametest.BackpackTestSupport.*;

final class SettingsGameTests {
    private SettingsGameTests() {}

    static void settingsOnlyTemplates(GameTestHelper helper) {
        BagInventory source = bag(BackpackTier.NETHERITE, UpgradeKind.STACK_UPGRADE_TIER_4, UpgradeKind.ADVANCED_JUKEBOX, UpgradeKind.TANK, UpgradeKind.ADVANCED_PICKUP);
        source.setItem(0, new ItemStack(Items.DIAMOND, 200_000));
        source.remember(0, new ItemStack(Items.DIAMOND));
        source.remember(119, new ItemStack(Items.EMERALD));
        source.toggleNoSort(1);
        source.updateSettings(tag -> { tag.putInt("display_slot", 0); tag.putInt("display_depth", 9); tag.putString("captured_entities", "must not copy"); });
        source.upgradeInventory(upgrade(source, 1)).setItem(11, new ItemStack(Items.MUSIC_DISC_13));
        source.setFilter(upgrade(source, 3), 0, new ItemStack(Items.GOLD_INGOT));
        source.updateSettings(upgrade(source, 1), tag -> { tag.putBoolean("playing", true); tag.putString("repeat", "all"); tag.putLong("song_finish", 999); });
        var sourceTank = new BackpackTank(source, upgrade(source, 2), false);
        try (var transaction = Transaction.openOuter()) { sourceTank.insert(FluidVariant.of(Fluids.WATER), 81000, transaction); transaction.commit(); }
        SettingsTemplate template = SettingsTemplate.capture(source);
        var ops = RegistryOps.create(NbtOps.INSTANCE, helper.getLevel().registryAccess());
        var encoded = SettingsTemplate.CODEC.encodeStart(ops, template).getOrThrow();
        helper.assertFalse(encoded.toString().contains("captured_entities") || encoded.toString().contains("song_finish")
                || encoded.toString().contains("amount_droplets") || encoded.toString().contains("music_disc_13"), "Settings serialization excludes captured mobs, playback clocks, stored fluid and physical record slots");
        SettingsTemplate decoded = SettingsTemplate.CODEC.parse(ops, encoded).getOrThrow();
        BagInventory destination = bag(BackpackTier.NETHERITE, UpgradeKind.STACK_UPGRADE_TIER_4, UpgradeKind.ADVANCED_JUKEBOX, UpgradeKind.TANK, UpgradeKind.ADVANCED_PICKUP);
        destination.setItem(0, new ItemStack(Items.GOLD_INGOT, 17));
        destination.upgradeInventory(upgrade(destination, 1)).setItem(0, new ItemStack(Items.MUSIC_DISC_CAT));
        var destinationTank = new BackpackTank(destination, upgrade(destination, 2), false);
        try (var transaction = Transaction.openOuter()) { destinationTank.insert(FluidVariant.of(Fluids.LAVA), 41, transaction); transaction.commit(); }
        destination.updateSettings(upgrade(destination, 1), tag -> { tag.putBoolean("playing", false); tag.putLong("song_finish", 42); tag.putBoolean("shuffle", true); });
        String identity = destination.identity();
        decoded.apply(destination);
        helper.assertValueEqual(destination.identity(), identity, "Applying settings preserves the destination identity");
        helper.assertValueEqual(count(destination, Items.GOLD_INGOT), 17, "Applying settings preserves physical main contents");
        helper.assertValueEqual(count(destination, Items.DIAMOND), 0, "Template loading never creates the source's 200,000 diamonds");
        helper.assertTrue(destination.upgradeInventory(upgrade(destination, 1)).getItem(0).is(Items.MUSIC_DISC_CAT), "Destination physical record is retained");
        helper.assertTrue(destination.upgradeInventory(upgrade(destination, 1)).getItem(11).isEmpty(), "Template never duplicates the source record");
        helper.assertValueEqual(destinationTank.getAmount(), 41L, "Fluid quantity is not a setting and remains unchanged");
        helper.assertTrue(destinationTank.getResource().equals(FluidVariant.of(Fluids.LAVA)), "Fluid identity is not replaced by source water");
        helper.assertValueEqual(NbtAccess.getLongOr(destination.settings(upgrade(destination, 1)), "song_finish", 0), 42L, "Runtime clocks remain the destination's own");
        helper.assertFalse(NbtAccess.getBooleanOr(destination.settings(upgrade(destination, 1)), "shuffle", false), "Absent template option resets to its default instead of retaining stale settings");
        helper.assertTrue(destination.ghost(upgrade(destination, 3), 0).is(Items.GOLD_INGOT), "Ghost filters transfer as settings");
        helper.assertTrue(destination.stack().get(BagComponents.MEMORY).entries().stream().noneMatch(entry -> entry.slot() == 0), "Incompatible occupied memory slot is skipped");
        helper.succeed();
    }

    static void templateGeometryAndValidation(GameTestHelper helper) {
        BagInventory source = bag(BackpackTier.NETHERITE, UpgradeKind.ADVANCED_PICKUP);
        source.remember(2, new ItemStack(Items.EMERALD));
        source.remember(119, new ItemStack(Items.DIAMOND));
        source.updateSettings(tag -> { tag.putIntArray("no_sort", new int[]{-1, 1, 1, 119, 999}); tag.putInt("display_slot", 119); tag.putInt("display_rotation", 721); tag.putInt("display_depth", 900); });
        source.setFilter(upgrade(source, 0), 0, new ItemStack(Items.GOLD_INGOT));
        source.setFilter(upgrade(source, 0), 13, new ItemStack(Items.DIAMOND));
        BagInventory small = bag(BackpackTier.LEATHER, UpgradeKind.PICKUP);
        small.setItem(5, new ItemStack(Items.STICK, 4));
        SettingsTemplate.capture(source).apply(small);
        helper.assertValueEqual(small.stack().get(BagComponents.MEMORY).entries().size(), 1, "Only in-range memory slots survive a smaller backpack template load");
        helper.assertValueEqual(small.stack().get(BagComponents.MEMORY).entries().getFirst().slot(), 2, "Compatible small-bag memory uses the same physical slot");
        helper.assertTrue(java.util.Arrays.equals(NbtAccess.getIntArray(small.settings(), "no_sort").orElseThrow(), new int[]{1}), "No-sort slots are unique and bounded by actual geometry");
        helper.assertValueEqual(NbtAccess.getIntOr(small.settings(), "display_slot", 0), -1, "An out-of-range exterior slot becomes disabled");
        helper.assertValueEqual(NbtAccess.getIntOr(small.settings(), "display_rotation", -1), 0, "Rotation is normalized into 45-degree increments");
        helper.assertValueEqual(NbtAccess.getIntOr(small.settings(), "display_depth", 0), 16, "Exterior depth clamps to the supported range");
        helper.assertTrue(small.ghost(upgrade(small, 0), 0).is(Items.GOLD_INGOT), "Advanced-to-basic compatible filter settings are retained");
        helper.assertTrue(upgrade(small, 0).stack().get(BagComponents.FILTERS).entries().stream().allMatch(entry -> entry.slot() < UpgradeKind.PICKUP.filterSlots()), "Advanced filter rows cannot exceed the basic upgrade's capacity");
        helper.assertValueEqual(count(small, Items.STICK), 4, "Geometry adaptation never discards existing physical contents");
        helper.succeed();
    }

    static void privateDefaultsAndTemplateNames(GameTestHelper helper) {
        var first = player(helper);
        var second = player(helper);
        var original = bag(BackpackTier.LEATHER);
        helper.assertTrue(NbtAccess.getBooleanOr(SettingsRuntime.effective(original, first), "keep_search", true), "Keep-search starts enabled");
        SettingsRuntime.action(original, first, "setting", 0, "keep_search");
        SettingsRuntime.action(original, first, "defaults_save", 0, "");
        var fresh = bag(BackpackTier.LEATHER);
        helper.assertFalse(NbtAccess.getBooleanOr(SettingsRuntime.effective(fresh, first), "keep_search", true), "Another backpack inherits the same player's saved default");
        helper.assertTrue(NbtAccess.getBooleanOr(SettingsRuntime.effective(fresh, second), "keep_search", true), "Private defaults do not leak to another player");
        fresh.updateSettings(tag -> tag.putBoolean("keep_search", true));
        helper.assertTrue(NbtAccess.getBooleanOr(SettingsRuntime.effective(fresh, first), "keep_search", false), "An explicit bag preference overrides the player's default");
        SettingsRuntime.action(fresh, first, "defaults_use", 0, "");
        helper.assertFalse(NbtAccess.getBooleanOr(SettingsRuntime.effective(fresh, first), "keep_search", true), "Use-defaults removes the bag override");
        helper.assertFalse(SettingsRuntime.action(fresh, first, "setting", 0, "sort_order"), "Boolean toggle cannot overwrite a string preference");
        for (String invalid : new String[]{"../escape", "/absolute", "bad:name", "", "x".repeat(49), "a\nb"}) {
            helper.assertFalse(SettingsRuntime.action(fresh, first, "template_save", 0, invalid), "Unsafe personal name is rejected: " + invalid.replace('\n', ' '));
        }
        fresh.remember(0, new ItemStack(Items.EMERALD));
        helper.assertTrue(SettingsRuntime.action(fresh, first, "template_save", 0, "1"), "Numbered template saves");
        helper.assertTrue(SettingsRuntime.action(fresh, first, "template_save", 0, "Mining Trip"), "Named template saves");
        helper.assertTrue(SettingsRuntime.names(first).containsAll(java.util.List.of("1", "Mining Trip")), "Both names are available for cycling");
        helper.assertFalse(SettingsRuntime.names(second).contains("Mining Trip"), "Personal templates remain private");
        var before = fresh.stack().copy();
        helper.assertTrue(SettingsRuntime.action(fresh, first, "template_preview", 0, "Mining Trip"), "Template preview is available before applying");
        assertStack(helper, fresh.stack(), before, "Previewing cannot mutate the backpack");
        helper.assertTrue(NbtAccess.getStringOr(SettingsRuntime.view(fresh, first).copyTag(), "template_preview", "").contains("1 memory"), "Preview describes the saved contents of the settings template");
        fresh.remember(0, ItemStack.EMPTY);
        SettingsRuntime.action(fresh, first, "template_load", 0, "Mining Trip");
        helper.assertValueEqual(fresh.stack().get(BagComponents.MEMORY).entries().size(), 1, "Personal template restores remembered slots");
        SettingsRuntime.action(fresh, first, "template_delete", 0, "1");
        helper.assertFalse(SettingsRuntime.names(first).contains("1"), "Deleting one personal template retains the other");
        helper.assertTrue(SettingsRuntime.names(first).contains("Mining Trip"), "Deleting does not erase unrelated templates");
        helper.succeed();
    }

    static void menuPreferences(GameTestHelper helper) {
        var player = player(helper);
        var bag = bag(BackpackTier.GOLD, UpgradeKind.ADVANCED_JUKEBOX);
        player.getInventory().setItem(0, bag.stack());
        BackpackMenus.openInventory(player, 0);
        var menu = (BackpackMenu) player.containerMenu;
        menu.clickMenuButton(player, 100);
        player.getInventory().setItem(9, new ItemStack(Items.MUSIC_DISC_13));
        int slot = menuSlot(menu, player.getInventory(), 9);
        menu.clicked(slot, 0, ClickType.QUICK_MOVE, player);
        helper.assertTrue(bag.getItem(0).is(Items.MUSIC_DISC_13), "Default shift-click goes into main storage");
        bag.setItem(0, ItemStack.EMPTY);
        player.getInventory().setItem(9, new ItemStack(Items.MUSIC_DISC_CAT));
        SettingsRuntime.action(bag, player, "setting", 0, "shift_into_tab");
        menu.clicked(slot, 0, ClickType.QUICK_MOVE, player);
        helper.assertTrue(bag.upgradeInventory(upgrade(bag, 0)).getItem(0).is(Items.MUSIC_DISC_CAT), "Configured shift-click reaches the selected real record inventory");
        helper.assertTrue(player.getInventory().getItem(9).isEmpty(), "Selected-tab insertion consumes the source exactly once");
        SettingsRuntime.action(bag, player, "search", 0, "@minecraft diamond");
        player.closeContainer();
        BackpackMenus.openInventory(player, 0);
        helper.assertValueEqual(((BackpackMenu) player.containerMenu).selectedSlot(), 0, "Keep-tab reopens the selected upgrade");
        helper.assertValueEqual(NbtAccess.getStringOr(bag.settings(), "last_search", ""), "@minecraft diamond", "Keep-search persists the entered query");
        SettingsRuntime.action(bag, player, "setting", 0, "keep_tab");
        SettingsRuntime.action(bag, player, "setting", 0, "keep_search");
        SettingsRuntime.action(bag, player, "search", 0, "will not persist");
        player.closeContainer();
        BackpackMenus.openInventory(player, 0);
        helper.assertValueEqual(((BackpackMenu) player.containerMenu).selectedSlot(), -1, "Disabling keep-tab restores the main view on next open");
        helper.assertFalse(bag.settings().contains("last_search"), "Disabling keep-search removes the persisted query");
        player.closeContainer();
        helper.succeed();
    }

    static void permissionCheckedExport(GameTestHelper helper) {
        var player = player(helper);
        var bag = bag(BackpackTier.LEATHER);
        bag.remember(0, new ItemStack(Items.EMERALD));
        String name = "test_" + UUID.randomUUID().toString().substring(0, 8);
        var root = player.level().getServer().getWorldPath(LevelResource.DATAPACK_DIR);
        var pack = root.resolve("fabricated_backpacks_settings_" + name);
        helper.assertFalse(SettingsRuntime.action(bag, player, "template_export", 0, name), "Nonoperator cannot create server datapacks");
        helper.assertFalse(Files.exists(pack), "Denied export creates no files");
        // Exercise native authority with a real level-two entry; GameTestServer's /op default is zero.
        var players = player.level().getServer().getPlayerList();
        players.getOps().add(new net.minecraft.server.players.ServerOpListEntry(player.getGameProfile(), 2, false));
        players.sendPlayerPermissionLevel(player);
        try {
            helper.assertTrue(player.hasPermissions(2), "Export fixture has the actual server game-master permission");
            helper.assertFalse(SettingsRuntime.action(bag, player, "template_export", 0, "../outside"), "Even operators cannot traverse outside the datapack directory");
            helper.assertTrue(SettingsRuntime.action(bag, player, "template_export", 0, name), "Operator can export a new settings datapack");
            var path = pack.resolve("data/fabricated_backpacks/backpack_settings/" + name + ".snbt");
            var parsed = SettingsTemplate.CODEC.parse(RegistryOps.create(NbtOps.INSTANCE, helper.getLevel().registryAccess()), TagParser.parseTag(Files.readString(path))).getOrThrow();
            helper.assertValueEqual(parsed.memory().entries().size(), 1, "Exported SNBT round-trips with the actual registry-aware codec");
            var manifest = com.google.gson.JsonParser.parseString(Files.readString(pack.resolve("pack.mcmeta"))).getAsJsonObject();
            var metadata = net.minecraft.server.packs.metadata.pack.PackMetadataSection.CODEC
                    .parse(com.mojang.serialization.JsonOps.INSTANCE, manifest.get("pack")).getOrThrow();
            helper.assertValueEqual(metadata.packFormat(),
                    net.minecraft.SharedConstants.getCurrentVersion().getPackVersion(net.minecraft.server.packs.PackType.SERVER_DATA),
                    "Exported manifest is accepted by the exact Minecraft server-data codec");
            String original = Files.readString(path);
            helper.assertFalse(SettingsRuntime.action(bag, player, "template_export", 0, name), "Export does not overwrite an existing pack");
            helper.assertValueEqual(Files.readString(path), original, "Rejected overwrite leaves the existing pack byte-for-byte intact");
        } catch (Exception exception) { throw new AssertionError("Datapack export failed", exception); }
        finally { player.level().getServer().getPlayerList().deop(player.getGameProfile()); }
        helper.succeed();
    }

    static void dataPackSettings(GameTestHelper helper) {
        var player = player(helper);
        var bag = bag(BackpackTier.LEATHER);
        bag.setItem(1, new ItemStack(Items.DIAMOND, 7));
        helper.assertTrue(SettingsRuntime.names(player).contains("fabricated_backpacks_tests:compact"), "Resource manager discovers installed settings datapacks");
        helper.assertTrue(SettingsRuntime.action(bag, player, "template_load", 0, "fabricated_backpacks_tests:compact"), "Namespaced datapack template loads through the real resource manager");
        helper.assertValueEqual(NbtAccess.getIntOr(bag.settings(), "display_rotation", -1), 90, "Datapack settings are applied");
        helper.assertFalse(NbtAccess.getBooleanOr(bag.settings(), "keep_search", true), "Datapack navigation preference is applied");
        helper.assertValueEqual(count(bag, Items.DIAMOND), 7, "Datapack template cannot replace stored items");
        helper.succeed();
    }
}
