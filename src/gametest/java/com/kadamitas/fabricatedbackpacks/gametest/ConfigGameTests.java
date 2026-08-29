package com.kadamitas.fabricatedbackpacks.gametest;

import com.kadamitas.fabricatedbackpacks.config.BackpackConfig;
import com.kadamitas.fabricatedbackpacks.config.ConfigFile;
import com.kadamitas.fabricatedbackpacks.config.ServerConfig;
import com.kadamitas.fabricatedbackpacks.config.BurdenRuntime;
import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.gameplay.MobCapture;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.storage.BagComponents;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.storage.InventorySnapshot;
import com.kadamitas.fabricatedbackpacks.storage.InstalledUpgrade;
import com.kadamitas.fabricatedbackpacks.settings.SettingsTemplate;
import com.kadamitas.fabricatedbackpacks.upgrade.CookingRuntime;
import com.kadamitas.fabricatedbackpacks.upgrade.CompactingRuntime;
import com.kadamitas.fabricatedbackpacks.upgrade.ConsumptionRuntime;
import com.kadamitas.fabricatedbackpacks.upgrade.TransferRuntime;
import com.kadamitas.fabricatedbackpacks.upgrade.AlchemyRuntime;
import com.kadamitas.fabricatedbackpacks.upgrade.UpgradeEngine;
import com.kadamitas.fabricatedbackpacks.upgrade.UpgradeFilters;
import com.kadamitas.fabricatedbackpacks.upgrade.JukeboxRuntime;
import com.kadamitas.fabricatedbackpacks.resource.BackpackTank;
import com.kadamitas.fabricatedbackpacks.resource.BackpackBattery;
import com.kadamitas.fabricatedbackpacks.resource.ResourceRuntime;
import com.kadamitas.fabricatedbackpacks.resource.FluidAmount;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static com.kadamitas.fabricatedbackpacks.gametest.BackpackTestSupport.*;

public final class ConfigGameTests {
    private ConfigGameTests() { }

    public static void configuredGeometryAndShrink(GameTestHelper helper) {
        ServerConfig previous = BackpackConfig.get();
        try {
            BackpackConfig.configure(ConfigFile.decode("{\"capacities\":{\"backpack\":{\"slots\":144,\"upgrades\":10}}}"));
            BagInventory large = BagInventory.of(new ItemStack(BackpackRegistry.item(BackpackTier.LEATHER)));
            helper.assertValueEqual(large.getContainerSize(), 144, "Configured maximum inventory capacity is used");
            helper.assertValueEqual(large.upgrades().getContainerSize(), 10, "Configured upgrade maximum is used");
            helper.assertValueEqual(large.columns(), 12, "Large configured geometry uses twelve columns");
            ItemStack emptyLarge = BackpackTestSupport.roundTrip(helper.getLevel(), large.stack());
            large.setItem(143, new ItemStack(Items.EMERALD, 41));
            large.upgrades().setItem(9, new ItemStack(BackpackRegistry.item(UpgradeKind.PICKUP)));
            ItemStack saved = BackpackTestSupport.roundTrip(helper.getLevel(), large.stack());

            BackpackConfig.configure(ConfigFile.decode("{\"capacities\":{\"backpack\":{\"slots\":1,\"upgrades\":0}}}"));
            BagInventory retained = BagInventory.of(saved);
            helper.assertValueEqual(retained.getContainerSize(), 144, "Shrinking defaults does not shrink existing storage");
            helper.assertValueEqual(retained.upgrades().getContainerSize(), 10, "Shrinking defaults retains old upgrade slots");
            BackpackTestSupport.assertStack(helper, retained.getItem(143), Items.EMERALD, 41, "Last retained item survives a codec round trip");
            helper.assertTrue(retained.has(UpgradeKind.PICKUP), "The final upgrade slot survives shrink");
            BagInventory small = BagInventory.of(new ItemStack(BackpackRegistry.item(BackpackTier.LEATHER)));
            helper.assertValueEqual(small.getContainerSize(), 1, "New bags receive the lower default");
            helper.assertValueEqual(small.upgrades().getContainerSize(), 0, "Zero upgrade slots is a supported configuration");
            helper.assertValueEqual(BagInventory.clientOf(emptyLarge).getContainerSize(), 144, "Empty server dimensions synchronize without local inference");
            ItemStack smallCopy = small.stack().copy();
            BackpackConfig.configure(previous);
            BagInventory mirror = BagInventory.clientOf(smallCopy);
            helper.assertValueEqual(mirror.getContainerSize(), 1, "A client with a larger local default uses exact server geometry");
            helper.assertValueEqual(mirror.upgrades().getContainerSize(), 0, "The client retains an explicitly empty upgrade bar");
        } finally { BackpackConfig.configure(previous); }
        helper.succeed();
    }

    public static void geometryReflowAndComponents(GameTestHelper helper) {
        ServerConfig previous = BackpackConfig.get();
        try {
            BackpackConfig.configure(ConfigFile.decode("{\"capacities\":{\"backpack\":{\"slots\":81,\"upgrades\":1}}}"));
            BagInventory bag = BackpackTestSupport.bag(BackpackTier.LEATHER, UpgradeKind.MOB_CATCHER);
            Set<Integer> emptyRectangle = Set.of(63, 64, 72, 73);
            for (int slot = 0; slot < 81; slot++) if (!emptyRectangle.contains(slot)) bag.setItem(slot, new ItemStack(Items.STONE));
            bag.remember(80, new ItemStack(Items.DIAMOND));
            bag.toggleNoSort(80);
            bag.updateSettings(tag -> tag.putInt("display_slot", 80));
            var player = BackpackTestSupport.player(helper);
            var chicken = helper.spawn(EntityTypes.CHICKEN, new BlockPos(5, 1, 5));
            chicken.setNoAi(true);
            helper.assertTrue(MobCapture.capture(bag, chicken, player), "A real chicken occupies the only free rectangle in the last two rows");
            int[] before = bag.settings().getIntArray("captured_slots").orElseThrow();
            helper.assertValueEqual(before.length, 4, "The chicken uses a complete rectangular footprint");
            bag.stack().set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of(2.5f), List.of(true), List.of("retained"), List.of(1, 2)));
            bag.dye(0x13579B, 0x2468AC);
            CustomModelData dyed = bag.stack().get(DataComponents.CUSTOM_MODEL_DATA);
            helper.assertValueEqual(dyed.floats(), List.of(2.5f), "Dye preserves model floats");
            helper.assertValueEqual(dyed.flags(), List.of(true), "Dye preserves model flags");
            helper.assertValueEqual(dyed.strings(), List.of("retained"), "Dye preserves model strings");

            ItemStack saved = BackpackTestSupport.roundTrip(helper.getLevel(), bag.stack());
            BackpackConfig.configure(ConfigFile.decode("{\"capacities\":{\"backpack\":{\"slots\":82,\"upgrades\":0}}}"));
            BagInventory widened = BagInventory.of(saved);
            helper.assertValueEqual(widened.getContainerSize(), 108, "Widening preserves all nine existing rows");
            helper.assertValueEqual(widened.columns(), 12, "The saved grid widens to twelve columns");
            BackpackTestSupport.assertStack(helper, widened.getItem(104), Items.STONE, 1, "The last old cell retains its row and column");
            helper.assertTrue(widened.stack().getOrDefault(BagComponents.MEMORY, InventorySnapshot.EMPTY).entries().stream().anyMatch(entry -> entry.slot() == 104),
                    "Memory reservations move with the cell");
            helper.assertTrue(Arrays.stream(widened.settings().getIntArray("no_sort").orElseThrow()).anyMatch(slot -> slot == 104), "No-sort cells move with the grid");
            helper.assertValueEqual(widened.settings().getIntOr("display_slot", -1), 104, "Exterior display follows its item cell");
            int[] expected = Arrays.stream(before).map(slot -> slot / 9 * 12 + slot % 9).sorted().toArray();
            helper.assertTrue(Arrays.equals(expected, widened.settings().getIntArray("captured_slots").orElseThrow()), "Captured rectangles reflow without changing their shape");
            helper.assertTrue(MobCapture.release(widened, 0, player, Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(2, 1, 2)))),
                    "The real captured entity remains releasable after reflow and serialization");
            helper.assertValueEqual(BackpackTestSupport.count(widened, Items.STONE), 77, "Reflow conserves every ordinary item");
        } finally { BackpackConfig.configure(previous); }
        helper.succeed();
    }

    public static void itemRulesAndCapture(GameTestHelper helper) {
        ServerConfig previous = BackpackConfig.get();
        try {
            BackpackConfig.configure(ConfigFile.decode("""
                    {"storage":{"disallowedItems":["minecraft:stone","#minecraft:planks"],"disallowContainerItems":true},
                     "capture":{"blockedEntities":["minecraft:chicken"],"passiveEntities":["minecraft:zombie"],
                                "hostileEntities":["minecraft:zombie"],"excludeInventories":true}}
                    """));
            BagInventory bag = BackpackTestSupport.bag(BackpackTier.GOLD, UpgradeKind.MOB_CATCHER);
            helper.assertFalse(bag.canPlaceItem(0, new ItemStack(Items.STONE)), "Explicit item prohibition is enforced");
            helper.assertFalse(bag.canPlaceItem(0, new ItemStack(Items.OAK_PLANKS)), "Item tag prohibition is enforced");
            helper.assertFalse(bag.canPlaceItem(0, new ItemStack(Items.BUNDLE)), "Optional container-item prohibition is enforced");
            helper.assertTrue(bag.canPlaceItem(0, new ItemStack(Items.DIAMOND)), "An unrelated item remains insertable");
            helper.assertValueEqual(bag.insert(new ItemStack(Items.STONE, 23), false).getCount(), 23, "Rejected insertion returns the whole resource");
            var player = BackpackTestSupport.player(helper);
            var blocked = helper.spawn(EntityTypes.CHICKEN, new BlockPos(5, 1, 5));
            helper.assertFalse(MobCapture.capture(bag, blocked, player), "A blocked entity remains live");
            helper.assertTrue(blocked.isAlive(), "Rejected capture does not remove the entity");
            blocked.discard();
            var zombie = helper.spawn(EntityTypes.ZOMBIE, new BlockPos(5, 1, 5));
            zombie.setNoAi(true); zombie.setHealth(2);
            helper.assertTrue(MobCapture.capture(bag, zombie, player), "Passive classification wins over a simultaneous hostile override");
            var villager = helper.spawn(EntityTypes.VILLAGER, new BlockPos(5, 1, 5));
            villager.setHealth(2);
            helper.assertFalse(MobCapture.capture(bag, villager, player), "Configured inventory-entity exclusion rejects a real villager");
            villager.discard();

            BackpackConfig.configure(ConfigFile.decode("{\"capture\":{\"blockedEntities\":[\"#minecraft:undead\"],\"passiveEntities\":[\"minecraft:zombie\"]}}"));
            var undead = helper.spawn(EntityTypes.ZOMBIE, new BlockPos(5, 1, 5));
            undead.setNoAi(true); undead.setHealth(2);
            helper.assertFalse(MobCapture.capture(bag, undead, player), "An entity tag blocklist remains stronger than passive classification");
            undead.discard();
            helper.assertFalse(bag.settings().getListOrEmpty("captured_entities").isEmpty(), "Rejected attempts preserve the earlier captured entity");
        } finally { BackpackConfig.configure(previous); }
        helper.succeed();
    }

    public static void backpackBurden(GameTestHelper helper) {
        ServerConfig previous = BackpackConfig.get();
        try {
            BackpackConfig.configure(ConfigFile.decode("{\"storage\":{\"onlyWornUpgrades\":true,\"burden\":{\"enabled\":true}}}"));
            var player = BackpackTestSupport.player(helper);
            for (int slot = 0; slot < 6; slot++) player.getInventory().setItem(slot, new ItemStack(BackpackRegistry.item(BackpackTier.LEATHER)));
            com.kadamitas.fabricatedbackpacks.equipment.BackpackEquipment.set(player, new ItemStack(BackpackRegistry.item(BackpackTier.LEATHER)));
            helper.assertValueEqual(BurdenRuntime.backpackCount(player), 7L, "Burden counts physical inventory and native equipment even when only worn upgrades tick");
            BurdenRuntime.tick(player);
            var effect = player.getEffect(net.minecraft.world.effect.MobEffects.SLOWNESS);
            helper.assertTrue(effect != null && effect.getAmplifier() == 3, "Each backpack over the three-bag allowance adds one effect level");
            player.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.SLOWNESS, 600, 5));
            BurdenRuntime.tick(player);
            helper.assertValueEqual(player.getEffect(net.minecraft.world.effect.MobEffects.SLOWNESS).getAmplifier(), 5, "Burden does not weaken a stronger existing potion effect");
            for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) player.getInventory().setItem(slot, ItemStack.EMPTY);
            com.kadamitas.fabricatedbackpacks.equipment.BackpackEquipment.set(player, ItemStack.EMPTY);
            BurdenRuntime.tick(player);
            helper.assertValueEqual(player.getEffect(net.minecraft.world.effect.MobEffects.SLOWNESS).getAmplifier(), 5, "Removing bags does not remove another effect's provenance");
        } finally { BackpackConfig.configure(previous); }
        helper.succeed();
    }

    public static void upgradeGeometryAndInstallation(GameTestHelper helper) {
        ServerConfig previous = BackpackConfig.get();
        try {
            BackpackConfig.configure(ConfigFile.decode("""
                    {"upgrades":{"filters":{"advanced_pickup_upgrade":{"slots":64,"columns":6}},
                      "jukebox":{"size":16,"rowWidth":6},"groupLimits":{"cooking":2,"tank":10,"battery":10},
                      "itemLimits":{"tank_upgrade":10,"battery_upgrade":10},
                      "stack":{"baseMultiplier":2,"excludedItems":["#minecraft:logs"]}}}
                    """));
            BagInventory large = bag(BackpackTier.NETHERITE, UpgradeKind.ADVANCED_JUKEBOX, UpgradeKind.ADVANCED_PICKUP);
            var music = upgrade(large, 0); var pickup = upgrade(large, 1);
            large.upgradeInventory(music).setItem(15, new ItemStack(Items.MUSIC_DISC_CAT));
            large.setFilter(pickup, 63, new ItemStack(Items.DIAMOND, 64));
            large.setItem(0, new ItemStack(Items.DIAMOND, 128));
            helper.assertValueEqual(large.inventoryColumns(music), 6, "Configured record columns reach the real menu model");
            helper.assertValueEqual(large.filterColumns(pickup), 6, "Configured filter columns are shared with the server model");
            helper.assertValueEqual(large.capacity(new ItemStack(Items.DIAMOND)), 128, "The server base multiplier affects normal storage");
            helper.assertValueEqual(large.capacity(new ItemStack(Items.OAK_LOG)), 64, "A registry tag exclusion prevents overstacking");
            helper.assertTrue(UpgradeEngine.isValidAuxiliary(large, music, 15, new ItemStack(Items.MUSIC_DISC_CAT), helper.getLevel()), "The sixteenth configured record cell accepts a real disc");
            helper.assertFalse(UpgradeEngine.isValidAuxiliary(large, music, 16, new ItemStack(Items.MUSIC_DISC_CAT), helper.getLevel()), "Record geometry rejects an out-of-range physical slot");
            BagInventory limits = bag(BackpackTier.NETHERITE, UpgradeKind.SMELTING, UpgradeKind.AUTO_SMELTING);
            helper.assertFalse(limits.canInstall(2, new ItemStack(BackpackRegistry.item(UpgradeKind.SMOKING))), "A configured cooking group bound applies across different items");
            helper.assertFalse(limits.canInstall(1, new ItemStack(BackpackRegistry.item(UpgradeKind.SMELTING))), "Per-item limits still apply when the group permits two variants");
            helper.assertTrue(limits.canInstall(1, new ItemStack(BackpackRegistry.item(UpgradeKind.SMOKING))), "A legal replacement at the group limit remains possible");
            BagInventory hardLimits = bag(BackpackTier.NETHERITE, UpgradeKind.TANK, UpgradeKind.TANK, UpgradeKind.BATTERY);
            helper.assertFalse(hardLimits.canInstall(3, new ItemStack(BackpackRegistry.item(UpgradeKind.TANK))), "Tank geometry retains its hard two-upgrade bound");
            helper.assertFalse(hardLimits.canInstall(3, new ItemStack(BackpackRegistry.item(UpgradeKind.BATTERY))), "Battery geometry retains its hard single-upgrade bound");
            ItemStack saved = roundTrip(helper.getLevel(), large.stack());

            BackpackConfig.configure(ConfigFile.decode("""
                    {"upgrades":{"filters":{"advanced_pickup_upgrade":{"slots":1,"columns":1}},"jukebox":{"size":2,"rowWidth":1}}}
                    """));
            BagInventory retained = BagInventory.of(saved);
            helper.assertValueEqual(retained.inventorySlots(upgrade(retained, 0)), 16, "Shrinking record defaults preserves all saved physical cells");
            assertStack(helper, retained.upgradeInventory(upgrade(retained, 0)).getItem(15), Items.MUSIC_DISC_CAT, 1, "The final disc survives a real codec and smaller defaults");
            helper.assertValueEqual(retained.filterSlots(upgrade(retained, 1)), 64, "Shrinking ghost defaults preserves existing filter rows");
            assertStack(helper, retained.ghost(upgrade(retained, 1), 63), Items.DIAMOND, 1, "Ghost counts remain one while the last row survives");
            assertStack(helper, retained.getItem(0), Items.DIAMOND, 128, "A lower multiplier never deletes existing over-capacity items");
            helper.assertValueEqual(retained.capacity(new ItemStack(Items.DIAMOND)), 64, "Future insertions use the new server capacity");
            retained.setFilter(upgrade(retained, 1), 63, new ItemStack(Items.APPLE));
            retained.updateSettings(upgrade(retained, 1), tag -> tag.putString("filter_mode", "ALLOW"));
            helper.assertTrue(UpgradeFilters.matches(retained, upgrade(retained, 1), new ItemStack(Items.APPLE)), "Preserved high filter rows remain active and editable");
            BagInventory fresh = bag(BackpackTier.NETHERITE, UpgradeKind.ADVANCED_JUKEBOX, UpgradeKind.ADVANCED_PICKUP);
            helper.assertValueEqual(fresh.inventorySlots(upgrade(fresh, 0)), 2, "New upgrades use the smaller record default");
            helper.assertValueEqual(fresh.filterSlots(upgrade(fresh, 1)), 1, "New upgrades use the smaller filter default");
            fresh.setFilter(upgrade(fresh, 1), 1, new ItemStack(Items.EMERALD));
            helper.assertTrue(fresh.filterItems(upgrade(fresh, 1)).isEmpty(), "A forged new filter row cannot expand its configured layout");
            BagInventory basic = bag(BackpackTier.LEATHER, UpgradeKind.JUKEBOX);
            helper.assertValueEqual(basic.inventorySlots(upgrade(basic, 0)), 2, "Basic jukebox storage uses its doubled two-slot library");
        } finally { BackpackConfig.configure(previous); }
        helper.succeed();
    }

    public static void cookingFilterGeometryAndTemplates(GameTestHelper helper) {
        ServerConfig previous = BackpackConfig.get();
        try {
            BackpackConfig.configure(ServerConfig.defaults());
            BagInventory source = bag(BackpackTier.NETHERITE, UpgradeKind.AUTO_SMELTING);
            var old = upgrade(source, 0);
            source.setFilter(old, 0, new ItemStack(Items.RAW_IRON));
            source.setFilter(old, 7, new ItemStack(Items.RAW_COPPER));
            source.setFilter(old, 8, new ItemStack(Items.COAL));
            source.setFilter(old, 11, new ItemStack(Items.CHARCOAL));
            source.updateSettings(old, state -> { state.remove("cooking_input_filter_slots"); state.remove("cooking_fuel_filter_slots"); });
            BagInventory legacy = BagInventory.of(roundTrip(helper.getLevel(), source.stack()));
            BackpackConfig.configure(ConfigFile.decode("{\"upgrades\":{\"cooking\":{\"inputFilters\":12,\"fuelFilters\":6}}}"));
            var cooker = upgrade(legacy, 0);
            helper.assertTrue(legacy.ghost(cooker, 8).isEmpty(), "Expanding inputs does not reinterpret legacy coal as a recipe input");
            assertStack(helper, legacy.ghost(cooker, 12), Items.COAL, 1, "Legacy fuel follows the effective input boundary");
            assertStack(helper, legacy.ghost(cooker, 15), Items.CHARCOAL, 1, "All legacy fuel positions retain their relative order");
            legacy.setFilter(cooker, 11, new ItemStack(Items.RAW_GOLD));
            legacy.setFilter(cooker, 17, new ItemStack(Items.BAMBOO));
            helper.assertValueEqual(legacy.settings(cooker).getIntOr("cooking_input_filter_slots", -1), 12, "Editing migrates the actual saved input geometry");
            SettingsTemplate template = SettingsTemplate.capture(legacy);
            var ops = helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
            template = SettingsTemplate.CODEC.parse(ops, SettingsTemplate.CODEC.encodeStart(ops, template).getOrThrow()).getOrThrow();
            BackpackConfig.configure(ConfigFile.decode("{\"upgrades\":{\"cooking\":{\"inputFilters\":2,\"fuelFilters\":2}}}"));
            helper.assertValueEqual(legacy.filterSlots(cooker), 18, "Changing defaults cannot truncate a migrated cooker");
            assertStack(helper, legacy.ghost(cooker, 17), Items.BAMBOO, 1, "The last expanded fuel filter remains available");
            BagInventory target = bag(BackpackTier.NETHERITE, UpgradeKind.AUTO_SMELTING);
            template.apply(target);
            var installed = upgrade(target, 0);
            helper.assertValueEqual(target.filterSlots(installed), 4, "A template respects a new target's smaller category sizes");
            assertStack(helper, target.ghost(installed, 0), Items.RAW_IRON, 1, "The first input is retained in the target input category");
            assertStack(helper, target.ghost(installed, 2), Items.COAL, 1, "Fuel remaps from source slot twelve to target slot two");
            helper.assertTrue(target.ghost(installed, 1).isEmpty() && target.ghost(installed, 3).isEmpty(), "Out-of-range input and fuel filters are trimmed independently");
            target.setItem(0, new ItemStack(Items.RAW_IRON, 3));
            target.setItem(1, new ItemStack(Items.COAL));
            target.setItem(2, new ItemStack(Items.RAW_GOLD));
            CookingRuntime.tick(target, installed, helper.getLevel());
            helper.assertTrue(target.upgradeInventory(installed).getItem(CookingRuntime.INPUT).is(Items.RAW_IRON), "Auto cooking uses the remapped input filter against a real recipe");
            helper.assertTrue(target.settings(installed).getBooleanOr("burning", false), "The remapped fuel filter supplies actual vanilla fuel");
            assertStack(helper, target.getItem(2), Items.RAW_GOLD, 1, "A trimmed source filter cannot authorize unrelated input");
        } finally { BackpackConfig.configure(previous); }
        helper.succeed();
    }

    private record CookingCase(BagInventory bag, InstalledUpgrade upgrade, ServerConfig rules, int duration, int fuel, net.minecraft.world.item.Item result) { }

    public static void configuredCookingBounds(GameTestHelper helper) {
        java.util.ArrayList<CookingCase> cases = new java.util.ArrayList<>();
        for (String tuning : List.of("{\"speed\":4,\"fuelEfficiency\":0.25}", "{\"speed\":0.25,\"fuelEfficiency\":4}")) {
            ServerConfig rules = ConfigFile.decode("{\"upgrades\":{\"cooking\":" + tuning + "}}");
            for (UpgradeKind kind : List.of(UpgradeKind.SMELTING, UpgradeKind.AUTO_SMELTING, UpgradeKind.SMOKING,
                    UpgradeKind.AUTO_SMOKING, UpgradeKind.BLASTING, UpgradeKind.AUTO_BLASTING)) {
                BagInventory bag = bag(BackpackTier.LEATHER, kind);
                var upgrade = upgrade(bag, 0);
                ItemStack input = new ItemStack(kind == UpgradeKind.SMOKING || kind == UpgradeKind.AUTO_SMOKING ? Items.BEEF : Items.RAW_IRON);
                var recipe = CookingRuntime.recipe(helper.getLevel(), kind, input).orElseThrow().value();
                bag.upgradeInventory(upgrade).setItem(CookingRuntime.INPUT, input);
                bag.upgradeInventory(upgrade).setItem(CookingRuntime.FUEL, new ItemStack(Items.COAL));
                bag.updateSettings(upgrade, state -> { state.putDouble("cooking_speed", 64); state.putDouble("fuel_efficiency", 64); });
                int duration = (int) Math.ceil(recipe.cookingTime() / rules.upgrades().cooking().speed());
                boolean quick = kind == UpgradeKind.SMOKING || kind == UpgradeKind.AUTO_SMOKING || kind == UpgradeKind.BLASTING || kind == UpgradeKind.AUTO_BLASTING;
                int fuel = (int) Math.floor(helper.getLevel().fuelValues().burnDuration(new ItemStack(Items.COAL)) * rules.upgrades().cooking().fuelEfficiency() * (quick ? .5 : 1));
                cases.add(new CookingCase(bag, upgrade, rules, duration, fuel, recipe.assemble(new net.minecraft.world.item.crafting.SingleRecipeInput(input)).getItem()));
            }
        }
        int[] elapsed = {0};
        helper.onEachTick(() -> {
            ServerConfig prior = BackpackConfig.get();
            try {
                elapsed[0]++;
                for (CookingCase test : cases) {
                    if (elapsed[0] > test.duration()) continue;
                    BackpackConfig.configure(test.rules());
                    CookingRuntime.tick(test.bag(), test.upgrade(), helper.getLevel());
                    var state = test.bag().settings(test.upgrade());
                    helper.assertValueEqual(state.getIntOr("cook_total", -1), test.duration(), "Server speed overrides a forged item multiplier for " + test.upgrade().kind());
                    helper.assertValueEqual(state.getIntOr("burn_total", -1), test.fuel(), "Server efficiency overrides a forged item multiplier for " + test.upgrade().kind());
                    if (elapsed[0] == test.duration()) {
                        helper.assertValueEqual(count(test.bag(), test.result()) + count(test.bag().upgradeInventory(test.upgrade()), test.result()), 1, "Exactly one recipe completes at the configured tick boundary");
                        helper.assertValueEqual(state.getIntOr("burn_remaining", -1), test.fuel() - test.duration(), "Fuel is spent once per active vanilla tick");
                        helper.assertValueEqual(count(test.bag().upgradeInventory(test.upgrade()), Items.COAL), 0, "Exactly one physical fuel item is consumed");
                    } else helper.assertValueEqual(count(test.bag(), test.result()) + count(test.bag().upgradeInventory(test.upgrade()), test.result()), 0, "The configured speed cannot produce an early result");
                }
                if (cases.stream().allMatch(test -> elapsed[0] >= test.duration())) helper.succeed();
            } finally { BackpackConfig.configure(prior); }
        });
    }

    public static void configuredResourceBounds(GameTestHelper helper) {
        ServerConfig previous = BackpackConfig.get();
        try {
            BackpackConfig.configure(ConfigFile.decode("""
                    {"upgrades":{"stack":{"baseMultiplier":1.5,"multipliers":{"stack_upgrade_tier_1":4}},
                      "tank":{"capacityPerRow":500,"stackRatio":0.5,"transferPerRow":25,"minimumTransfer":10},
                      "battery":{"capacityPerRow":1000,"stackRatio":0,"transferPerRow":7}}}
                    """));
            BagInventory storage = bag(BackpackTier.NETHERITE, UpgradeKind.TANK, UpgradeKind.BATTERY, UpgradeKind.STACK_UPGRADE_TIER_1);
            var tank = new BackpackTank(storage, upgrade(storage, 0), true);
            var battery = new BackpackBattery(storage, upgrade(storage, 1));
            FluidVariant water = FluidVariant.of(Fluids.WATER);
            helper.assertValueEqual(tank.getCapacity(), FluidAmount.dropletsForMb(17_500), "The actual tank uses configured per-row capacity and stack ratio");
            helper.assertValueEqual(battery.getCapacity(), 10_000L, "Zero battery ratio ignores item multipliers");
            try (Transaction transaction = Transaction.openOuter()) {
                helper.assertValueEqual(tank.insert(water, Long.MAX_VALUE, transaction), FluidAmount.dropletsForMb(875), "A real fluid transaction is limited by the configured transfer rate");
                helper.assertValueEqual(battery.insert(Long.MAX_VALUE, transaction), 70L, "A real energy transaction is limited by the configured transfer rate");
            }
            helper.assertValueEqual(tank.getAmount(), 0L, "Aborting configured fluid transfer restores every droplet");
            helper.assertValueEqual(battery.getAmount(), 0L, "Aborting configured energy transfer restores every unit");
            try (Transaction transaction = Transaction.openOuter()) {
                new BackpackTank(storage, upgrade(storage, 0), false).insert(water, tank.getCapacity(), transaction);
                battery.insert(Long.MAX_VALUE, transaction);
                transaction.commit();
            }
            BackpackConfig.configure(ConfigFile.decode("{\"upgrades\":{\"tank\":{\"capacityPerRow\":500,\"stackRatio\":0,\"transferPerRow\":25,\"minimumTransfer\":10}}}"));
            helper.assertValueEqual(tank.getCapacity(), FluidAmount.dropletsForMb(5_000), "An existing handle observes the new server capacity");
            helper.assertValueEqual(tank.getAmount(), FluidAmount.dropletsForMb(17_500), "A smaller capacity never deletes existing fluid");
            try (Transaction transaction = Transaction.openOuter()) {
                helper.assertValueEqual(tank.insert(water, 1, transaction), 0L, "Over-capacity tanks cannot accept new fluid");
                helper.assertValueEqual(tank.extract(water, Long.MAX_VALUE, transaction), FluidAmount.dropletsForMb(250), "Existing overflow remains extractable at the current server rate");
                transaction.commit();
            }
            BagInventory loaded = BagInventory.of(roundTrip(helper.getLevel(), storage.stack()));
            helper.assertValueEqual(ResourceRuntime.tankStoredMb(loaded, 0), 17_250L, "A real save preserves the remaining overflow quantity");
            helper.assertValueEqual(ResourceRuntime.batteryStored(loaded, 1), 70L, "Changing another resource's configuration does not alter stored energy");

            BackpackConfig.configure(ConfigFile.decode("{\"upgrades\":{\"experience\":{\"interval\":1,\"transferPoints\":3,\"allowMending\":false,\"mendingPoints\":2}}}"));
            var player = player(helper);
            ItemStack tool = new ItemStack(Items.DIAMOND_PICKAXE);
            tool.setDamageValue(20);
            tool.enchant(helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.MENDING), 1);
            player.getInventory().setItem(0, tool);
            BagInventory xp = bag(BackpackTier.NETHERITE, UpgradeKind.TANK, UpgradeKind.XP_PUMP);
            helper.assertValueEqual(ResourceRuntime.offerExperience(xp, 20), 20L, "The XP fixture owns twenty real points in fluid storage");
            xp.updateSettings(upgrade(xp, 1), tag -> {
                tag.putString("direction", "off"); tag.putInt("mending_points", Integer.MAX_VALUE);
                tag.putInt("transfer_points", Integer.MAX_VALUE);
            });
            ResourceRuntime.tick(xp, helper.getLevel(), player.blockPosition(), player);
            helper.assertValueEqual(tool.getDamageValue(), 20, "A saved enabled flag cannot bypass the server mending veto");
            helper.assertValueEqual(ResourceRuntime.tankStoredMb(xp, 0), 400L, "Disabled mending leaves all stored XP untouched");
            BackpackConfig.configure(ConfigFile.decode("{\"upgrades\":{\"experience\":{\"interval\":1,\"transferPoints\":3,\"allowMending\":true,\"mendingPoints\":2}}}"));
            ResourceRuntime.tick(xp, helper.getLevel(), player.blockPosition(), player);
            helper.assertValueEqual(tool.getDamageValue(), 16, "A forged point budget is capped to two vanilla Mending XP");
            helper.assertValueEqual(ResourceRuntime.tankStoredMb(xp, 0), 360L, "Two repaired XP cost exactly forty millibuckets");
            xp.updateSettings(upgrade(xp, 1), tag -> { tag.putString("direction", "output"); tag.putInt("target", 1); tag.putBoolean("mending", false); });
            player.setExperienceLevels(0); player.setExperiencePoints(0); player.totalExperience = 0;
            ResourceRuntime.tick(xp, helper.getLevel(), player.blockPosition(), player);
            helper.assertValueEqual(player.totalExperience, 3, "Automatic XP exchange cannot exceed the configured server budget");
            helper.assertValueEqual(ResourceRuntime.tankStoredMb(xp, 0), 300L, "The limited exchange conserves the same three points in fluid");

            String pumpRules = "{\"upgrades\":{\"pump\":{\"playerRange\":1,\"worldRange\":1,\"handTicks\":7,\"handlerTicks\":11,\"idleTicks\":13,\"handGraceTicks\":17}}}";
            BackpackConfig.configure(ConfigFile.decode(pumpRules));
            BagInventory pump = bag(BackpackTier.NETHERITE, UpgradeKind.TANK, UpgradeKind.ADVANCED_PUMP);
            var pumping = upgrade(pump, 1);
            BlockPos origin = helper.absolutePos(new BlockPos(1, 1, 1));
            long now = helper.getLevel().getGameTime();
            pump.updateSettings(pumping, tag -> tag.putBoolean("handlers", false));
            player.getInventory().setItem(0, ItemStack.EMPTY);
            ResourceRuntime.tick(pump, helper.getLevel(), origin, player);
            helper.assertValueEqual(pump.settings(pumping).getLongOr("next_work", 0), now + 13, "An idle pump uses its configured work delay");
            pump.updateSettings(pumping, tag -> tag.putLong("next_work", 0));
            player.getInventory().setItem(0, new ItemStack(Items.WATER_BUCKET));
            ResourceRuntime.tick(pump, helper.getLevel(), origin, player);
            assertStack(helper, player.getInventory().getItem(0), Items.BUCKET, 1, "Configured hand work exchanges one real bucket");
            helper.assertValueEqual(pump.settings(pumping).getLongOr("next_work", 0), now + 7, "Successful hand work uses its configured cooldown");
            helper.assertValueEqual(pump.settings(pumping).getLongOr("fast_until", 0), now + 17, "Fast polling uses the configured finite grace period");
            helper.getLevel().setBlockAndUpdate(origin.east(), Blocks.WATER.defaultBlockState());
            pump.updateSettings(pumping, tag -> { tag.putBoolean("hands", false); tag.putBoolean("world", true); tag.putLong("next_work", 0); });
            ResourceRuntime.tick(pump, helper.getLevel(), origin, player);
            helper.assertTrue(helper.getLevel().getFluidState(origin.east()).isSource(), "The exclusive configured world boundary rejects an adjacent source at radius one");
            BackpackConfig.configure(ConfigFile.decode(pumpRules.replace("\"worldRange\":1", "\"worldRange\":2")));
            pump.updateSettings(pumping, tag -> tag.putLong("next_work", 0));
            ResourceRuntime.tick(pump, helper.getLevel(), origin, player);
            helper.assertTrue(helper.getLevel().getBlockState(origin.east()).isAir(), "The same real source becomes available inside radius two");
            helper.assertValueEqual(ResourceRuntime.tankStoredMb(pump, 0), 2_000L, "Hand and world pumping conserve both full buckets");
            helper.assertValueEqual(pump.settings(pumping).getLongOr("next_work", 0), now + 12, "World delay combines configured handler cadence with actual distance");
        } finally { BackpackConfig.configure(previous); }
        helper.succeed();
    }

    public static void configuredUpgradeRanges(GameTestHelper helper) {
        ServerConfig previous = BackpackConfig.get();
        try {
            BackpackConfig.configure(ConfigFile.decode("""
                    {"upgrades":{"magnet":{"range":1,"advancedRange":2,"activeTicks":7,"idleTicks":13},
                      "feeding":{"range":1,"idleTicks":17,"hungryTicks":7},"refill":{"range":1,"interval":1},
                      "alchemy":{"range":1,"interval":1},"allowAlwaysVoid":false}}
                    """));
            BlockPos origin = helper.absolutePos(new BlockPos(1, 1, 1));
            long now = helper.getLevel().getGameTime();
            ItemEntity near = configDrop(helper, origin, 1.5, Items.DIAMOND);
            ItemEntity middle = configDrop(helper, origin, 2.5, Items.EMERALD);
            ItemEntity far = configDrop(helper, origin, 4.5, Items.AMETHYST_SHARD);
            BagInventory magnet = bag(BackpackTier.LEATHER, UpgradeKind.MAGNET);
            UpgradeEngine.tick(magnet, helper.getLevel(), origin, null);
            helper.assertFalse(near.isAlive(), "A basic magnet collects inside its configured volume");
            helper.assertTrue(middle.isAlive() && far.isAlive(), "Basic magnet collection does not exceed its server range");
            helper.assertValueEqual(magnet.settings(upgrade(magnet, 0)).getLongOr("magnet_next", 0), now + 7, "Successful magnet work uses the configured cadence");
            BagInventory advanced = bag(BackpackTier.LEATHER, UpgradeKind.ADVANCED_MAGNET);
            UpgradeEngine.tick(advanced, helper.getLevel(), origin, null);
            helper.assertFalse(middle.isAlive(), "The advanced magnet uses its independently configured larger range");
            helper.assertTrue(far.isAlive(), "The advanced range is also bounded");
            BagInventory idle = bag(BackpackTier.LEATHER, UpgradeKind.MAGNET);
            UpgradeEngine.tick(idle, helper.getLevel(), origin, null);
            helper.assertValueEqual(idle.settings(upgrade(idle, 0)).getLongOr("magnet_next", 0), now + 13, "Failed magnet work backs off by the configured interval");
            far.discard();

            var player = player(helper);
            player.setPos(Vec3.atCenterOf(origin).add(3, 0, 0));
            player.getFoodData().setFoodLevel(10);
            BagInventory feeding = bag(BackpackTier.LEATHER, UpgradeKind.FEEDING);
            feeding.setItem(0, new ItemStack(Items.APPLE, 2));
            ConsumptionRuntime.feed(feeding, upgrade(feeding, 0), helper.getLevel(), origin, null);
            assertStack(helper, feeding.getItem(0), Items.APPLE, 2, "Placed feeding ignores players outside the configured volume");
            helper.assertValueEqual(feeding.settings(upgrade(feeding, 0)).getLongOr("feeding_next", 0), now + 17, "Feeding idle time comes from server configuration");
            player.setPos(Vec3.atCenterOf(origin).add(1, 0, 0));
            feeding.updateSettings(upgrade(feeding, 0), tag -> tag.putLong("feeding_next", 0));
            ConsumptionRuntime.feed(feeding, upgrade(feeding, 0), helper.getLevel(), origin, null);
            assertStack(helper, feeding.getItem(0), Items.APPLE, 1, "Entering the configured feeding volume consumes exactly one food");
            helper.assertValueEqual(feeding.settings(upgrade(feeding, 0)).getLongOr("feeding_next", 0), now + 7, "A still-hungry player uses the configured shorter feeding delay");

            BagInventory refill = bag(BackpackTier.LEATHER, UpgradeKind.REFILL);
            refill.setItem(0, new ItemStack(Items.TORCH, 4)); refill.setFilter(upgrade(refill, 0), 0, new ItemStack(Items.TORCH));
            player.setPos(Vec3.atCenterOf(origin).add(3, 0, 0));
            TransferRuntime.refill(refill, upgrade(refill, 0), helper.getLevel(), origin, null);
            helper.assertValueEqual(count(player.getInventory(), Items.TORCH), 0, "Placed refill observes its own configured player range");
            player.setPos(Vec3.atCenterOf(origin).add(1, 0, 0));
            TransferRuntime.refill(refill, upgrade(refill, 0), helper.getLevel(), origin, null);
            helper.assertValueEqual(count(player.getInventory(), Items.TORCH), 4, "Configured refill transfers owned items once a player enters range");

            BagInventory alchemy = bag(BackpackTier.LEATHER, UpgradeKind.ALCHEMY);
            ItemStack healing = new ItemStack(Items.SPLASH_POTION);
            healing.set(DataComponents.POTION_CONTENTS, new PotionContents(Potions.HEALING));
            alchemy.setItem(0, healing); alchemy.setFilter(upgrade(alchemy, 0), 0, healing);
            player.setHealth(4); player.setPos(Vec3.atCenterOf(origin).add(3, 0, 0));
            AlchemyRuntime.tick(alchemy, upgrade(alchemy, 0), helper.getLevel(), origin, null);
            helper.assertFalse(alchemy.getItem(0).isEmpty(), "Placed alchemy cannot target outside its configured range");
            player.setPos(Vec3.atCenterOf(origin).add(1, 0, 0));
            AlchemyRuntime.tick(alchemy, upgrade(alchemy, 0), helper.getLevel(), origin, null);
            helper.assertTrue(alchemy.getItem(0).isEmpty(), "A real immediate splash is used inside configured range");

            BagInventory voider = bag(BackpackTier.LEATHER, UpgradeKind.VOID);
            voider.setFilter(upgrade(voider, 0), 0, new ItemStack(Items.DIRT));
            voider.updateSettings(upgrade(voider, 0), tag -> tag.putString("void_mode", "ALWAYS"));
            UpgradeEngine.insert(voider, new ItemStack(Items.DIRT, 4), false);
            helper.assertValueEqual(count(voider, Items.DIRT), 4, "Server-disabled ALWAYS cannot be restored by a saved item preference");
        } finally { BackpackConfig.configure(previous); }
        helper.succeed();
    }

    private static ItemEntity configDrop(GameTestHelper helper, BlockPos origin, double x, net.minecraft.world.item.Item item) {
        ItemEntity entity = new ItemEntity(helper.getLevel(), origin.getX() + x, origin.getY() + .5, origin.getZ() + .5, new ItemStack(item));
        entity.setNoPickUpDelay(); helper.getLevel().addFreshEntity(entity); return entity;
    }

    public static void configuredMagnetExperienceCadence(GameTestHelper helper) {
        ServerConfig previous = BackpackConfig.get();
        ServerConfig rules = ConfigFile.decode("""
                {"upgrades":{"magnet":{"range":1,"advancedRange":2,"activeTicks":7,"idleTicks":13}}}
                """);
        BlockPos origin = helper.absolutePos(new BlockPos(1, 2, 1));
        BagInventory magnet = bag(BackpackTier.NETHERITE, UpgradeKind.TANK, UpgradeKind.MAGNET);
        InstalledUpgrade control = upgrade(magnet, 1);
        BagInventory advanced = bag(BackpackTier.NETHERITE, UpgradeKind.TANK, UpgradeKind.ADVANCED_MAGNET);
        long started = helper.getLevel().getGameTime();
        ExperienceOrb first = configOrb(helper, origin, 1.5, 5);
        ExperienceOrb distant = configOrb(helper, origin, 2.5, 7);
        try {
            BackpackConfig.configure(rules);
            ResourceRuntime.tick(magnet, helper.getLevel(), origin, null);
            helper.assertTrue(first.isRemoved(), "Basic XP collection uses the configured radius");
            helper.assertFalse(distant.isRemoved(), "The basic XP radius excludes a more distant orb");
            helper.assertValueEqual(ResourceRuntime.tankStoredMb(magnet, 0), 100L, "Five real orb points become exactly 100 mB");
            helper.assertValueEqual(magnet.settings(control).getLongOr("magnet_xp_next", 0), started + 7, "XP uses the configured active cadence independently of item work");
            ResourceRuntime.tick(advanced, helper.getLevel(), origin, null);
            helper.assertTrue(distant.isRemoved(), "Advanced XP collection uses its configured larger radius");
            helper.assertValueEqual(ResourceRuntime.tankStoredMb(advanced, 0), 140L, "Advanced collection conserves all seven points");
        } finally { BackpackConfig.configure(previous); }
        ExperienceOrb pending = configOrb(helper, origin, 1.5, 3);
        Vec3 pendingPosition = pending.position();
        helper.onEachTick(() -> {
            ServerConfig beforeTick = BackpackConfig.get();
            try {
                BackpackConfig.configure(rules);
                long elapsed = helper.getLevel().getGameTime() - started;
                if (!pending.isRemoved()) helper.assertTrue(pending.position().distanceToSqr(pendingPosition) < 1e-12,
                        "The stationary XP cadence fixture must remain in its original collection volume before work; elapsed="
                                + elapsed + ", expected=" + pendingPosition + ", actual=" + pending.position());
                ResourceRuntime.tick(magnet, helper.getLevel(), origin, null);
                if (elapsed < 7) {
                    helper.assertFalse(pending.isRemoved(), "XP cadence prevents work before its configured deadline");
                    helper.assertValueEqual(ResourceRuntime.tankStoredMb(magnet, 0), 100L, "Waiting for the cadence cannot create stored experience");
                } else {
                    helper.assertTrue(pending.isRemoved(), "XP work resumes at the configured seven-tick deadline, not a fixed ten-tick boundary");
                    helper.assertValueEqual(ResourceRuntime.tankStoredMb(magnet, 0), 160L, "Both XP collections conserve eight total points");
                    if (elapsed < 14) helper.assertValueEqual(magnet.settings(control).getLongOr("magnet_xp_next", 0), started + 14, "Successful repeated XP work keeps its active cadence");
                }
                if (elapsed >= 14) {
                    helper.assertValueEqual(magnet.settings(control).getLongOr("magnet_xp_next", 0), started + 27, "An empty scan changes to the configured idle cadence");
                    magnet.updateSettings(control, tag -> tag.putLong("magnet_xp_next", Long.MAX_VALUE));
                    ResourceRuntime.tick(magnet, helper.getLevel(), origin, null);
                    helper.assertValueEqual(magnet.settings(control).getLongOr("magnet_xp_next", 0), helper.getLevel().getGameTime() + 13, "A stale future clock cannot suspend XP collection indefinitely");
                    helper.succeed();
                }
            } finally { BackpackConfig.configure(beforeTick); }
        });
    }

    private static ExperienceOrb configOrb(GameTestHelper helper, BlockPos origin, double x, int points) {
        ExperienceOrb orb = new ExperienceOrb(helper.getLevel(), new Vec3(origin.getX() + x, origin.getY() + .5, origin.getZ() + .5), Vec3.ZERO, points);
        // The constructor's third vector biases random launch direction; it does not set velocity.
        orb.setNoGravity(true);
        orb.setDeltaMovement(Vec3.ZERO);
        helper.getLevel().addFreshEntity(orb);
        return orb;
    }

    public static void configuredJukeboxResize(GameTestHelper helper) {
        ServerConfig previous = BackpackConfig.get();
        BagInventory bag;
        InstalledUpgrade upgrade;
        BlockPos position = helper.absolutePos(new BlockPos(1, 2, 1));
        long originalFinish;
        try {
            BackpackConfig.configure(ServerConfig.defaults());
            bag = bag(BackpackTier.NETHERITE, UpgradeKind.ADVANCED_JUKEBOX);
            upgrade = upgrade(bag, 0);
            helper.assertValueEqual(bag.upgradeInventory(upgrade).getContainerSize(), 24,
                    "A fresh advanced jukebox starts with the doubled twenty-four-slot default");
            bag.upgradeInventory(upgrade).setItem(0, new ItemStack(Items.MUSIC_DISC_CAT));
            bag.upgradeInventory(upgrade).setItem(5, new ItemStack(Items.MUSIC_DISC_BLOCKS));
            JukeboxRuntime.action(bag, upgrade, helper.getLevel(), position, null, "play");
            JukeboxRuntime.action(bag, upgrade, helper.getLevel(), position, null, "next");
            helper.assertValueEqual(bag.settings(upgrade).getIntOr("active_slot", -1), 5, "The doubled twenty-four-slot default has active audio and history");
            originalFinish = bag.settings(upgrade).getLongOr("song_finish", 0);
        } finally { BackpackConfig.configure(previous); }
        helper.runAfterDelay(2, () -> {
            ServerConfig beforeTick = BackpackConfig.get();
            try {
                BackpackConfig.configure(ConfigFile.decode("{\"upgrades\":{\"jukebox\":{\"size\":200,\"rowWidth\":6}}}"));
                var expanded = bag.upgradeInventory(upgrade);
                helper.assertValueEqual(expanded.getContainerSize(), 200, "Live server configuration expands the real disc inventory to a 200-record library");
                expanded.setItem(198, new ItemStack(Items.MUSIC_DISC_FAR));
                expanded.setItem(199, new ItemStack(Items.MUSIC_DISC_CHIRP));
                JukeboxRuntime.tick(bag, upgrade, helper.getLevel(), position, null);
                helper.assertValueEqual(bag.settings(upgrade).getIntOr("active_slot", -1), 5, "Growing an existing session retains its unchanged active disc");
                helper.assertValueEqual(bag.settings(upgrade).getLongOr("song_finish", 0), originalFinish, "A later resize tick does not restart active audio");
                JukeboxRuntime.action(bag, upgrade, helper.getLevel(), position, null, "next");
                helper.assertValueEqual(bag.settings(upgrade).getIntOr("active_slot", -1), 198, "Expanded queue includes actual new high-index records");
                JukeboxRuntime.action(bag, upgrade, helper.getLevel(), position, null, "next");
                helper.assertValueEqual(bag.settings(upgrade).getIntOr("active_slot", -1), 199, "Playback reaches the final physical slot in a 200-record library");
                JukeboxRuntime.action(bag, upgrade, helper.getLevel(), position, null, "previous");
                helper.assertValueEqual(bag.settings(upgrade).getIntOr("active_slot", -1), 198, "Playback history returns from physical slot 199 to 198");
                JukeboxRuntime.action(bag, upgrade, helper.getLevel(), position, null, "previous");
                helper.assertValueEqual(bag.settings(upgrade).getIntOr("active_slot", -1), 5, "Playback history remains usable after expansion");
                JukeboxRuntime.action(bag, upgrade, helper.getLevel(), position, null, "shuffle");
                JukeboxRuntime.action(bag, upgrade, helper.getLevel(), position, null, "repeat");
                BackpackConfig.configure(ConfigFile.decode("{\"upgrades\":{\"jukebox\":{\"size\":2}}}"));
                JukeboxRuntime.tick(bag, upgrade, helper.getLevel(), position, null);
                helper.assertTrue(bag.settings(upgrade).getBooleanOr("playing", false), "A smaller configured default cannot truncate the saved disc inventory or stop an unaffected song");
                helper.assertValueEqual(bag.upgradeInventory(upgrade).getContainerSize(), 200, "Existing 200-slot auxiliary extent preserves all owned discs");
                JukeboxRuntime.stopUpgrade(bag, upgrade.slot(), helper.getLevel().getServer());
                BagInventory restored = BagInventory.of(roundTrip(helper.getLevel(), bag.stack()));
                InstalledUpgrade restoredUpgrade = upgrade(restored, 0);
                JukeboxRuntime.tick(restored, restoredUpgrade, helper.getLevel(), position, null);
                helper.assertFalse(restored.settings(restoredUpgrade).getBooleanOr("playing", true), "Restored preferences never restart missing audio instances");
                helper.assertTrue(restored.settings(restoredUpgrade).getBooleanOr("shuffle", false), "Resize and component persistence preserve shuffle");
                helper.assertValueEqual(restored.settings(restoredUpgrade).getStringOr("repeat", ""), "ALL", "Resize and persistence preserve repeat");
                helper.assertValueEqual(count(restored.upgradeInventory(restoredUpgrade), Items.MUSIC_DISC_FAR), 1, "High-index physical records survive the whole resize/codec lifecycle");
                helper.assertValueEqual(count(restored.upgradeInventory(restoredUpgrade), Items.MUSIC_DISC_CHIRP), 1, "The final physical slot remains conserved");
                JukeboxRuntime.stopUpgrade(restored, restoredUpgrade.slot(), helper.getLevel().getServer());
                helper.succeed();
            } finally { BackpackConfig.configure(beforeTick); }
        });
    }

    public static void configuredCompactingShapes(GameTestHelper helper) {
        ServerConfig previous = BackpackConfig.get();
        try {
            BackpackConfig.configure(ServerConfig.defaults());
            BagInventory bag = bag(BackpackTier.LEATHER, UpgradeKind.COMPACTING);
            bag.setItem(0, new ItemStack(Items.CLAY_BALL, 6));
            helper.assertValueEqual(CompactingRuntime.compact(bag, upgrade(bag, 0), helper.getLevel(), 20), 0, "A non-square fixture is not enabled by ordinary square compaction");
            BackpackConfig.configure(ConfigFile.decode("""
                    {"upgrades":{"compacting":{"maximumOperations":1,"extraShapes":[],
                      "itemOverrides":{"minecraft:clay_ball":[{"width":2,"height":2,"pattern":"1110"}]}}}}
                    """));
            helper.assertValueEqual(CompactingRuntime.compact(bag, upgrade(bag, 0), helper.getLevel(), 256), 1, "A server item override enables its real reversible recipe and caps the batch");
            helper.assertValueEqual(count(bag, Items.CLAY), 1, "One configured compacted output is retained");
            helper.assertValueEqual(count(bag, Items.CLAY_BALL), 3, "Only the configured recipe's three ingredients are spent");
            helper.assertValueEqual(CompactingRuntime.compact(bag, upgrade(bag, 0), helper.getLevel(), 256), 1, "Remaining inputs can be processed in the next bounded operation");
            helper.assertValueEqual(count(bag, Items.CLAY), 2, "Both real outputs remain conserved");
        } finally { BackpackConfig.configure(previous); }
        helper.succeed();
    }
}
