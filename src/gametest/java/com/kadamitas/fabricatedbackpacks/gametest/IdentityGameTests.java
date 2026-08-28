package com.kadamitas.fabricatedbackpacks.gametest;

import com.kadamitas.fabricatedbackpacks.admin.AdminNames;
import com.kadamitas.fabricatedbackpacks.admin.AdminSavedData;
import com.kadamitas.fabricatedbackpacks.block.BackpackBlockEntity;
import com.kadamitas.fabricatedbackpacks.config.BackpackConfig;
import com.kadamitas.fabricatedbackpacks.config.ConfigFile;
import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.equipment.BackpackEquipment;
import com.kadamitas.fabricatedbackpacks.gameplay.BackpackIdentities;
import com.kadamitas.fabricatedbackpacks.gameplay.BackpackRuntime;
import com.kadamitas.fabricatedbackpacks.gameplay.BackpackTraversal;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.resource.BackpackBattery;
import com.kadamitas.fabricatedbackpacks.resource.BackpackTank;
import com.kadamitas.fabricatedbackpacks.storage.BagComponents;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.storage.InventorySnapshot;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.kadamitas.fabricatedbackpacks.gametest.BackpackTestSupport.*;

/** Component-owned storage must survive identifier repair, including real equipment and entity publication. */
public final class IdentityGameTests {
    private IdentityGameTests() {}

    private static ItemStack withoutIdentity(ItemStack source) {
        ItemStack copy = source.copy();
        copy.remove(BagComponents.IDENTITY);
        return copy;
    }

    private static ItemStack withoutTreeIdentities(ItemStack source) {
        ItemStack copy = withoutIdentity(source);
        InventorySnapshot saved = source.getOrDefault(BagComponents.CONTENTS, InventorySnapshot.EMPTY);
        List<InventorySnapshot.Entry> entries = new ArrayList<>();
        for (var entry : saved.entries()) {
            ItemStack item = entry.create();
            if (BackpackRegistry.isBackpack(item)) item.remove(BagComponents.IDENTITY);
            entries.add(new InventorySnapshot.Entry(entry.slot(), ItemStackTemplate.fromNonEmptyStack(item.copyWithCount(1)), entry.count()));
        }
        copy.set(BagComponents.CONTENTS, new InventorySnapshot(saved.size(), entries));
        return copy;
    }

    private static void clear(ServerPlayer player) {
        player.getInventory().clearContent();
        BackpackEquipment.set(player, ItemStack.EMPTY);
    }

    private static ItemEntity drop(GameTestHelper helper, ItemStack stack, double x) {
        Vec3 position = helper.absoluteVec(new Vec3(x, 1.5, 3));
        ItemEntity entity = new ItemEntity(helper.getLevel(), position.x, position.y, position.z, stack);
        entity.setPickUpDelay(Integer.MAX_VALUE);
        entity.setNoGravity(true);
        entity.setDeltaMovement(Vec3.ZERO);
        helper.getLevel().addFreshEntity(entity);
        return entity;
    }

    public static void duplicateCarriedIdentities(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ItemStack widened = new ItemStack(BackpackRegistry.item(BackpackTier.LEATHER));
        widened.set(BagComponents.CONTENTS, new InventorySnapshot(144, List.of()));
        BagInventory largest = BagInventory.of(widened);
        largest.setItem(143, new ItemStack(Items.EMERALD, 41));
        BagInventory smaller = bag(BackpackTier.NETHERITE, UpgradeKind.TANK, UpgradeKind.BATTERY,
                UpgradeKind.STACK_UPGRADE_TIER_4, UpgradeKind.AUTO_SMELTING);
        smaller.stack().set(BagComponents.IDENTITY, largest.identity());
        smaller.stack().set(DataComponents.CUSTOM_NAME, Component.literal("Keep every sample"));
        smaller.dye(0x123456, 0x789abc);
        smaller.setItem(0, new ItemStack(Items.STONE, 999));
        ItemStack pickaxe = new ItemStack(Items.DIAMOND_PICKAXE);
        pickaxe.setDamageValue(73);
        smaller.setItem(1, pickaxe);
        smaller.updateSettings(upgrade(smaller, 3), tag -> {
            tag.putLong("feeding_next", Long.MAX_VALUE - 1);
            tag.putInt("burn_remaining", 137);
            tag.putInt("cook_progress", 51);
        });
        BackpackTank tank = new BackpackTank(smaller, upgrade(smaller, 0), false);
        BackpackBattery battery = new BackpackBattery(smaller, upgrade(smaller, 1));
        try (Transaction transaction = Transaction.openOuter()) {
            helper.assertValueEqual(tank.insert(FluidVariant.of(Fluids.WATER), 1234567, transaction), 1234567L, "Real tank fixture accepts fractional fluid units");
            helper.assertValueEqual(battery.insert(2345, transaction), 2345L, "Real battery fixture accepts energy");
            transaction.commit();
        }
        BagInventory firstTie = bag(BackpackTier.IRON);
        BagInventory secondTie = bag(BackpackTier.IRON);
        secondTie.stack().set(BagComponents.IDENTITY, firstTie.identity());
        firstTie.setItem(2, new ItemStack(Items.REDSTONE, 19));
        secondTie.setItem(2, new ItemStack(Items.GOLD_INGOT, 23));
        BagInventory malformed = bag(BackpackTier.COPPER);
        malformed.stack().set(BagComponents.IDENTITY, "not-a-canonical-uuid");
        malformed.setItem(3, new ItemStack(Items.AMETHYST_SHARD, 29));
        player.getInventory().setItem(1, largest.stack());
        player.getInventory().setItem(2, smaller.stack());
        player.getInventory().setItem(3, smaller.stack());
        player.getInventory().setItem(4, firstTie.stack());
        player.getInventory().setItem(5, secondTie.stack());
        player.getInventory().setItem(6, malformed.stack());
        String retained = largest.identity();
        String retainedTie = firstTie.identity();
        List<BagInventory> bags = List.of(largest, smaller, firstTie, secondTie, malformed);
        List<ItemStack> before = bags.stream().map(bag -> bag.stack().copy()).toList();
        try {
            var rules = BackpackConfig.get();
            try {
                BackpackConfig.configure(ConfigFile.decode("{\"storage\":{\"disableDuplicateChecks\":true}}"));
                helper.assertValueEqual(BackpackIdentities.scan(helper.getLevel().getServer()), 0, "The server can explicitly disable identity repair");
                for (int index = 0; index < bags.size(); index++) assertStack(helper, bags.get(index).stack(), before.get(index), "Disabled repair leaves the complete physical stack unchanged");
            } finally { BackpackConfig.configure(rules); }
            BackpackIdentities.scan(helper.getLevel().getServer());
            helper.assertTrue(largest.identity().equals(retained), "Resolved saved capacity outranks the material tier when retaining an identity");
            helper.assertFalse(smaller.identity().equals(retained), "The smaller physical bag receives a new identity");
            helper.assertTrue(firstTie.identity().equals(retainedTie) && !secondTie.identity().equals(retainedTie), "Equal capacities retain the lower stable inventory location");
            helper.assertTrue(AdminNames.isIdentity(malformed.identity()), "A malformed identity is replaced with a canonical UUID");
            helper.assertTrue(player.getInventory().getItem(2) == player.getInventory().getItem(3), "A literal physical alias is not replaced by a new stack");
            for (int index = 0; index < bags.size(); index++)
                assertStack(helper, withoutIdentity(bags.get(index).stack()), withoutIdentity(before.get(index)), "Identity repair preserves every other component for bag " + index);
            helper.assertValueEqual(tank.getAmount(), 1234567L, "Fluid, including fractional units, is conserved");
            helper.assertValueEqual(battery.getAmount(), 2345L, "Energy is conserved");
            helper.assertValueEqual(smaller.settings(upgrade(smaller, 3)).getLongOr("feeding_next", 0), Long.MAX_VALUE - 1, "A repair never resets a saved clock");
            List<ItemStack> repaired = bags.stream().map(bag -> bag.stack().copy()).toList();
            helper.assertValueEqual(BackpackIdentities.scan(helper.getLevel().getServer()), 0, "Repeating a completed scan is idempotent");
            for (int index = 0; index < bags.size(); index++) assertStack(helper, bags.get(index).stack(), repaired.get(index), "Repeated scan leaves the repaired stack unchanged");
        } finally { clear(player); }
        helper.succeed();
    }

    public static void duplicateEquippedAndNestedIdentities(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        BackpackEquipment.set(player, bag(BackpackTier.NETHERITE, UpgradeKind.INCEPTION).stack());
        BagInventory worn = BackpackEquipment.inventory(player).orElseThrow();
        BagInventory largerChild = bag(BackpackTier.DIAMOND, UpgradeKind.TANK);
        largerChild.setItem(0, new ItemStack(Items.DIAMOND, 31));
        worn.setItem(0, largerChild.stack());
        BagInventory inventory = bag(BackpackTier.NETHERITE, UpgradeKind.INCEPTION);
        inventory.stack().set(BagComponents.IDENTITY, worn.identity());
        BagInventory smallerChild = bag(BackpackTier.COPPER);
        smallerChild.stack().set(BagComponents.IDENTITY, largerChild.identity());
        smallerChild.setItem(2, new ItemStack(Items.EMERALD, 43));
        inventory.setItem(0, smallerChild.stack());
        BackpackEquipment.setFromInventory(player, worn);
        helper.assertFalse(BackpackEquipment.get(player) == worn.stack(), "Fixture has a published equipment copy and a separate canonical handle");
        player.getInventory().setItem(0, inventory.stack());
        player.getInventory().setItem(1, worn.stack());
        String rootIdentity = worn.identity();
        String childIdentity = largerChild.identity();
        ItemStack beforeWorn = worn.stack().copy();
        ItemStack beforeInventory = inventory.stack().copy();
        try {
            BackpackIdentities.scan(helper.getLevel().getServer());
            helper.assertTrue(worn.identity().equals(rootIdentity) && !inventory.identity().equals(rootIdentity), "The canonical equipment wins an equal-capacity location tie");
            helper.assertTrue(largerChild.identity().equals(childIdentity) && !smallerChild.identity().equals(childIdentity), "Both physical child candidates participate despite their previous duplicate ID");
            helper.assertTrue(BackpackEquipment.isCurrent(player, worn) && BackpackEquipment.inventory(player).orElseThrow() == worn,
                    "Identity publication does not invalidate the canonical equipment inventory");
            assertStack(helper, BackpackEquipment.get(player), worn.stack(), "Equipment publication remains synchronized");
            assertStack(helper, withoutTreeIdentities(worn.stack()), withoutTreeIdentities(beforeWorn), "Worn root and child contents are conserved");
            assertStack(helper, withoutTreeIdentities(inventory.stack()), withoutTreeIdentities(beforeInventory), "The other root and child contents are conserved");
            BagInventory saved = BagInventory.of(roundTrip(helper.getLevel(), inventory.stack()));
            helper.assertTrue(saved.getItem(0).get(BagComponents.IDENTITY).equals(smallerChild.identity()), "A child repair is serialized into the physical parent");
            helper.assertValueEqual(BackpackRuntime.physicalCarried(player).size(), 2, "The same canonical worn stack in an inventory slot remains one physical candidate");
            helper.assertValueEqual(BackpackIdentities.scan(helper.getLevel().getServer()), 0, "A later equipment scan does not reidentify the published alias");
            helper.assertValueEqual(BackpackTraversal.children(inventory).size(), 1, "Repaired children can enter the bounded processing graph");

            BagInventory greater = BagInventory.of(new ItemStack(BackpackRegistry.item(BackpackTier.LEATHER)));
            ItemStack expanded = greater.stack().copy();
            expanded.set(BagComponents.CONTENTS, new InventorySnapshot(144, List.of()));
            greater = BagInventory.of(expanded);
            greater.stack().set(BagComponents.IDENTITY, worn.identity());
            player.getInventory().setItem(2, greater.stack());
            String keptByGreater = greater.identity();
            BackpackIdentities.scan(helper.getLevel().getServer());
            helper.assertTrue(greater.identity().equals(keptByGreater) && !worn.identity().equals(keptByGreater), "Capacity still outranks equipment preference");
            helper.assertTrue(BackpackEquipment.isCurrent(player, worn), "Reidentifying the worn bag preserves its canonical handle");
            assertStack(helper, BackpackEquipment.get(player), worn.stack(), "A changed worn identity is published to observers");
            helper.assertValueEqual(BackpackIdentities.scan(helper.getLevel().getServer()), 0, "Worn identity changes are stable on repeated scans");
        } finally { clear(player); }
        helper.succeed();
    }

    public static void duplicateDroppedIdentities(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        BagInventory carried = bag(BackpackTier.IRON);
        BagInventory larger = bag(BackpackTier.NETHERITE, UpgradeKind.INCEPTION);
        BagInventory child = bag(BackpackTier.LEATHER);
        child.setItem(0, new ItemStack(Items.DIAMOND, 11));
        larger.setItem(1, child.stack());
        BagInventory smaller = bag(BackpackTier.COPPER);
        carried.stack().set(BagComponents.IDENTITY, larger.identity());
        smaller.stack().set(BagComponents.IDENTITY, larger.identity());
        carried.setItem(0, new ItemStack(Items.COAL, 7));
        smaller.setItem(0, new ItemStack(Items.IRON_INGOT, 13));
        player.getInventory().setItem(0, carried.stack());
        ItemEntity first = drop(helper, larger.stack(), 2);
        ItemEntity second = drop(helper, smaller.stack(), 3);
        ItemStack invalidCount = smaller.stack().copyWithCount(2);
        ItemEntity invalid = drop(helper, invalidCount, 4);
        String retained = larger.identity();
        ItemStack beforeFirst = first.getItem().copy();
        ItemStack beforeSecond = second.getItem().copy();
        ItemStack beforeCarried = carried.stack().copy();
        try {
            var rules = BackpackConfig.get();
            try {
                BackpackConfig.configure(ConfigFile.decode("{\"storage\":{\"disableDuplicateChecks\":true}}"));
                helper.assertValueEqual(BackpackIdentities.scanNearby(second), 0, "The same disable flag covers nearby dropped-entity repair");
                assertStack(helper, second.getItem(), beforeSecond, "Disabling dropped checks preserves its complete stack");
                assertStack(helper, carried.stack(), beforeCarried, "A dropped scan cannot repair nearby inventory while disabled");
            } finally { BackpackConfig.configure(rules); }
            BackpackIdentities.scanNearby(second);
            helper.assertTrue(first.getItem().get(BagComponents.IDENTITY).equals(retained), "The larger physical dropped backpack keeps the identity");
            helper.assertFalse(second.getItem().get(BagComponents.IDENTITY).equals(retained) || carried.identity().equals(retained), "Nearby dropped and carried copies get separate identities");
            helper.assertFalse(second.getItem().get(BagComponents.IDENTITY).equals(carried.identity()), "Every distinct repaired stack has an independent ID");
            assertStack(helper, withoutTreeIdentities(first.getItem()), withoutTreeIdentities(beforeFirst), "Dropped parent and child contents are preserved");
            assertStack(helper, withoutIdentity(second.getItem()), withoutIdentity(beforeSecond), "Entity item publication preserves all components other than its ID");
            assertStack(helper, withoutIdentity(carried.stack()), withoutIdentity(beforeCarried), "Nearby carried contents are preserved");
            assertStack(helper, invalid.getItem(), invalidCount, "Malformed multi-count bags are not split, deleted or normalized by identity repair");
            ItemStack stable = second.getItem().copy();
            helper.assertValueEqual(BackpackIdentities.scanNearby(first), 0, "The real dropped-entity path is idempotent");
            assertStack(helper, second.getItem(), stable, "An unchanged entity is not republished or rewritten");
        } finally { first.discard(); second.discard(); invalid.discard(); clear(player); }
        helper.succeed();
    }

    public static void identityRepairAndArchiveCadence(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        BagInventory first = bag(BackpackTier.LEATHER, UpgradeKind.FEEDING);
        BagInventory second = bag(BackpackTier.COPPER, UpgradeKind.FEEDING);
        first.stack().set(BagComponents.IDENTITY, second.identity());
        long future = helper.getLevel().getGameTime() + 100000;
        first.updateSettings(upgrade(first, 0), tag -> tag.putLong("feeding_next", future));
        second.updateSettings(upgrade(second, 0), tag -> tag.putLong("feeding_next", future));
        first.setItem(0, new ItemStack(Items.BREAD, 17));
        second.setItem(0, new ItemStack(Items.CARROT, 23));
        player.getInventory().setItem(0, first.stack());
        player.getInventory().setItem(1, second.stack());
        BagInventory placedSource = bag(BackpackTier.NETHERITE, UpgradeKind.INCEPTION);
        BagInventory child = bag(BackpackTier.LEATHER);
        child.setItem(0, new ItemStack(Items.AMETHYST_SHARD, 29));
        placedSource.setItem(2, child.stack());
        BlockPos position = new BlockPos(3, 1, 3);
        helper.setBlock(position, BackpackRegistry.block(BackpackTier.NETHERITE).defaultBlockState());
        BackpackBlockEntity placed = (BackpackBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(position));
        placed.setStack(placedSource.stack());
        String keeper = second.identity();
        ItemStack beforeFirst = first.stack().copy();
        ItemStack beforeSecond = second.stack().copy();
        helper.runAfterDelay(25, () -> {
            try {
                helper.assertTrue(second.identity().equals(keeper) && !first.identity().equals(keeper), "Registered START tick repairs the collision without a manual scan");
                assertStack(helper, withoutIdentity(first.stack()), withoutIdentity(beforeFirst), "Automatic repair preserves the first bag and future feeding clock");
                assertStack(helper, withoutIdentity(second.stack()), withoutIdentity(beforeSecond), "The retained bag's stored data is unchanged");
                AdminSavedData data = AdminSavedData.of(helper.getLevel().getServer());
                var firstArchive = data.archive(first.identity()).orElseThrow();
                var secondArchive = data.archive(second.identity()).orElseThrow();
                helper.assertTrue(firstArchive.ownerId().equals(player.getUUID().toString()) && secondArchive.ownerId().equals(player.getUUID().toString()), "Periodic scans archive both independently repaired player bags");
                assertStack(helper, firstArchive.backpack(), first.stack(), "Archive runs after identity repair and retains current contents");
                helper.assertTrue(data.archive(placed.inventory().identity()).isPresent(), "An actual ticking placed backpack gets an archive");
                helper.assertTrue(data.archive(child.identity()).isPresent(), "Placed child storage is archived at one bounded level");
                helper.assertValueEqual(first.settings(upgrade(first, 0)).getLongOr("feeding_next", 0), future, "Collision recovery does not turn a future clock into immediate automation");
            } finally { clear(player); }
            helper.succeed();
        });
    }

    public static void physicalArchivesIgnoreUpgradeScope(GameTestHelper helper) {
        var previous = BackpackConfig.get();
        ServerPlayer player = player(helper);
        try {
            BackpackConfig.configure(ConfigFile.decode("{\"storage\":{\"onlyWornUpgrades\":true,\"childUpgrades\":false,\"outerUsesChildren\":false}}"));
            BagInventory root = bag(BackpackTier.NETHERITE, UpgradeKind.INCEPTION);
            BagInventory child = bag(BackpackTier.LEATHER);
            child.setItem(0, new ItemStack(Items.GOLD_INGOT, 37));
            root.setItem(0, child.stack());
            player.getInventory().setItem(0, root.stack());
            BackpackEquipment.set(player, bag(BackpackTier.COPPER).stack());
            helper.assertValueEqual(BackpackRuntime.carried(player).size(), 1, "Only-worn rules limit automatic upgrade roots");
            helper.assertValueEqual(BackpackRuntime.physicalCarried(player).size(), 2, "Physical discovery still includes the inactive inventory bag");
            for (BagInventory bag : BackpackRuntime.physicalCarried(player)) BackpackRuntime.archiveTree(bag, helper.getLevel(), player);
            AdminSavedData data = AdminSavedData.of(helper.getLevel().getServer());
            helper.assertTrue(data.archive(root.identity()).isPresent() && data.archive(child.identity()).isPresent(), "Disabling outer and child upgrades does not disable their physical archives");
            helper.assertTrue(data.archive(child.identity()).orElseThrow().ownerId().equals(player.getUUID().toString()), "Child archives retain the physical carrier's access ownership");
            BackpackRuntime.archiveTree(root, helper.getLevel(), null);
            helper.assertTrue(data.archive(root.identity()).orElseThrow().playerBacked(), "A later non-player scan cannot remove ownership protection");
        } finally { clear(player); BackpackConfig.configure(previous); }
        helper.succeed();
    }
}
