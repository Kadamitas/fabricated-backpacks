package com.kadamitas.fabricatedbackpacks.gametest;

import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.block.BackpackBlockEntity;
import com.kadamitas.fabricatedbackpacks.equipment.BackpackEquipment;
import com.kadamitas.fabricatedbackpacks.gameplay.BackpackRuntime;
import com.kadamitas.fabricatedbackpacks.menu.BackpackMenu;
import com.kadamitas.fabricatedbackpacks.menu.BackpackMenus;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.storage.BagComponents;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.storage.InventorySnapshot;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;

import java.util.HashSet;
import java.util.List;

import static com.kadamitas.fabricatedbackpacks.gametest.BackpackTestSupport.*;

final class StorageGameTests {
    private StorageGameTests() {}

    static void tierComponentRoundTrips(GameTestHelper helper) {
        var identities = new HashSet<String>();
        for (BackpackTier tier : BackpackTier.values()) {
            BagInventory original = bag(tier, UpgradeKind.STACK_UPGRADE_TIER_4);
            original.setItem(0, new ItemStack(Items.COBBLESTONE, 999));
            ItemStack tool = new ItemStack(Items.DIAMOND_PICKAXE);
            tool.setDamageValue(27);
            tool.set(DataComponents.CUSTOM_NAME, Component.literal("Survey pick " + tier.id()));
            original.setItem(tier.slots() - 1, tool);
            original.stack().set(DataComponents.CUSTOM_NAME, Component.literal("Expedition " + tier.id()));
            original.remember(1, new ItemStack(Items.DIAMOND));
            original.toggleNoSort(tier.slots() - 1);
            original.dye(0x234567, 0xfedcba);
            original.updateSettings(state -> state.putBoolean("memory_components", true));
            original.updateSettings(upgrade(original, 0), state -> state.putLong("test_counter", 9000000000L));
            ItemStack encodedOriginal = original.stack().copy();
            BagInventory loaded = BagInventory.of(roundTrip(helper.getLevel(), encodedOriginal));
            helper.assertValueEqual(loaded.getContainerSize(), tier.slots(), "Storage slot count survives " + tier);
            helper.assertValueEqual(loaded.upgrades().getContainerSize(), tier.upgradeSlots(), "Upgrade slot count survives " + tier);
            helper.assertValueEqual(loaded.getItem(0).getCount(), 999, "Overstack count survives the actual ItemStack codec for " + tier);
            helper.assertValueEqual(loaded.capacity(new ItemStack(Items.COBBLESTONE)), 1024, "Stack multiplier survives " + tier);
            helper.assertTrue(identities.add(loaded.identity()), "Every created bag has its own identity");
            helper.assertValueEqual(loaded.settings(upgrade(loaded, 0)).getLongOr("test_counter", 0), 9000000000L, "Upgrade settings retain wide values");
            helper.assertTrue(!loaded.canPlaceItem(1, new ItemStack(Items.DIRT)), "Remembered slot is enforced after loading");
            assertStack(helper, loaded.stack(), encodedOriginal, "All typed components and item data survive " + tier);
        }
        helper.assertValueEqual(identities.size(), 6, "All six actual tiers were exercised");

        BagInventory records = bag(BackpackTier.NETHERITE, UpgradeKind.ADVANCED_JUKEBOX, UpgradeKind.ADVANCED_ALCHEMY);
        var available = BuiltInRegistries.ITEM.stream().map(Item::getDefaultInstance)
                .filter(item -> item.has(DataComponents.JUKEBOX_PLAYABLE)).limit(12).toList();
        helper.assertValueEqual(available.size(), 12, "The auxiliary roundtrip contains twelve actual records");
        for (int slot = 0; slot < 12; slot++) records.upgradeInventory(upgrade(records, 0)).setItem(slot, available.get(slot).copy());
        records.updateSettings(upgrade(records, 0), state -> { state.putBoolean("shuffle", true); state.putString("repeat", "ALL"); });
        records.setFilter(upgrade(records, 1), 7, new ItemStack(Items.GOLDEN_APPLE));
        records.updateSettings(upgrade(records, 1), state -> { state.putString("alchemy_condition_7", "HURT"); state.putInt("alchemy_health_7", 35); });
        BagInventory reloaded = BagInventory.of(roundTrip(helper.getLevel(), records.stack()));
        for (int slot = 0; slot < 12; slot++) assertStack(helper, reloaded.upgradeInventory(upgrade(reloaded, 0)).getItem(slot), available.get(slot), "Disc slot " + slot + " persists independently");
        helper.assertTrue(reloaded.ghost(upgrade(reloaded, 1), 0).isEmpty(), "Sparse ghost rows are not compacted on reload");
        helper.assertTrue(reloaded.ghost(upgrade(reloaded, 1), 7).is(Items.GOLDEN_APPLE), "Final advanced alchemy ghost retains its index");
        assertStack(helper, reloaded.stack(), records.stack(), "Auxiliary inventories and preferences survive together");

        var ops = RegistryOps.create(NbtOps.INSTANCE, helper.getLevel().registryAccess());
        var item = ItemStackTemplate.fromNonEmptyStack(new ItemStack(Items.DIAMOND));
        var duplicate = new InventorySnapshot(2, List.of(new InventorySnapshot.Entry(0, item, 1), new InventorySnapshot.Entry(0, item, 2)));
        var outside = new InventorySnapshot(1, List.of(new InventorySnapshot.Entry(1, item, 1)));
        helper.assertTrue(InventorySnapshot.CODEC.encodeStart(ops, duplicate).error().isPresent(), "Duplicate saved slot indices fail the real codec");
        helper.assertTrue(InventorySnapshot.CODEC.encodeStart(ops, outside).error().isPresent(), "Out-of-bounds saved slot indices fail the real codec");
        helper.succeed();
    }

    static void upgradeCapacityAndNesting(GameTestHelper helper) {
        BagInventory stacked = bag(BackpackTier.NETHERITE, UpgradeKind.STACK_UPGRADE_TIER_4, UpgradeKind.STACK_UPGRADE_TIER_4, UpgradeKind.STACK_UPGRADE_TIER_4);
        stacked.setItem(0, new ItemStack(Items.DIAMOND, 200000));
        helper.assertValueEqual(stacked.capacity(new ItemStack(Items.DIAMOND)), 262144, "Three multiplicative stack upgrades use the full supported count");
        helper.assertValueEqual(BagInventory.of(roundTrip(helper.getLevel(), stacked.stack())).getItem(0).getCount(), 200000, "Large stack survives registry-aware save and load");
        helper.assertFalse(stacked.canRemoveUpgrade(2), "Removing capacity while oversized contents exist is rejected");
        helper.assertFalse(stacked.upgrades().canTakeItem(new SimpleContainer(1), 2, stacked.upgrades().getItem(2)), "Container removal uses the same capacity guard");
        helper.assertFalse(stacked.canInstall(2, new ItemStack(BackpackRegistry.item(UpgradeKind.STACK_DOWNGRADE_TIER_3))), "Replacing capacity with a downgrade cannot truncate items");
        helper.assertValueEqual(stacked.getItem(0).getCount(), 200000, "Rejected transitions preserve every item");
        stacked.setItem(0, new ItemStack(Items.DIAMOND, 16000));
        helper.assertTrue(stacked.canRemoveUpgrade(2), "Upgrade removal becomes valid when all stacks fit");
        stacked.upgrades().removeItem(2, 1);
        helper.assertValueEqual(stacked.capacity(new ItemStack(Items.DIAMOND)), 16384, "Committed removal changes the effective capacity");

        BagInventory limits = bag(BackpackTier.NETHERITE, UpgradeKind.PICKUP);
        helper.assertFalse(limits.canInstall(1, new ItemStack(BackpackRegistry.item(UpgradeKind.ADVANCED_PICKUP))), "Basic and advanced variants share their family limit");
        helper.assertTrue(limits.canInstall(0, new ItemStack(BackpackRegistry.item(UpgradeKind.ADVANCED_PICKUP))), "Replacing the same family in its own slot remains valid");
        helper.assertFalse(limits.canInstall(1, new ItemStack(BackpackRegistry.item(UpgradeKind.MAGNET), 2)), "A stacked upgrade cannot be installed");
        helper.assertFalse(limits.canInstall(1, new ItemStack(Items.DIAMOND)), "Ordinary items cannot enter upgrade slots");
        limits.setItem(11, new ItemStack(Items.EMERALD));
        helper.assertFalse(limits.canInstall(1, new ItemStack(BackpackRegistry.item(UpgradeKind.TANK))), "Tank installation cannot hide occupied columns");
        limits.removeItem(11, 1);
        limits.upgrades().setItem(1, new ItemStack(BackpackRegistry.item(UpgradeKind.TANK)));
        helper.assertTrue(limits.blocked(10) && limits.blocked(11) && limits.blocked(118), "Tank reserves its two full columns");
        helper.assertFalse(limits.canPlaceItem(10, new ItemStack(Items.DIAMOND)), "Reserved columns reject inventory writes");
        limits.setItem(9, new ItemStack(Items.EMERALD));
        helper.assertFalse(limits.canInstall(2, new ItemStack(BackpackRegistry.item(UpgradeKind.BATTERY))), "Battery cannot reserve a further occupied column");
        helper.assertValueEqual(count(limits, Items.EMERALD), 1, "Failed reserve operation retains contents");

        BagInventory outer = bag(BackpackTier.NETHERITE);
        BagInventory child = bag(BackpackTier.LEATHER);
        child.setItem(0, new ItemStack(Items.DIAMOND, 11));
        helper.assertFalse(outer.canPlaceItem(0, child.stack()), "Backpacks require inception before accepting a child");
        outer.upgrades().setItem(0, new ItemStack(BackpackRegistry.item(UpgradeKind.INCEPTION)));
        helper.assertTrue(outer.canPlaceItem(0, child.stack()), "One non-nesting child is accepted with inception");
        helper.assertFalse(outer.canPlaceItem(1, outer.stack().copy()), "Copied identity cannot contain itself");
        outer.setItem(0, child.stack());
        helper.assertFalse(outer.canRemoveUpgrade(0), "Inception cannot be removed while a child remains stored");
        BagInventory loaded = BagInventory.of(roundTrip(helper.getLevel(), outer.stack()));
        helper.assertValueEqual(count(BagInventory.of(loaded.getItem(0)), Items.DIAMOND), 11, "Nested contents retain their exact owned count");
        BagInventory deeper = bag(BackpackTier.NETHERITE, UpgradeKind.INCEPTION);
        deeper.setItem(0, bag(BackpackTier.LEATHER).stack());
        helper.assertFalse(outer.canPlaceItem(1, deeper.stack()), "Nesting depth cannot be extended through another inception bag");
        deeper.upgrades().removeItem(0, 1);
        helper.assertFalse(outer.canPlaceItem(1, deeper.stack()), "Removing a child's inception marker cannot bypass depth validation");
        helper.succeed();
    }

    static void memorySortingAndFilters(GameTestHelper helper) {
        BagInventory inventory = bag(BackpackTier.NETHERITE, UpgradeKind.FILTER);
        ItemStack named = new ItemStack(Items.DIAMOND, 17);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Reserved diamonds"));
        inventory.remember(0, named);
        helper.assertTrue(inventory.canPlaceItem(0, new ItemStack(Items.DIAMOND)), "Default memory matches by item");
        inventory.updateSettings(state -> state.putBoolean("memory_components", true));
        helper.assertFalse(inventory.canPlaceItem(0, new ItemStack(Items.DIAMOND)), "Component-sensitive memory refuses different metadata");
        helper.assertTrue(inventory.canPlaceItem(0, named), "Exact remembered components still match");
        inventory.setItem(0, named.copy());
        inventory.setItem(5, new ItemStack(Items.EMERALD, 3));
        inventory.toggleNoSort(5);
        inventory.setItem(1, new ItemStack(Items.COBBLESTONE, 20));
        inventory.setItem(2, new ItemStack(Items.COBBLESTONE, 30));
        ItemStack one = new ItemStack(Items.DIAMOND_PICKAXE);
        one.setDamageValue(4);
        ItemStack two = one.copy();
        two.setDamageValue(5);
        inventory.setItem(3, one);
        inventory.setItem(4, two);
        for (String order : List.of("name", "count", "mod", "tags")) {
            inventory.sort(order);
            assertStack(helper, inventory.getItem(0), named, "Memory fixes its physical position during " + order + " sorting");
            helper.assertTrue(inventory.getItem(5).is(Items.EMERALD) && inventory.getItem(5).getCount() == 3, "No-sort slot remains stationary");
            helper.assertValueEqual(count(inventory, Items.COBBLESTONE), 50, "Sort combines compatible items without loss");
            helper.assertValueEqual(count(inventory, Items.DIAMOND_PICKAXE), 2, "Different tool damage is never merged away");
        }
        var before = inventory.stack().copy();
        helper.assertTrue(inventory.insert(new ItemStack(Items.DIRT, 64), true).isEmpty(), "Simulation reports available capacity");
        assertStack(helper, inventory.stack(), before, "Simulation leaves every persistent component unchanged");
        BagInventory reloaded = BagInventory.of(roundTrip(helper.getLevel(), inventory.stack()));
        helper.assertValueEqual(reloaded.settings().getIntArray("no_sort").orElseThrow()[0], 5, "No-sort survives a saved item roundtrip");
        helper.assertFalse(reloaded.canPlaceItem(0, new ItemStack(Items.DIRT)), "Memory survives a saved item roundtrip");

        var filter = upgrade(inventory, 0);
        inventory.setFilter(filter, 0, new ItemStack(Items.DIAMOND, 64));
        inventory.setFilter(filter, 1, new ItemStack(Items.DIAMOND));
        helper.assertValueEqual(inventory.ghost(filter, 0).getCount(), 1, "Ghost exemplar stores one descriptive item");
        helper.assertTrue(inventory.ghost(filter, 1).isEmpty(), "Duplicate component-identical ghosts are rejected");
        helper.assertFalse(inventory.canPlaceItem(1, new ItemStack(Items.DIAMOND)), "Default BLOCK filter governs container input");
        helper.assertFalse(inventory.canTakeItem(new SimpleContainer(1), 0, named), "Default BOTH direction also governs output");
        inventory.updateSettings(filter, state -> state.putString("filter_direction", "INPUT"));
        helper.assertTrue(inventory.canTakeItem(new SimpleContainer(1), 0, named), "Input-only filter allows output");
        inventory.updateSettings(filter, state -> state.putBoolean("enabled", false));
        helper.assertTrue(inventory.canPlaceItem(1, new ItemStack(Items.DIAMOND)), "Disabling the filter restores normal insertion");
        helper.assertValueEqual(count(inventory, Items.DIAMOND), 17, "Ghost edits never consume physical storage items");
        inventory.remember(0, ItemStack.EMPTY);
        helper.assertTrue(inventory.canPlaceItem(0, new ItemStack(Items.DIRT)), "Clearing memory removes its placement restriction");
        helper.succeed();
    }

    static void itemStorageRollbackAndFilters(GameTestHelper helper) {
        BlockPos position = helper.absolutePos(new BlockPos(2, 1, 2));
        helper.getLevel().setBlockAndUpdate(position, BackpackRegistry.block(BackpackTier.NETHERITE).defaultBlockState());
        var block = (BackpackBlockEntity) helper.getLevel().getBlockEntity(position);
        block.setStack(bag(BackpackTier.NETHERITE, UpgradeKind.STACK_UPGRADE_TIER_1, UpgradeKind.FILTER, UpgradeKind.TANK).stack());
        BagInventory inventory = block.inventory();
        inventory.setItem(0, new ItemStack(Items.DIAMOND, 100));
        for (int slot = 1; slot < inventory.getContainerSize(); slot++) if (!inventory.blocked(slot)) inventory.setItem(slot, new ItemStack(Items.DIRT, 64));
        var filter = upgrade(inventory, 1);
        inventory.setFilter(filter, 0, new ItemStack(Items.DIAMOND));
        inventory.updateSettings(filter, state -> state.putString("filter_mode", "ALLOW"));
        var storage = ItemStorage.SIDED.find(helper.getLevel(), position, Direction.NORTH);
        helper.assertTrue(storage != null, "Placed bag exposes the registered public sided item API");
        ItemStack before = inventory.stack().copy();
        try (Transaction transaction = Transaction.openOuter()) {
            helper.assertValueEqual(storage.insert(ItemVariant.of(Items.EMERALD), 3, transaction), 0L, "Sided storage respects the ordinary upgrade filter");
            helper.assertValueEqual(storage.insert(ItemVariant.of(Items.DIAMOND), 40, transaction), 28L, "Partial insertion uses upgraded capacity without entering blocked columns");
        }
        helper.assertValueEqual(count(inventory, Items.DIAMOND), 100, "An aborted API insertion restores the live inventory");
        assertStack(helper, inventory.stack(), before, "An aborted API insertion also restores its persistent component snapshot");
        try (Transaction transaction = Transaction.openOuter()) {
            helper.assertValueEqual(storage.insert(ItemVariant.of(Items.DIAMOND), 40, transaction), 28L, "A committed partial API insertion reports the exact moved amount");
            transaction.commit();
        }
        helper.assertValueEqual(count(inventory, Items.DIAMOND), 128, "Commit retains exactly the accepted count");
        ItemStack committed = inventory.stack().copy();
        try (Transaction outer = Transaction.openOuter()) {
            helper.assertValueEqual(storage.extract(ItemVariant.of(Items.DIAMOND), 30, outer), 30L, "Sided extraction reads the actual upgraded stack");
            try (Transaction inner = outer.openNested()) {
                helper.assertValueEqual(storage.insert(ItemVariant.of(Items.DIAMOND), 2, inner), 2L, "Nested transaction sees its parent's provisional inventory");
                inner.commit();
            }
        }
        assertStack(helper, inventory.stack(), committed, "Aborting the parent rolls back a committed nested transaction and all persisted data");
        try (Transaction transaction = Transaction.openOuter()) {
            helper.assertValueEqual(storage.extract(ItemVariant.of(Items.DIAMOND), 9, transaction), 9L, "Committed extraction returns the exact removed amount");
            transaction.commit();
        }
        helper.assertValueEqual(count(BagInventory.of(roundTrip(helper.getLevel(), inventory.stack())), Items.DIAMOND), 119, "Committed item API writes survive the actual item codec");
        inventory.updateSettings(filter, state -> { state.putString("filter_mode", "BLOCK"); state.putString("filter_direction", "OUTPUT"); });
        ItemStack locked = inventory.stack().copy();
        try (Transaction transaction = Transaction.openOuter()) {
            helper.assertValueEqual(storage.extract(ItemVariant.of(Items.DIAMOND), 119, transaction), 0L, "Output-only filter also governs public storage API extraction");
            transaction.commit();
        }
        assertStack(helper, inventory.stack(), locked, "Filtered extraction has no persistent side effects");
        helper.succeed();
    }

    static void sharedInventoryHandles(GameTestHelper helper) {
        var player = player(helper);
        BagInventory source = bag(BackpackTier.NETHERITE, UpgradeKind.ADVANCED_PICKUP, UpgradeKind.ADVANCED_JUKEBOX);
        player.getInventory().setItem(0, source.stack());
        BagInventory warm = BackpackRuntime.carried(player).getFirst();
        BackpackMenus.openInventory(player, 0);
        var menu = (BackpackMenu) player.containerMenu;
        helper.assertTrue(menu.bag() == warm && warm == BagInventory.of(source.stack()), "Runtime, menu, and direct access share one handle for the actual item object");
        menu.bag().setItem(0, new ItemStack(Items.DIAMOND, 37));
        menu.bag().setFilter(upgrade(menu.bag(), 0), 15, new ItemStack(Items.EMERALD));
        menu.bag().updateSettings(upgrade(menu.bag(), 0), settings -> settings.putString("filter_mode", "ALLOW"));
        menu.bag().upgradeInventory(upgrade(menu.bag(), 1)).setItem(11, new ItemStack(Items.MUSIC_DISC_13));
        menu.bag().updateSettings(upgrade(menu.bag(), 1), settings -> settings.putBoolean("shuffle", true));
        player.closeContainer();
        BackpackRuntime.carried(player).getFirst().save();
        BagInventory loaded = BagInventory.of(roundTrip(helper.getLevel(), source.stack()));
        helper.assertValueEqual(count(loaded, Items.DIAMOND), 37, "A warm runtime handle cannot overwrite closed-menu item changes");
        helper.assertTrue(loaded.ghost(upgrade(loaded, 0), 15).is(Items.EMERALD), "Closing a menu retains positional filter changes");
        helper.assertValueEqual(loaded.settings(upgrade(loaded, 0)).getStringOr("filter_mode", ""), "ALLOW", "Closing a menu retains upgrade preferences");
        helper.assertTrue(loaded.upgradeInventory(upgrade(loaded, 1)).getItem(11).is(Items.MUSIC_DISC_13), "Closing a menu retains the twelfth physical record");
        helper.assertTrue(loaded.settings(upgrade(loaded, 1)).getBooleanOr("shuffle", false), "Closing a menu retains playback preference changes");
        BagInventory copy = BagInventory.of(source.stack().copy());
        helper.assertTrue(copy != warm && copy.identity().equals(warm.identity()), "Codec and transaction copies never alias live state merely because their saved UUID agrees");
        copy.setItem(0, new ItemStack(Items.DIAMOND, 2));
        copy.upgradeInventory(upgrade(copy, 1)).removeItem(11, 1);
        helper.assertValueEqual(count(warm, Items.DIAMOND), 37, "Editing a simulation copy cannot mutate physical source contents");
        helper.assertTrue(warm.upgradeInventory(upgrade(warm, 1)).getItem(11).is(Items.MUSIC_DISC_13), "Simulation copies also isolate nested auxiliary inventory changes");

        player.getInventory().setItem(0, ItemStack.EMPTY);
        BackpackEquipment.set(player, source.stack());
        BackpackRuntime.carried(player);
        BackpackMenus.openEquipped(player);
        var equipped = (BackpackMenu) player.containerMenu;
        equipped.bag().setItem(1, new ItemStack(Items.GOLD_INGOT, 23));
        equipped.persist();
        player.closeContainer();
        BackpackRuntime.carried(player).getFirst().save();
        helper.assertValueEqual(count(BagInventory.of(BackpackEquipment.get(player)), Items.GOLD_INGOT), 23, "Equipped attachment copies retain edits after close and the next runtime access");
        helper.succeed();
    }
}
