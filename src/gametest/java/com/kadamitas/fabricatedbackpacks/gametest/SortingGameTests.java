package com.kadamitas.fabricatedbackpacks.gametest;

import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.settings.SettingsRuntime;
import com.kadamitas.fabricatedbackpacks.storage.BagComponents;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.kadamitas.fabricatedbackpacks.gametest.BackpackTestSupport.*;

/** Sorting uses the real inventory, exact component identities, and a complete no-loss destination plan. */
public final class SortingGameTests {
    private SortingGameTests() {}

    private static Map<ItemVariant, Long> contents(BagInventory bag) {
        Map<ItemVariant, Long> amounts = new HashMap<>();
        for (ItemStack item : bag.getItems()) if (!item.isEmpty()) amounts.merge(ItemVariant.of(item), (long) item.getCount(), Math::addExact);
        return Map.copyOf(amounts);
    }

    private static ItemStack named(ItemStack stack, String name) {
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return stack;
    }

    public static void memoryFirstSortingAndConservation(GameTestHelper helper) {
        BagInventory bag = bag(BackpackTier.GOLD, UpgradeKind.STACK_UPGRADE_TIER_1);
        bag.remember(70, new ItemStack(Items.COBBLESTONE));
        bag.remember(71, new ItemStack(Items.COBBLESTONE));
        bag.remember(72, new ItemStack(Items.EMERALD));
        bag.remember(73, new ItemStack(Items.DIAMOND));
        bag.setItem(70, new ItemStack(Items.COBBLESTONE, 7));
        bag.setItem(0, new ItemStack(Items.COBBLESTONE, 100));
        bag.setItem(1, new ItemStack(Items.COBBLESTONE, 95));
        bag.setItem(2, new ItemStack(Items.COBBLESTONE, 60));
        bag.setItem(3, new ItemStack(Items.EMERALD, 33));
        bag.setItem(4, new ItemStack(Items.OAK_PLANKS, 27));
        bag.setItem(5, new ItemStack(Items.EMERALD, 9));
        bag.toggleNoSort(5);
        var before = contents(bag);
        var memory = bag.stack().get(BagComponents.MEMORY);
        var settings = bag.stack().get(BagComponents.SETTINGS);
        for (String order : List.of("name", "count", "mod", "tags")) {
            bag.sort(order);
            assertStack(helper, bag.getItem(70), Items.COBBLESTONE, 128, "The first memory fills before lower ordinary slots during " + order);
            assertStack(helper, bag.getItem(71), Items.COBBLESTONE, 128, "Compatible excess fills the next reserved cell");
            assertStack(helper, bag.getItem(72), Items.EMERALD, 33, "An empty remembered cell receives available matching items");
            helper.assertTrue(bag.getItem(73).isEmpty(), "An unmatched memory cell remains empty");
            assertStack(helper, bag.getItem(5), Items.EMERALD, 9, "Excluded cells never donate to a memory reservation");
            helper.assertTrue(contents(bag).equals(before), "Sorting conserves exact variants and full counts for " + order);
            helper.assertTrue(memory.equals(bag.stack().get(BagComponents.MEMORY)) && settings.equals(bag.stack().get(BagComponents.SETTINGS)),
                    "Sorting does not rewrite reservations or exclusion preferences");
            ItemStack sorted = bag.stack().copy();
            bag.sort(order);
            assertStack(helper, bag.stack(), sorted, "Repeating the same sort is stable");
        }
        BagInventory saved = BagInventory.of(roundTrip(helper.getLevel(), bag.stack()));
        helper.assertTrue(contents(saved).equals(before), "The sorted state preserves counts through the real item codec");
        helper.succeed();
    }

    public static void memoryComponentsAndInheritedPreferences(GameTestHelper helper) {
        var player = player(helper);
        BagInventory defaults = bag(BackpackTier.LEATHER);
        defaults.updateSettings(tag -> tag.putBoolean("memory_components", true));
        helper.assertTrue(SettingsRuntime.action(defaults, player, "defaults_save", 0, ""), "A player can save component-sensitive memory as a default");
        BagInventory bag = bag(BackpackTier.LEATHER);
        ItemStack named = named(new ItemStack(Items.DIAMOND, 23), "Reserved cut");
        ItemStack plain = new ItemStack(Items.DIAMOND, 19);
        ItemStack damageSeven = new ItemStack(Items.DIAMOND_PICKAXE);
        damageSeven.setDamageValue(7);
        ItemStack damageEight = damageSeven.copy();
        damageEight.setDamageValue(8);
        bag.remember(20, named);
        bag.remember(21, plain);
        bag.remember(22, damageSeven);
        bag.setItem(20, named.copyWithCount(7));
        bag.setItem(0, named.copyWithCount(16));
        bag.setItem(1, plain);
        bag.setItem(2, damageEight);
        bag.setItem(3, damageSeven);
        var before = contents(bag);
        helper.assertFalse(bag.settings().contains("memory_components"), "This bag relies on the player's inherited preference");
        bag.sort("name", player);
        assertStack(helper, bag.getItem(20), named, "Existing reserved components are retained and topped up");
        assertStack(helper, bag.getItem(21), plain, "A plain reservation cannot absorb the named variant");
        assertStack(helper, bag.getItem(22), damageSeven, "Exact memory distinguishes tool damage");
        helper.assertTrue(contents(bag).equals(before), "Component-sensitive placement preserves every distinct item");

        BagInventory override = bag(BackpackTier.LEATHER);
        override.updateSettings(tag -> tag.putBoolean("memory_components", false));
        override.remember(24, named);
        override.setItem(0, plain.copyWithCount(11));
        override.sort("count", player);
        assertStack(helper, override.getItem(24), Items.DIAMOND, 11, "A backpack override can choose item-only matching over the player default");
        helper.assertTrue(override.stack().get(BagComponents.MEMORY).entries().getFirst().create().has(DataComponents.CUSTOM_NAME),
                "Item-only placement never changes the stored ghost's components");

        BagInventory variant = bag(BackpackTier.LEATHER);
        variant.remember(24, new ItemStack(Items.DIAMOND));
        ItemStack existing = named(new ItemStack(Items.DIAMOND, 7), "Z existing");
        ItemStack other = named(new ItemStack(Items.DIAMOND, 29), "A different");
        variant.setItem(24, existing.copy());
        variant.setItem(0, other.copy());
        variant.setItem(1, existing.copyWithCount(13));
        variant.sort("name");
        assertStack(helper, variant.getItem(24), existing.copyWithCount(20), "Item-only memory prefers the existing component variant instead of exchanging it for an earlier name");
        helper.assertValueEqual(contents(variant).get(ItemVariant.of(other)), 29L, "A different named stack is not merged into a reservation");
        helper.succeed();
    }

    public static void sortingProtectedCellsAndNestedContents(GameTestHelper helper) {
        BagInventory bag = bag(BackpackTier.NETHERITE, UpgradeKind.TANK, UpgradeKind.INCEPTION);
        bag.setItem(0, new ItemStack(Items.DIAMOND, 7));
        bag.toggleNoSort(0);
        bag.toggleNoSort(4);
        bag.updateSettings(tag -> tag.putIntArray("captured_slots", new int[]{2, 3}));
        bag.setItem(3, new ItemStack(Items.GOLD_INGOT, 9));
        bag.setItem(10, new ItemStack(Items.DIAMOND, 11));
        bag.remember(0, new ItemStack(Items.DIAMOND));
        bag.remember(2, new ItemStack(Items.EMERALD));
        bag.remember(4, new ItemStack(Items.COBBLESTONE));
        bag.remember(10, new ItemStack(Items.DIAMOND));
        bag.remember(25, new ItemStack(Items.DIAMOND));
        bag.setItem(1, new ItemStack(Items.EMERALD, 17));
        bag.setItem(5, new ItemStack(Items.EMERALD, 20));
        bag.setItem(6, new ItemStack(Items.DIAMOND, 19));
        BagInventory child = bag(BackpackTier.GOLD, UpgradeKind.STACK_UPGRADE_TIER_1);
        child.setItem(0, named(new ItemStack(Items.AMETHYST_SHARD, 101), "Nested cargo"));
        bag.setItem(7, child.stack());
        bag.remember(24, child.stack());
        var before = contents(bag);
        var settings = bag.stack().get(BagComponents.SETTINGS);
        ItemStack stationary = bag.getItem(0);
        bag.sort("count");
        helper.assertTrue(bag.getItem(0) == stationary, "The actual excluded ItemStack object stays in place");
        helper.assertTrue(bag.getItem(2).isEmpty() && bag.getItem(4).isEmpty(), "Captured and excluded empty cells remain empty even with matching memory");
        assertStack(helper, bag.getItem(3), Items.GOLD_INGOT, 9, "An existing captured-cell record is never moved or deleted");
        assertStack(helper, bag.getItem(10), Items.DIAMOND, 11, "Resource-reserved columns are not touched by sorting");
        assertStack(helper, bag.getItem(25), Items.DIAMOND, 19, "An ordinary memory cannot steal items from protected cells");
        assertStack(helper, bag.getItem(24), child.stack(), "A nested carrier retains its exact identity, upgrades and full contents");
        helper.assertTrue(contents(bag).equals(before), "Protected and movable exact variants are all conserved");
        helper.assertTrue(settings.equals(bag.stack().get(BagComponents.SETTINGS)), "Capture occupancy and no-sort settings remain unchanged");
        BagInventory saved = BagInventory.of(roundTrip(helper.getLevel(), bag.stack()));
        helper.assertValueEqual(BagInventory.of(saved.getItem(24)).getItem(0).getCount(), 101, "A moved child preserves enhanced nested counts through its parent codec");
        helper.succeed();
    }

    public static void sortingCapacityAndAtomicFailure(GameTestHelper helper) {
        BagInventory reduced = bag(BackpackTier.LEATHER, UpgradeKind.STACK_DOWNGRADE_TIER_1);
        reduced.setItem(0, new ItemStack(Items.STONE, 25));
        reduced.remember(20, new ItemStack(Items.STONE));
        reduced.sort("name");
        assertStack(helper, reduced.getItem(20), Items.STONE, 8, "A memory cell uses the current reduced capacity");
        helper.assertValueEqual(count(reduced, Items.STONE), 25, "An oversized saved source is split without losing its remainder");
        for (ItemStack item : reduced.getItems()) helper.assertTrue(item.isEmpty() || item.getCount() <= reduced.capacity(item), "All planned physical cells respect capacity");

        BagInventory large = bag(BackpackTier.NETHERITE, UpgradeKind.STACK_UPGRADE_OMEGA_TIER);
        int limit = large.capacity(new ItemStack(Items.STONE));
        large.setItem(0, new ItemStack(Items.STONE, limit));
        large.setItem(1, new ItemStack(Items.STONE, limit - 7));
        large.remember(119, new ItemStack(Items.STONE));
        long expected = 2L * limit - 7;
        large.sort("count");
        helper.assertValueEqual(large.getItem(119).getCount(), limit, "Large memory stacks clamp at their exact maximum");
        helper.assertValueEqual(contents(large).get(ItemVariant.of(Items.STONE)), expected, "Totals above a signed integer are conserved without count overflow");

        BagInventory full = bag(BackpackTier.LEATHER, UpgradeKind.STACK_DOWNGRADE_TIER_1);
        for (int slot = 0; slot < full.getContainerSize(); slot++) full.setItem(slot, new ItemStack(Items.STONE, 8));
        full.remember(26, new ItemStack(Items.DIAMOND));
        ItemStack before = full.stack().copy();
        full.sort("name");
        assertStack(helper, full.stack(), before, "An impossible new reservation leaves the complete full bag unchanged");

        BagInventory impossibleSplit = bag(BackpackTier.LEATHER, UpgradeKind.STACK_DOWNGRADE_TIER_3);
        impossibleSplit.setItem(0, new ItemStack(Items.STONE, Integer.MAX_VALUE));
        ItemStack huge = impossibleSplit.stack().copy();
        impossibleSplit.sort("count");
        assertStack(helper, impossibleSplit.stack(), huge, "An oversized source requiring too many cells is rejected after a bounded plan without truncation");
        helper.succeed();
    }
}
