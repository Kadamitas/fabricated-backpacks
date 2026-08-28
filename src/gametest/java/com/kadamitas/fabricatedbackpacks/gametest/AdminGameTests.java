package com.kadamitas.fabricatedbackpacks.gametest;

import com.kadamitas.fabricatedbackpacks.admin.AdminSavedData;
import com.kadamitas.fabricatedbackpacks.admin.BackpackAdmin;
import com.kadamitas.fabricatedbackpacks.admin.BackpackArchive;
import com.kadamitas.fabricatedbackpacks.admin.BackpackArchives;
import com.kadamitas.fabricatedbackpacks.admin.BackpackTemplates;
import com.kadamitas.fabricatedbackpacks.admin.WholeBagTemplate;
import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.menu.BackpackMenu;
import com.kadamitas.fabricatedbackpacks.menu.BackpackMenus;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.storage.BagComponents;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.storage.InventorySnapshot;
import com.kadamitas.fabricatedbackpacks.world.MobLoot;
import com.kadamitas.fabricatedbackpacks.world.WorldComponents;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.DimensionDataStorage;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Actual dispatcher, saved-data, datapack and inventory operations, not command-tree shape checks. */
public final class AdminGameTests {
    private AdminGameTests() { }
    private static String name(String prefix) { return prefix + UUID.randomUUID().toString().replace("-", ""); }
    private static void operator(ServerPlayer player) {
        var players = player.serverLevel().getServer().getPlayerList();
        players.getOps().add(new net.minecraft.server.players.ServerOpListEntry(player.getGameProfile(), 2, false));
        players.sendPlayerPermissionLevel(player);
    }
    private static void ordinary(ServerPlayer player) { player.level().getServer().getPlayerList().deop(player.getGameProfile()); }
    private static int command(ServerPlayer player, String command) {
        try { return player.level().getServer().getCommands().getDispatcher().execute(command, player.createCommandSourceStack()); }
        catch (CommandSyntaxException exception) { throw new AssertionError("Valid command failed: " + command, exception); }
    }
    private static void denied(GameTestHelper helper, ServerPlayer player, String command) {
        try {
            int result = player.level().getServer().getCommands().getDispatcher().execute(command, player.createCommandSourceStack());
            helper.assertValueEqual(result, 0, "A denied command cannot report success: " + command);
        } catch (CommandSyntaxException expected) { }
    }
    private static List<ItemStack> bags(ServerPlayer player) {
        List<ItemStack> result = new ArrayList<>();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++)
            if (BackpackRegistry.isBackpack(player.getInventory().getItem(slot))) result.add(player.getInventory().getItem(slot));
        return result;
    }

    public static void adminCommandPermissions(GameTestHelper helper) {
        ServerPlayer player = BackpackTestSupport.player(helper);
        String template = name("permission_");
        player.getInventory().setItem(0, BackpackTestSupport.bag(BackpackTier.LEATHER).stack());
        denied(helper, player, "fb template create " + template);
        player.setGameMode(GameType.CREATIVE);
        denied(helper, player, "fabricatedbackpacks template create " + template);
        denied(helper, player, "fb cleanup nonplayer");
        helper.assertTrue(AdminSavedData.of(helper.getLevel().getServer()).template(template).isEmpty(), "Creative mode does not grant template authoring permission");
        player.setGameMode(GameType.SURVIVAL);
        operator(player);
        try {
            helper.assertValueEqual(command(player, "fb"), 1, "Short command root is registered for the real operator");
            helper.assertValueEqual(command(player, "fabricatedbackpacks"), 1, "Full command alias is also registered");
            helper.assertValueEqual(command(player, "fb template create " + template), 1, "An operator in survival can author a template");
        } finally { ordinary(player); }
        denied(helper, player, "fb template give " + template + " " + player.getGameProfile().getName());
        helper.assertValueEqual(bags(player).size(), 1, "Revoked permission cannot grant another backpack");
        helper.succeed();
    }

    public static void archiveSnapshotAndRecovery(GameTestHelper helper) {
        ServerPlayer author = BackpackTestSupport.player(helper);
        ServerPlayer first = BackpackTestSupport.player(helper);
        ServerPlayer second = BackpackTestSupport.player(helper);
        BagInventory bag = BackpackTestSupport.bag(BackpackTier.NETHERITE, UpgradeKind.INCEPTION, UpgradeKind.STACK_UPGRADE_TIER_4);
        bag.dye(0x234567, 0xABCDEF);
        bag.stack().set(DataComponents.CUSTOM_NAME, Component.literal("Archived expedition"));
        bag.setItem(0, new ItemStack(Items.DIAMOND, 999));
        ItemStack tool = new ItemStack(Items.DIAMOND_PICKAXE);
        tool.enchant(helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.FORTUNE), 3);
        bag.setItem(1, tool);
        BagInventory nested = BackpackTestSupport.bag(BackpackTier.LEATHER);
        nested.setItem(2, new ItemStack(Items.EMERALD, 17));
        bag.setItem(2, nested.stack());
        BagInventory overflow = BackpackTestSupport.bag(BackpackTier.COPPER);
        MobLoot.queue(bag, List.of(overflow.stack()));
        BackpackArchives.record(helper.getLevel(), bag, author);
        AdminSavedData data = AdminSavedData.of(helper.getLevel().getServer());
        var archive = data.archive(bag.identity()).orElseThrow();
        ItemStack detached = archive.backpack();
        BagInventory.of(detached).setItem(0, new ItemStack(Items.COAL, 1));
        helper.assertValueEqual(BagInventory.of(archive.backpack()).getItem(0).getCount(), 999, "Archive accessors never expose their saved stack");
        bag.setItem(3, new ItemStack(Items.IRON_INGOT, 12));
        helper.assertTrue(BagInventory.of(archive.backpack()).getItem(3).isEmpty(), "Live changes cannot mutate an earlier archive value");
        BackpackArchives.record(helper.getLevel(), bag, null);
        archive = data.archive(bag.identity()).orElseThrow();
        helper.assertTrue(archive.playerBacked() && archive.ownerId().equals(author.getUUID().toString()), "Placed/non-player access cannot remove player archive protection");
        helper.assertValueEqual(archive.bodyColor(), 0x234567, "Access log records colors");
        helper.assertTrue(archive.itemName().equals("Archived expedition") && archive.accessedAt() > 0, "Access log records name and time");
        operator(author);
        try {
            helper.assertTrue(command(author, "fb list player " + author.getGameProfile().getName()) >= 1, "Actual command filters archives by player name");
            helper.assertValueEqual(command(author, "fb recover " + UUID.randomUUID() + " " + first.getGameProfile().getName()), 0, "An unknown archive cannot grant a substitute backpack");
            helper.assertTrue(bags(first).isEmpty(), "Failed recovery leaves the target inventory unchanged");
            helper.assertValueEqual(command(author, "fb recover " + bag.identity() + " " + first.getGameProfile().getName()), 1, "Recovery dispatch gives first recipient one bag");
            helper.assertValueEqual(command(author, "fabricatedbackpacks give " + bag.identity() + " " + second.getGameProfile().getName()), 1, "Recovery give alias uses the same saved snapshot");
        } finally { ordinary(author); }
        ItemStack a = bags(first).getFirst();
        ItemStack b = bags(second).getFirst();
        helper.assertFalse(a.get(BagComponents.IDENTITY).equals(b.get(BagComponents.IDENTITY)) || a.get(BagComponents.IDENTITY).equals(bag.identity()), "Every recipient gets an independent root identity");
        BagInventory recovered = BagInventory.of(a);
        helper.assertValueEqual(recovered.getItem(0).getCount(), 999, "Enhanced stack quantities survive command recovery");
        helper.assertTrue(ItemStack.isSameItemSameComponents(recovered.getItem(1), tool), "Enchanted items retain registry-aware components");
        helper.assertFalse(recovered.getItem(2).get(BagComponents.IDENTITY).equals(nested.identity()), "Nested inventory identities are forked");
        var extra = a.get(WorldComponents.EXTRA_ITEMS).items().getFirst();
        helper.assertFalse(extra.get(BagComponents.IDENTITY).equals(overflow.identity()), "Deferred extra backpack identities are forked too");
        recovered.setItem(0, new ItemStack(Items.COAL));
        helper.assertValueEqual(BagInventory.of(data.archive(bag.identity()).orElseThrow().backpack()).getItem(0).getCount(), 999, "Changing recovered storage leaves the saved archive intact");
        helper.succeed();
    }

    public static void templateDatapackExport(GameTestHelper helper) {
        ServerPlayer author = BackpackTestSupport.player(helper);
        ServerPlayer recipient = BackpackTestSupport.player(helper);
        BagInventory bag = BackpackTestSupport.bag(BackpackTier.COPPER, UpgradeKind.STACK_UPGRADE_TIER_4);
        bag.setItem(7, new ItemStack(Items.REDSTONE, 1001));
        author.getInventory().setItem(0, bag.stack());
        String name = name("export_");
        operator(author);
        try {
            command(author, "fb template create " + name);
            helper.assertValueEqual(command(author, "fb template export " + name), 1, "Export command creates a new datapack");
            var pack = helper.getLevel().getServer().getWorldPath(LevelResource.DATAPACK_DIR).resolve("fabricated_backpacks_template_" + name);
            var json = pack.resolve("data/fabricated_backpacks/backpack_templates/" + name + ".json");
            String before = Files.readString(json);
            var location = new PackLocationInfo("file/" + pack.getFileName(), Component.literal("Export fixture"), PackSource.DEFAULT, Optional.empty());
            var resources = new PathPackResources(location, pack);
            var metadata = resources.getMetadataSection(PackMetadataSection.TYPE);
            helper.assertTrue(metadata != null, "Minecraft parses current-format pack.mcmeta without fallback metadata");
            try (var manager = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(resources))) {
                var loaded = BackpackTemplates.read(manager, helper.getLevel().registryAccess(), BackpackRegistry.id(name)).orElseThrow();
                helper.assertValueEqual(BagInventory.of(loaded.backpack()).getItem(7).getCount(), 1001, "Actual datapack resource loading retains enhanced quantities");
            }
            helper.assertValueEqual(command(author, "fb template export " + name), 0, "Existing export directories are never overwritten");
            helper.assertTrue(Files.readString(json).equals(before), "Refused export leaves the previous file byte-for-byte intact");
            try {
                BackpackTemplates.export(helper.getLevel().getServer(), name, "../escape");
                throw new AssertionError("Path traversal was accepted");
            } catch (IllegalArgumentException expected) { }
            helper.assertTrue(BackpackTemplates.names(helper.getLevel().getServer()).contains("fabricated_backpacks_tests:admin_fixture"), "Bundled datapack template is independently discoverable");
            helper.assertValueEqual(command(author, "fb template give fabricated_backpacks_tests:admin_fixture " + recipient.getGameProfile().getName()), 1, "Actual command resolves an explicitly namespaced datapack reference");
            BackpackTestSupport.assertStack(helper, BagInventory.of(bags(recipient).getFirst()).getItem(2), Items.DIAMOND, 43, "Datapack grant uses the decoded fixture, not the held bag");
            String wrongItem = "{\"format\":1,\"backpack\":{\"id\":\"minecraft:stone\",\"count\":1}}";
            try {
                BackpackTemplates.decode(helper.getLevel().registryAccess(), wrongItem);
                throw new AssertionError("Non-backpack template was accepted");
            } catch (IllegalStateException | IllegalArgumentException expected) { }
            helper.assertValueEqual(bags(recipient).size(), 1, "Invalid templates and failed exports cannot mutate recipients");
        } catch (java.io.IOException exception) { throw new AssertionError("Datapack export fixture failed", exception); }
        finally { ordinary(author); }
        helper.succeed();
    }

    public static void archiveAccessLifecycle(GameTestHelper helper) {
        ServerPlayer owner = BackpackTestSupport.player(helper);
        BagInventory bag = BackpackTestSupport.bag(BackpackTier.LEATHER);
        owner.getInventory().setItem(0, bag.stack());
        BackpackMenus.openInventory(owner, 0);
        helper.assertTrue(owner.containerMenu instanceof BackpackMenu, "A real backpack menu is open");
        BackpackMenu menu = (BackpackMenu)owner.containerMenu;
        menu.setCarried(new ItemStack(Items.EMERALD, 11));
        menu.clicked(0, 0, ClickType.PICKUP, owner);
        owner.closeContainer();
        helper.runAfterDelay(25, () -> {
            var saved = AdminSavedData.of(helper.getLevel().getServer()).archive(bag.identity()).orElseThrow();
            helper.assertTrue(saved.ownerId().equals(owner.getUUID().toString()), "Menu/periodic access hooks record the real owner");
            BackpackTestSupport.assertStack(helper, BagInventory.of(saved.backpack()).getItem(0), Items.EMERALD, 11, "Menu mutation is archived after the live inventory writeback");
            helper.succeed();
        });
    }
    public static void archiveDiskAndCleanup(GameTestHelper helper) {
        ServerPlayer author = BackpackTestSupport.player(helper);
        var server = helper.getLevel().getServer();
        var storage = server.overworld().getDataStorage();
        AdminSavedData original = AdminSavedData.of(server);
        AdminSavedData fixture = new AdminSavedData();
        storage.set("fabricated_backpacks_administration", fixture);
        operator(author);
        try {
            BagInventory empty = BackpackTestSupport.bag(BackpackTier.LEATHER);
            BagInventory occupied = BackpackTestSupport.bag(BackpackTier.LEATHER);
            occupied.setItem(5, new ItemStack(Items.DIAMOND, 37));
            BagInventory upgraded = BackpackTestSupport.bag(BackpackTier.LEATHER, UpgradeKind.PICKUP);
            BagInventory pending = BackpackTestSupport.bag(BackpackTier.LEATHER);
            pending.stack().set(WorldComponents.DEFERRED_LOOT, new WorldComponents.DeferredLoot(ResourceLocation.withDefaultNamespace("chests/spawn_bonus_chest"), 47, 1, 0));
            BagInventory ownedEmpty = BackpackTestSupport.bag(BackpackTier.LEATHER);
            for (BagInventory bag : List.of(empty, occupied, upgraded, pending)) BackpackArchives.record(helper.getLevel(), bag, null);
            BackpackArchives.record(helper.getLevel(), ownedEmpty, author);
            BackpackArchives.record(helper.getLevel(), ownedEmpty, null);
            var protectedEntry = fixture.archive(ownedEmpty.identity()).orElseThrow();
            try {
                fixture.record(new BackpackArchive(protectedEntry.identity(), "", "", protectedEntry.itemName(),
                        protectedEntry.bodyColor(), protectedEntry.trimColor(), protectedEntry.accessedAt(), protectedEntry.backpack()));
                throw new AssertionError("Player archive protection was removed through the data mutation API");
            } catch (IllegalArgumentException expected) { }
            helper.assertValueEqual(command(author, "fb cleanup nonplayer empty"), 1, "Only empty cleanup retains upgrades, items, deferred loot and every player archive");
            helper.assertTrue(fixture.archive(empty.identity()).isEmpty() && fixture.archive(pending.identity()).isPresent(), "Empty filtering uses persisted extra contents and unrolled loot");
            fixture.putTemplate("saved_fixture", occupied.stack(), false);
            var folder = Files.createTempDirectory(server.getWorldPath(LevelResource.ROOT), "fb-admin-save-");
            {
                DimensionDataStorage writer = new DimensionDataStorage(folder.toFile(), DataFixers.getDataFixer(), helper.getLevel().registryAccess());
                writer.set("fabricated_backpacks_administration", fixture);
                writer.save();
            }
            {
                DimensionDataStorage reader = new DimensionDataStorage(folder.toFile(), DataFixers.getDataFixer(), helper.getLevel().registryAccess());
                AdminSavedData loaded = reader.get(AdminSavedData.TYPE, "fabricated_backpacks_administration");
                helper.assertTrue(loaded != null, "Actual saved-data file reloads through Minecraft's registry-aware codec");
                helper.assertValueEqual(loaded.archives("").size(), 4, "Every retained archive survives disk save/load");
                helper.assertValueEqual(BagInventory.of(loaded.archive(occupied.identity()).orElseThrow().backpack()).getItem(5).getCount(), 37, "Archive item counts survive disk reload");
                helper.assertValueEqual(BagInventory.of(loaded.template("saved_fixture").orElseThrow().backpack()).getItem(5).getCount(), 37, "Local whole-bag templates survive in separate saved-data records");
                helper.assertTrue(loaded.archive(ownedEmpty.identity()).orElseThrow().playerBacked(), "Player protection is persisted, not an in-memory flag");
            }
            helper.assertValueEqual(command(author, "fabricatedbackpacks cleanup nonplayer"), 3, "Explicit unrestricted non-player cleanup removes only non-player archives");
            helper.assertTrue(fixture.archive(ownedEmpty.identity()).isPresent() && fixture.archives("").size() == 1, "Player-owned empty backups are still recoverable");
            helper.assertTrue(fixture.template("saved_fixture").isPresent(), "Archive cleanup cannot delete templates");
        } catch (java.io.IOException exception) { throw new AssertionError("Saved-data fixture failed", exception); }
        finally { storage.set("fabricated_backpacks_administration", original); ordinary(author); }
        helper.succeed();
    }

    public static void wholeTemplateCommands(GameTestHelper helper) {
        ServerPlayer author = BackpackTestSupport.player(helper);
        ServerPlayer recipient = BackpackTestSupport.player(helper);
        BagInventory bag = BackpackTestSupport.bag(BackpackTier.IRON, UpgradeKind.STACK_UPGRADE_TIER_4);
        bag.setItem(4, new ItemStack(Items.EMERALD, 700));
        author.getInventory().setItem(0, bag.stack());
        String name = name("whole_");
        operator(author);
        try {
            helper.assertValueEqual(command(author, "fb template create " + name), 1, "Template is created from the actual held bag");
            bag.setItem(4, new ItemStack(Items.DIAMOND, 5));
            helper.assertValueEqual(command(author, "fb template create " + name), 0, "Existing local templates require explicit overwrite");
            AdminSavedData data = AdminSavedData.of(helper.getLevel().getServer());
            helper.assertTrue(BagInventory.of(data.template(name).orElseThrow().backpack()).getItem(4).is(Items.EMERALD), "Rejected overwrite preserves the earlier complete snapshot");
            helper.assertValueEqual(command(author, "fabricatedbackpacks template create " + name + " overwrite"), 1, "Explicit overwrite replaces the template");
            helper.assertValueEqual(command(author, "fb template give " + name + " " + recipient.getGameProfile().getName()), 1, "Actual grant resolves a local template");
            ItemStack delivered = bags(recipient).getFirst();
            helper.assertFalse(delivered.get(BagComponents.IDENTITY).equals(bag.identity()), "Template grants do not reuse the source UUID");
            BackpackTestSupport.assertStack(helper, BagInventory.of(delivered).getItem(4), Items.DIAMOND, 5, "The replacement template is the one granted");
            BagInventory.of(delivered).setItem(4, ItemStack.EMPTY);
            BackpackTestSupport.assertStack(helper, bag.getItem(4), Items.DIAMOND, 5, "Giving a template never changes held storage");
            helper.assertValueEqual(command(author, "fb template delete " + name), 1, "Local template deletion is explicit");
            helper.assertValueEqual(command(author, "fb template give " + name + " " + recipient.getGameProfile().getName()), 0, "Deleted template cannot grant a replacement bag");
            helper.assertValueEqual(bags(recipient).size(), 1, "Failed template grant leaves existing inventory unchanged");
        } finally { ordinary(author); }
        helper.succeed();
    }

    public static void dynamicTemplateCommands(GameTestHelper helper) {
        ServerPlayer author = BackpackTestSupport.player(helper);
        author.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 13));
        String name = name("dynamic_");
        operator(author);
        try {
            helper.assertValueEqual(command(author, "fb dynamic start iron"), 1, "Draft starts from a tier");
            helper.assertValueEqual(command(author, "fb dynamic item minecraft:diamond 200 0"), 200, "Oversized request is deferred until upgrade selection");
            helper.assertValueEqual(command(author, "fb dynamic end " + name), 0, "Unplaced requests block template saving");
            helper.assertTrue(AdminSavedData.of(helper.getLevel().getServer()).template(name).isEmpty(), "A partial draft never silently becomes a complete template");
            var draft = BackpackAdmin.building(author).orElseThrow();
            helper.assertValueEqual(draft.build(author).leftoverCount(), 136L, "Leftovers report the exact quantity beyond the unupgraded slot");
            helper.assertValueEqual(command(author, "fb dynamic start gold"), 0, "Starting a second draft cannot discard pending requests");
            helper.assertValueEqual(command(author, "fb dynamic item minecraft:stone 1 999"), 0, "Out-of-range explicit slot is rejected without changing the draft");
            helper.assertValueEqual(command(author, "fb dynamic upgrade minecraft:stone"), 0, "Ordinary items cannot masquerade as upgrades");
            helper.assertValueEqual(command(author, "fb dynamic upgrade fabricated_backpacks:stack_upgrade_tier_2"), 1, "Adding a compatible upgrade enables deferred capacity");
            command(author, "fb dynamic item minecraft:emerald 500");
            command(author, "fb dynamic item minecraft:stone 50 1");
            helper.assertValueEqual(command(author, "fb dynamic end " + name), 1, "Complete draft is saved after replaying every request");
            BagInventory built = BagInventory.of(AdminSavedData.of(helper.getLevel().getServer()).template(name).orElseThrow().backpack());
            BackpackTestSupport.assertStack(helper, built.getItem(0), Items.DIAMOND, 200, "Explicit slot respects enhanced capacity");
            BackpackTestSupport.assertStack(helper, built.getItem(1), Items.STONE, 50, "Explicit slots take priority over earlier automatic requests");
            helper.assertValueEqual(BackpackTestSupport.count(built, Items.EMERALD), 500, "Automatic placement conserves every requested item");
            helper.assertTrue(BackpackAdmin.building(author).isEmpty(), "Successful save ends the draft");
            helper.assertValueEqual(command(author, "fb dynamic base " + name), 1, "A saved template can be the next draft's base");
            command(author, "fb dynamic item minecraft:coal 2147483647");
            var large = BackpackAdmin.building(author).orElseThrow().build(author);
            long placed = BagInventory.of(large.backpack()).stack().get(BagComponents.CONTENTS).entries().stream()
                    .filter(entry -> entry.create().is(Items.COAL)).mapToLong(InventorySnapshot.Entry::count).sum();
            helper.assertValueEqual(placed + large.leftoverCount(), (long)Integer.MAX_VALUE, "A maximum integer request cannot overflow or lose its excess");
            helper.assertValueEqual(command(author, "fb dynamic cancel"), 1, "Draft cancellation is explicit");
            BackpackTestSupport.assertStack(helper, author.getMainHandItem(), Items.DIAMOND, 13, "Virtual authoring never consumes or replaces the live held item");
        } finally { ordinary(author); }
        helper.succeed();
    }
}
