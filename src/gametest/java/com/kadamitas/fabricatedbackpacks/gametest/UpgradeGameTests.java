package com.kadamitas.fabricatedbackpacks.gametest;

import com.kadamitas.fabricatedbackpacks.compat.NbtAccess;
import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.equipment.BackpackEquipment;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.storage.InstalledUpgrade;
import com.kadamitas.fabricatedbackpacks.upgrade.AlchemyRuntime;
import com.kadamitas.fabricatedbackpacks.upgrade.CompactingRuntime;
import com.kadamitas.fabricatedbackpacks.upgrade.ConsumptionRuntime;
import com.kadamitas.fabricatedbackpacks.upgrade.CookingRuntime;
import com.kadamitas.fabricatedbackpacks.upgrade.InventoryMoves;
import com.kadamitas.fabricatedbackpacks.upgrade.JukeboxRuntime;
import com.kadamitas.fabricatedbackpacks.upgrade.TransferRuntime;
import com.kadamitas.fabricatedbackpacks.upgrade.ToolRuntime;
import com.kadamitas.fabricatedbackpacks.upgrade.UpgradeAccess;
import com.kadamitas.fabricatedbackpacks.upgrade.UpgradeEngine;
import com.kadamitas.fabricatedbackpacks.upgrade.UpgradeFilters;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.JukeboxPlayable;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** Registered by the test entry point. These exercise real registries, recipes, entities and component saves. */
public final class UpgradeGameTests {
    private UpgradeGameTests() { }

    private static BagInventory bag(UpgradeKind kind) {
        BagInventory bag = BagInventory.of(new ItemStack(BackpackRegistry.item(BackpackTier.NETHERITE)));
        bag.upgrades().setItem(0, new ItemStack(BackpackRegistry.item(kind)));
        return bag;
    }
    private static InstalledUpgrade upgrade(BagInventory bag) { return bag.installedUpgrades().getFirst(); }
    private static int count(Container inventory, Item item) { return InventoryMoves.count(inventory, new ItemStack(item)); }

    public static void filters(GameTestHelper helper) {
        BagInventory bag = bag(UpgradeKind.ADVANCED_FILTER);
        InstalledUpgrade upgrade = upgrade(bag);
        helper.assertTrue(UpgradeFilters.matches(bag, upgrade, new ItemStack(Items.DIAMOND)), "Empty default block list accepts eligible items");
        helper.assertFalse(UpgradeFilters.matches(bag, upgrade, ItemStack.EMPTY), "Empty resources never match");
        bag.updateSettings(upgrade, state -> state.putString("filter_mode", "ALLOW"));
        helper.assertFalse(UpgradeFilters.matches(bag, upgrade, new ItemStack(Items.DIAMOND)), "Empty allow list rejects everything");
        bag.setFilter(upgrade, 0, new ItemStack(Items.OAK_PLANKS));
        bag.updateSettings(upgrade, state -> state.putString("filter_match", "NAMESPACE"));
        helper.assertTrue(UpgradeFilters.matches(bag, upgrade, new ItemStack(Items.DIAMOND)), "Namespace matching uses actual registry namespace");
        bag.updateSettings(upgrade, state -> { state.putString("filter_match", "TAGS"); state.putString("tags", "minecraft:planks"); });
        helper.assertTrue(UpgradeFilters.matches(bag, upgrade, new ItemStack(Items.BIRCH_PLANKS)), "Registry tag matching includes birch planks");
        helper.assertFalse(UpgradeFilters.matches(bag, upgrade, new ItemStack(Items.DIAMOND)), "Registry tag matching rejects unrelated items");
        bag.updateSettings(upgrade, state -> state.putString("tags", ""));
        helper.assertFalse(UpgradeFilters.matches(bag, upgrade, new ItemStack(Items.DIAMOND)), "Empty ANY tags rejects");
        bag.updateSettings(upgrade, state -> state.putString("tag_match", "ALL"));
        helper.assertTrue(UpgradeFilters.matches(bag, upgrade, new ItemStack(Items.DIAMOND)), "Empty ALL tags accepts nonempty resource");
        ItemStack tool = new ItemStack(Items.IRON_PICKAXE);
        tool.setDamageValue(1);
        tool.set(DataComponents.CUSTOM_NAME, Component.literal("Remembered tool"));
        bag.setFilter(upgrade, 0, tool);
        bag.updateSettings(upgrade, state -> { state.putString("filter_match", "ITEM"); state.putBoolean("match_components", true); });
        ItemStack damaged = tool.copy();
        damaged.setDamageValue(2);
        helper.assertTrue(UpgradeFilters.matches(bag, upgrade, damaged), "Component comparison excludes independent damage component");
        damaged.set(DataComponents.CUSTOM_NAME, Component.literal("Different tool"));
        helper.assertFalse(UpgradeFilters.matches(bag, upgrade, damaged), "Custom names participate in component matching");
        bag.updateSettings(upgrade, state -> { state.putBoolean("match_components", false); state.putBoolean("match_damage", true); });
        helper.assertFalse(UpgradeFilters.matches(bag, upgrade, damaged), "Damage toggle enforces durability equality");
        bag.remember(7, new ItemStack(Items.REDSTONE));
        bag.updateSettings(upgrade, state -> { state.putString("filter_mode", "CONTENTS"); state.putBoolean("match_damage", false); });
        helper.assertTrue(UpgradeFilters.matches(bag, upgrade, new ItemStack(Items.REDSTONE)), "Empty remembered slots participate in contents filters");

        BagInventory safeVoid = bag(UpgradeKind.VOID);
        helper.assertTrue(UpgradeEngine.insert(safeVoid, new ItemStack(Items.DIAMOND, 8), false).isEmpty(), "Default void permits ordinary storage insertion");
        helper.assertValueEqual(count(safeVoid, Items.DIAMOND), 8, "Default empty void allow list cannot delete items");
        InstalledUpgrade voider = upgrade(safeVoid);
        bagVoidSettings(safeVoid, voider, "SLOT_OVERFLOW");
        helper.assertTrue(UpgradeEngine.insert(safeVoid, new ItemStack(Items.COBBLESTONE, 60), false).isEmpty(), "First slot representation is retained");
        helper.assertTrue(UpgradeEngine.insert(safeVoid, new ItemStack(Items.COBBLESTONE, 20), false).isEmpty(), "Slot excess is intentionally handled");
        helper.assertValueEqual(count(safeVoid, Items.COBBLESTONE), 64, "Slot overflow does not spread excess into empty slots");
        bagVoidSettings(safeVoid, voider, "ALWAYS");
        UpgradeEngine.insert(safeVoid, new ItemStack(Items.COBBLESTONE, 20), false);
        helper.assertValueEqual(count(safeVoid, Items.COBBLESTONE), 64, "Always void does not add matching external items");
        BagInventory full = bag(UpgradeKind.VOID);
        for (int slot = 0; slot < full.getContainerSize(); slot++) full.setItem(slot, new ItemStack(Items.DIRT, 64));
        bagVoidSettings(full, upgrade(full), "SLOT_OVERFLOW");
        helper.assertValueEqual(UpgradeEngine.insert(full, new ItemStack(Items.COBBLESTONE, 5), false).getCount(), 5,
                "No first representation means slot-overflow insertion must remain lossless");
        bagVoidSettings(full, upgrade(full), "STORAGE_OVERFLOW");
        helper.assertTrue(UpgradeEngine.insert(full, new ItemStack(Items.COBBLESTONE, 5), false).isEmpty(), "Storage overflow handles excess only under explicit matching filter");
        helper.succeed();
    }

    public static void advancedFilterMatrix(GameTestHelper helper) {
        BagInventory bag = bag(UpgradeKind.ADVANCED_FILTER);
        InstalledUpgrade upgrade = upgrade(bag);
        ItemStack ghost = new ItemStack(Items.IRON_PICKAXE);
        ghost.setDamageValue(7);
        ghost.set(DataComponents.CUSTOM_NAME, Component.literal("Matrix tool"));
        var efficiency = helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.EFFICIENCY);
        ghost.enchant(efficiency, 1);
        bag.setFilter(upgrade, 0, ghost);
        ItemStack damaged = ghost.copy(); damaged.setDamageValue(9);
        ItemStack named = ghost.copy(); named.set(DataComponents.CUSTOM_NAME, Component.literal("Another tool"));
        ItemStack enchanted = ghost.copy(); enchanted.enchant(efficiency, 2);
        ItemStack diamond = new ItemStack(Items.DIAMOND_PICKAXE);
        diamond.setDamageValue(7); diamond.set(DataComponents.CUSTOM_NAME, ghost.get(DataComponents.CUSTOM_NAME)); diamond.enchant(efficiency, 1);
        ItemStack axe = new ItemStack(Items.IRON_AXE);
        axe.setDamageValue(7); axe.set(DataComponents.CUSTOM_NAME, ghost.get(DataComponents.CUSTOM_NAME)); axe.enchant(efficiency, 1);
        record Example(ItemStack stack, boolean item, boolean namespace, boolean pickaxe, boolean damage, boolean components) { }
        List<Example> examples = List.of(new Example(ghost.copyWithCount(3), true, true, true, true, true),
                new Example(damaged, true, true, true, false, true), new Example(named, true, true, true, true, false),
                new Example(enchanted, true, true, true, true, false), new Example(diamond, false, true, true, true, false),
                new Example(axe, false, true, false, true, false),
                new Example(new ItemStack(BackpackRegistry.item(BackpackTier.LEATHER)), false, false, false, false, false));
        helper.assertTrue(ghost.getTags().anyMatch(tag -> tag.location().toString().equals("minecraft:pickaxes")), "Matrix uses the actual vanilla pickaxes tag");
        for (String primary : List.of("ITEM", "NAMESPACE", "TAGS")) for (boolean block : List.of(false, true)) {
            for (boolean damage : List.of(false, true)) for (boolean components : List.of(false, true)) {
                bag.updateSettings(upgrade, state -> {
                    state.putString("filter_mode", block ? "BLOCK" : "ALLOW"); state.putString("filter_match", primary);
                    state.putString("tags", "minecraft:pickaxes"); state.putString("tag_match", "ANY");
                    state.putBoolean("match_damage", damage); state.putBoolean("match_components", components);
                });
                for (Example example : examples) {
                    boolean selected = switch (primary) { case "NAMESPACE" -> example.namespace(); case "TAGS" -> example.pickaxe(); default -> example.item(); };
                    selected &= (!damage || example.damage()) && (!components || example.components());
                    helper.assertValueEqual(UpgradeFilters.matches(bag, upgrade, example.stack()), block != selected,
                            "Filter matrix " + primary + "/block=" + block + "/damage=" + damage + "/components=" + components + "/" + example.stack());
                }
                helper.assertFalse(UpgradeFilters.matches(bag, upgrade, ItemStack.EMPTY), "Block inversion never admits an empty stack");
            }
        }
        bag.updateSettings(upgrade, state -> {
            state.putString("filter_mode", "ALLOW"); state.putString("filter_match", "TAGS");
            state.putString("tags", "minecraft:pickaxes,minecraft:enchantable/durability");
            state.putBoolean("match_damage", false); state.putBoolean("match_components", false);
        });
        helper.assertTrue(UpgradeFilters.matches(bag, upgrade, axe), "ANY tags forms a union of real registry tags");
        bag.updateSettings(upgrade, state -> state.putString("tag_match", "ALL"));
        helper.assertTrue(UpgradeFilters.matches(bag, upgrade, ghost), "ALL tags retains the intersection");
        helper.assertFalse(UpgradeFilters.matches(bag, upgrade, axe), "ALL tags requires the pickaxe tag as well as durability");
        bag.updateSettings(upgrade, state -> state.putString("tags", "fabricated_backpacks_tests:removed_tag"));
        helper.assertFalse(UpgradeFilters.matches(bag, upgrade, ghost), "Unavailable datapack tags cannot match by their textual name alone");

        ItemStack potion = PotionContents.createItemStack(Items.POTION, Potions.FIRE_RESISTANCE);
        ItemStack longer = PotionContents.createItemStack(Items.POTION, Potions.LONG_FIRE_RESISTANCE);
        bag.setFilter(upgrade, 0, potion);
        bag.updateSettings(upgrade, state -> { state.putString("filter_match", "ITEM"); state.putBoolean("match_components", true); });
        helper.assertTrue(UpgradeFilters.matches(bag, upgrade, potion), "Exact potion data matches");
        helper.assertFalse(UpgradeFilters.matches(bag, upgrade, longer), "Potion duration is a typed component difference");
        ItemStack container = new ItemStack(Items.SHULKER_BOX);
        container.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND, 3))));
        bag.setFilter(upgrade, 0, container);
        ItemStack altered = container.copy();
        altered.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND, 4))));
        helper.assertFalse(UpgradeFilters.matches(bag, upgrade, altered), "Nested container quantities participate in component matching");
        BagInventory loaded = BagInventory.of(BackpackTestSupport.roundTrip(helper.getLevel(), bag.stack()));
        helper.assertTrue(UpgradeFilters.matches(loaded, upgrade(loaded), container), "Component-rich ghost filters survive the real item codec");
        helper.assertFalse(UpgradeFilters.matches(loaded, upgrade(loaded), altered), "Reload does not flatten nested component comparisons");
        BackpackTestSupport.assertStack(helper, bag.ghost(upgrade, 0), container, "Ghost tests do not consume or mutate their exemplar");
        helper.succeed();
    }

    public static void automaticCookingFilterControls(GameTestHelper helper) {
        ServerPlayer player = BackpackTestSupport.player(helper);
        BagInventory bag = bag(UpgradeKind.AUTO_SMELTING);
        InstalledUpgrade upgrade = upgrade(bag);
        bag.setItem(0, new ItemStack(Items.OAK_LOG, 2));
        bag.setItem(1, new ItemStack(Items.COAL));
        bag.setItem(2, new ItemStack(Items.DIAMOND, 3));
        helper.assertTrue(UpgradeEngine.action(bag, 0, "input_filter_match", player), "Automatic input supports namespace matching");
        helper.assertTrue(UpgradeEngine.action(bag, 0, "input_filter_match", player), "Automatic input supports registry tags");
        helper.assertTrue(UpgradeEngine.action(bag, 0, "input_tag:minecraft:logs", player), "Input tag action is independently prefixed");
        helper.assertValueEqual(NbtAccess.getStringOr(bag.settings(upgrade), "input_tags", ""), "minecraft:logs", "Input tag action updates the input filter state");
        helper.assertValueEqual(NbtAccess.getStringOr(bag.settings(upgrade), "tags", ""), "", "Input tag action never changes another filter's tags");
        helper.assertFalse(UpgradeEngine.action(bag, 0, "fuel_tag:minecraft:logs", player), "Fuel does not expose unsupported advanced matching");
        helper.assertFalse(UpgradeEngine.action(bag, 0, "fuel_match_components", player), "Fuel has four basic ghost slots, not a fake advanced button");
        helper.assertFalse(UpgradeEngine.action(bag, 0, "input_tag:Bad Tag", player), "Malformed tag identifiers are rejected");
        helper.assertTrue(UpgradeEngine.action(bag, 0, "input_filter_mode", player), "Input mode can become a block list");
        helper.assertTrue(UpgradeEngine.action(bag, 0, "input_filter_mode", player), "Input mode can use contents");
        helper.assertValueEqual(NbtAccess.getStringOr(bag.settings(upgrade), "input_filter_match", ""), "ITEM", "Contents mode clears an incompatible tag primary");
        helper.assertTrue(UpgradeEngine.action(bag, 0, "input_filter_mode", player), "Input mode returns to allow");
        helper.assertTrue(UpgradeEngine.action(bag, 0, "input_filter_match", player), "Input primary cycles back to namespace");
        helper.assertTrue(UpgradeEngine.action(bag, 0, "input_filter_match", player), "Input primary cycles back to tags");
        helper.onEachTick(() -> UpgradeEngine.tick(bag, helper.getLevel(), player.blockPosition(), player));
        helper.runAfterDelay(12, () -> {
            helper.assertValueEqual(count(bag.upgradeInventory(upgrade), Items.OAK_LOG), 2, "Real automatic pull honors the configured input tag");
            helper.assertValueEqual(count(bag, Items.OAK_LOG), 0, "Tagged inputs move exactly once");
            helper.assertValueEqual(count(bag, Items.DIAMOND), 3, "Unrelated storage is untouched");
            helper.assertTrue(NbtAccess.getBooleanOr(bag.settings(upgrade), "burning", false), "The empty fuel allow list accepts actual coal fuel");
            helper.assertValueEqual(count(bag, Items.COAL) + count(bag.upgradeInventory(upgrade), Items.COAL), 0, "Exactly one coal fuels this operation");
            helper.succeed();
        });
    }

    private static void bagVoidSettings(BagInventory bag, InstalledUpgrade upgrade, String mode) {
        bag.setFilter(upgrade, 0, new ItemStack(Items.COBBLESTONE));
        bag.updateSettings(upgrade, state -> state.putString("void_mode", mode));
    }

    public static void cooking(GameTestHelper helper) {
        List<BagInventory> machines = new ArrayList<>();
        for (UpgradeKind kind : List.of(UpgradeKind.SMELTING, UpgradeKind.AUTO_SMELTING, UpgradeKind.SMOKING,
                UpgradeKind.AUTO_SMOKING, UpgradeKind.BLASTING, UpgradeKind.AUTO_BLASTING)) {
            BagInventory bag = bag(kind);
            InstalledUpgrade upgrade = upgrade(bag);
            Item input = kind == UpgradeKind.SMOKING || kind == UpgradeKind.AUTO_SMOKING ? Items.PORKCHOP : Items.RAW_IRON;
            if (CookingRuntime.automatic(kind)) {
                bag.setFilter(upgrade, 0, new ItemStack(input));
                bag.setItem(0, new ItemStack(input, 2));
                bag.setItem(1, new ItemStack(Items.COAL));
            } else {
                bag.upgradeInventory(upgrade).setItem(0, new ItemStack(input, 2));
                bag.upgradeInventory(upgrade).setItem(1, new ItemStack(Items.COAL));
            }
            machines.add(bag);
        }
        BagInventory wet = bag(UpgradeKind.AUTO_SMELTING);
        wet.setFilter(upgrade(wet), 0, new ItemStack(Items.WET_SPONGE));
        wet.setItem(0, new ItemStack(Items.WET_SPONGE));
        wet.setItem(1, new ItemStack(Items.LAVA_BUCKET));
        BagInventory blocked = bag(UpgradeKind.SMELTING);
        blocked.upgradeInventory(upgrade(blocked)).setItem(0, new ItemStack(Items.RAW_IRON));
        blocked.upgradeInventory(upgrade(blocked)).setItem(1, new ItemStack(Items.COAL));
        blocked.upgradeInventory(upgrade(blocked)).setItem(2, new ItemStack(Items.IRON_INGOT, 64));
        BagInventory freshAuto = bag(UpgradeKind.AUTO_SMELTING);
        freshAuto.setItem(0, new ItemStack(Items.RAW_IRON));
        freshAuto.setItem(1, new ItemStack(Items.COAL));
        helper.onEachTick(() -> {
            for (BagInventory bag : machines) CookingRuntime.tick(bag, upgrade(bag), helper.getLevel());
            CookingRuntime.tick(wet, upgrade(wet), helper.getLevel());
            CookingRuntime.tick(blocked, upgrade(blocked), helper.getLevel());
            CookingRuntime.tick(freshAuto, upgrade(freshAuto), helper.getLevel());
        });
        helper.runAtTickTime(430, () -> {
            for (BagInventory bag : machines) {
                InstalledUpgrade upgrade = upgrade(bag);
                Container slots = bag.upgradeInventory(upgrade);
                Item expected = upgrade.kind() == UpgradeKind.SMOKING || upgrade.kind() == UpgradeKind.AUTO_SMOKING ? Items.COOKED_PORKCHOP : Items.IRON_INGOT;
                helper.assertValueEqual(count(bag, expected) + count(slots, expected), 2, "Both items cook under " + upgrade.kind());
                helper.assertValueEqual(count(bag, Items.COAL) + count(slots, Items.COAL), 0, "Exactly one fuel is used under " + upgrade.kind());
            }
            helper.assertValueEqual(count(wet, Items.WATER_BUCKET) + count(wet.upgradeInventory(upgrade(wet)), Items.WATER_BUCKET), 1, "Auto wet sponge keeps the lava bucket remainder until water conversion");
            helper.assertValueEqual(count(wet, Items.SPONGE) + count(wet.upgradeInventory(upgrade(wet)), Items.SPONGE), 1, "Wet sponge output is real vanilla sponge");
            helper.assertValueEqual(count(blocked.upgradeInventory(upgrade(blocked)), Items.RAW_IRON), 1, "Blocked output leaves input untouched");
            helper.assertValueEqual(count(blocked.upgradeInventory(upgrade(blocked)), Items.COAL), 1, "Blocked output does not burn unused fuel");
            helper.assertValueEqual(count(freshAuto, Items.RAW_IRON), 1, "Fresh automatic input allow list never smelts arbitrary storage contents");
            BagInventory first = machines.getFirst();
            InstalledUpgrade upgrade = upgrade(first);
            double before = NbtAccess.getDoubleOr(first.settings(upgrade), "experience", 0);
            ServerPlayer player = BackpackTestSupport.player(helper);
            player.giveExperiencePoints(-player.totalExperience);
            CookingRuntime.claimExperience(first, upgrade, player);
            int awarded = player.totalExperience;
            CookingRuntime.claimExperience(first, upgrade, player);
            helper.assertValueEqual(player.totalExperience, awarded, "Claiming cooking XP twice cannot duplicate whole points");
            helper.assertTrue(Math.abs(awarded + NbtAccess.getDoubleOr(first.settings(upgrade), "experience", 0) - before) < 1e-6, "Fractional cooking XP is conserved");
            helper.succeed();
        });
    }

    private static final class PausedCooking {
        BagInventory bag;
        final UpgradeKind kind;
        ItemStack detached = ItemStack.EMPTY;
        CompoundTag paused;
        PausedCooking(UpgradeKind kind) { this.kind = kind; bag = bag(kind); }
        InstalledUpgrade upgrade() { return UpgradeGameTests.upgrade(bag); }
    }

    public static void cookingPausedPersistence(GameTestHelper helper) {
        ServerPlayer player = BackpackTestSupport.player(helper);
        List<PausedCooking> machines = new ArrayList<>();
        for (UpgradeKind kind : List.of(UpgradeKind.SMELTING, UpgradeKind.AUTO_SMELTING, UpgradeKind.SMOKING,
                UpgradeKind.AUTO_SMOKING, UpgradeKind.BLASTING, UpgradeKind.AUTO_BLASTING)) {
            PausedCooking fixture = new PausedCooking(kind);
            Item input = kind == UpgradeKind.SMOKING || kind == UpgradeKind.AUTO_SMOKING ? Items.PORKCHOP : Items.RAW_IRON;
            fixture.bag.upgradeInventory(fixture.upgrade()).setItem(CookingRuntime.INPUT, new ItemStack(input, 2));
            fixture.bag.upgradeInventory(fixture.upgrade()).setItem(CookingRuntime.FUEL, new ItemStack(Items.COAL, 2));
            machines.add(fixture);
        }
        helper.onEachTick(() -> {
            for (PausedCooking fixture : machines) UpgradeEngine.tick(fixture.bag, helper.getLevel(), player.blockPosition(), player);
        });
        helper.runAfterDelay(30, () -> {
            for (PausedCooking fixture : machines) {
                fixture.paused = fixture.bag.settings(fixture.upgrade());
                helper.assertTrue(NbtAccess.getIntOr(fixture.paused, "cook_progress", 0) > 0, "Cooking really started before removal: " + fixture.kind);
                helper.assertTrue(NbtAccess.getIntOr(fixture.paused, "burn_remaining", 0) > 0, "Fuel has been consumed before removal: " + fixture.kind);
                helper.assertTrue(fixture.bag.canRemoveUpgrade(fixture.upgrade().slot(), player), "A cooking upgrade carries its own occupied auxiliary slots");
                UpgradeEngine.stopUpgrade(fixture.bag, fixture.upgrade().slot(), helper.getLevel().getServer());
                ItemStack removed = fixture.bag.upgrades().removeItemNoUpdate(fixture.upgrade().slot());
                fixture.bag.upgrades().setChanged();
                fixture.detached = BackpackTestSupport.roundTrip(helper.getLevel(), removed);
            }
        });
        helper.runAfterDelay(60, () -> {
            for (PausedCooking fixture : machines) {
                helper.assertTrue(fixture.bag.installedUpgrades().isEmpty(), "Removed machine is absent from runtime dispatch");
                fixture.bag.upgrades().setItem(1, fixture.detached);
                helper.assertValueEqual(NbtAccess.getIntOr(fixture.bag.settings(fixture.upgrade()), "cook_progress", -1), NbtAccess.getIntOr(fixture.paused, "cook_progress", 0), "Remove/serialize/reinsert preserves partial work: " + fixture.kind);
                helper.assertValueEqual(NbtAccess.getIntOr(fixture.bag.settings(fixture.upgrade()), "burn_remaining", -1), NbtAccess.getIntOr(fixture.paused, "burn_remaining", 0), "Detached fuel is not silently burnt: " + fixture.kind);
                helper.assertValueEqual(count(fixture.bag.upgradeInventory(fixture.upgrade()), Items.COAL), 1, "Serialized fuel inventory contains only the unconsumed second coal");
            }
        });
        helper.runAfterDelay(80, () -> {
            for (PausedCooking fixture : machines) {
                helper.assertTrue(UpgradeEngine.action(fixture.bag, fixture.upgrade().slot(), "toggle", player), "Installed cooking can be paused");
                fixture.paused = fixture.bag.settings(fixture.upgrade());
                helper.assertFalse(NbtAccess.getBooleanOr(fixture.paused, "burning", true), "Pausing immediately clears the burning client state");
            }
        });
        helper.runAfterDelay(100, () -> {
            for (PausedCooking fixture : machines) {
                fixture.bag = BagInventory.of(BackpackTestSupport.roundTrip(helper.getLevel(), fixture.bag.stack()));
                helper.assertFalse(NbtAccess.getBooleanOr(fixture.bag.settings(fixture.upgrade()), "enabled", true), "Disabled state survives reconstruction");
                helper.assertValueEqual(NbtAccess.getIntOr(fixture.bag.settings(fixture.upgrade()), "cook_progress", -1), NbtAccess.getIntOr(fixture.paused, "cook_progress", 0), "Disabled work survives a new wrapper: " + fixture.kind);
                helper.assertValueEqual(NbtAccess.getIntOr(fixture.bag.settings(fixture.upgrade()), "burn_remaining", -1), NbtAccess.getIntOr(fixture.paused, "burn_remaining", 0), "Disabled fuel survives a new wrapper: " + fixture.kind);
            }
        });
        helper.runAfterDelay(120, () -> {
            for (PausedCooking fixture : machines) {
                helper.assertValueEqual(NbtAccess.getIntOr(fixture.bag.settings(fixture.upgrade()), "cook_progress", -1), NbtAccess.getIntOr(fixture.paused, "cook_progress", 0), "Reconstructed disabled machine stays paused");
                helper.assertTrue(UpgradeEngine.action(fixture.bag, fixture.upgrade().slot(), "toggle", player), "Reconstructed machine resumes through its normal action");
            }
        });
        helper.runAfterDelay(540, () -> {
            for (PausedCooking fixture : machines) {
                Container inventory = fixture.bag.upgradeInventory(fixture.upgrade());
                Item output = fixture.kind == UpgradeKind.SMOKING || fixture.kind == UpgradeKind.AUTO_SMOKING ? Items.COOKED_PORKCHOP : Items.IRON_INGOT;
                helper.assertValueEqual(count(fixture.bag, output) + count(inventory, output), 2, "Resumed machine completes both original inputs once: " + fixture.kind);
                helper.assertValueEqual(count(fixture.bag, Items.COAL) + count(inventory, Items.COAL), 1, "Pause and reconstruction never charge another fuel: " + fixture.kind);
                CompoundTag state = fixture.bag.settings(fixture.upgrade());
                helper.assertValueEqual(NbtAccess.getCompoundOrEmpty(state, "recipes_used").getAllKeys().stream().mapToInt(key -> NbtAccess.getIntOr(NbtAccess.getCompoundOrEmpty(state, "recipes_used"), key, 0)).sum(), 2, "Recipe use accounting survives pauses exactly");
                BagInventory roundTrip = BagInventory.of(BackpackTestSupport.roundTrip(helper.getLevel(), fixture.bag.stack()));
                helper.assertTrue(Math.abs(NbtAccess.getDoubleOr(roundTrip.settings(upgrade(roundTrip)), "experience", 0) - NbtAccess.getDoubleOr(state, "experience", 0)) < 1e-9, "Unclaimed fractional XP survives the completed machine codec");
            }
            helper.succeed();
        });
    }

    public static void compacting(GameTestHelper helper) {
        BagInventory basic = bag(UpgradeKind.COMPACTING);
        basic.setItem(0, new ItemStack(Items.IRON_NUGGET, 9));
        CompactingRuntime.compact(basic, upgrade(basic), helper.getLevel(), 64);
        helper.assertValueEqual(count(basic, Items.IRON_NUGGET), 9, "Basic compacting does not use 3 by 3 recipes");
        BagInventory advanced = bag(UpgradeKind.ADVANCED_COMPACTING);
        advanced.setItem(0, new ItemStack(Items.IRON_NUGGET, 40));
        advanced.setItem(1, new ItemStack(Items.IRON_NUGGET, 41));
        CompactingRuntime.compact(advanced, upgrade(advanced), helper.getLevel(), 64);
        helper.assertValueEqual(count(advanced, Items.IRON_BLOCK), 1, "Compacting gathers split ingredients and cascades nuggets through ingots into a block");
        helper.assertValueEqual(count(advanced, Items.IRON_NUGGET) + count(advanced, Items.IRON_INGOT), 0, "Cascading consumes exactly the reversible ingredient count");
        BagInventory sand = bag(UpgradeKind.COMPACTING);
        sand.setItem(0, new ItemStack(Items.SAND, 4));
        CompactingRuntime.compact(sand, upgrade(sand), helper.getLevel(), 64);
        helper.assertValueEqual(count(sand, Items.SAND), 4, "Irreversible recipes are rejected by default");
        sand.updateSettings(upgrade(sand), state -> state.putBoolean("compact_anything", true));
        CompactingRuntime.compact(sand, upgrade(sand), helper.getLevel(), 64);
        helper.assertValueEqual(count(sand, Items.SANDSTONE), 1, "Explicit compact-anything permits an ordinary 2 by 2 recipe");
        BagInventory honey = bag(UpgradeKind.COMPACTING);
        honey.setItem(0, new ItemStack(Items.HONEY_BOTTLE, 4));
        honey.updateSettings(upgrade(honey), state -> state.putBoolean("compact_anything", true));
        CompactingRuntime.compact(honey, upgrade(honey), helper.getLevel(), 64);
        helper.assertValueEqual(count(honey, Items.HONEY_BLOCK), 1, "Compacting uses the vanilla honey block recipe");
        helper.assertValueEqual(count(honey, Items.GLASS_BOTTLE), 4, "All four crafting remainder bottles are retained");
        BagInventory full = bag(UpgradeKind.ADVANCED_COMPACTING);
        for (int slot = 0; slot < full.getContainerSize(); slot++) full.setItem(slot, new ItemStack(Items.DIRT, 64));
        full.setItem(0, new ItemStack(Items.IRON_NUGGET, 64));
        full.setItem(1, new ItemStack(Items.IRON_NUGGET, 64));
        helper.assertValueEqual(CompactingRuntime.compact(full, upgrade(full), helper.getLevel(), 64), 0, "No output space aborts compaction");
        helper.assertValueEqual(count(full, Items.IRON_NUGGET), 128, "Aborted recipe preserves every ingredient");
        helper.succeed();
    }

    public static void magnet(GameTestHelper helper) {
        BlockPos position = helper.absolutePos(new BlockPos(1, 2, 1));
        BagInventory bag = bag(UpgradeKind.MAGNET);
        bag.setFilter(upgrade(bag), 0, new ItemStack(Items.DIAMOND));
        bag.updateSettings(upgrade(bag), state -> state.putString("filter_mode", "ALLOW"));
        ItemEntity near = item(helper, position, .5, new ItemStack(Items.DIAMOND, 9));
        ItemEntity farther = item(helper, position, 4.5, new ItemStack(Items.DIAMOND, 3));
        ItemEntity delayed = item(helper, position, 1, new ItemStack(Items.DIAMOND, 2));
        delayed.setNeverPickUp();
        ItemEntity owned = item(helper, position, 1.5, new ItemStack(Items.DIAMOND, 4));
        owned.setTarget(UUID.randomUUID());
        UpgradeEngine.tick(bag, helper.getLevel(), position, null);
        helper.assertFalse(near.isAlive(), "Magnet stores nearby eligible items");
        helper.assertTrue(farther.isAlive(), "Basic magnet does not reach a point outside its inflated box");
        helper.assertTrue(delayed.isAlive() && owned.isAlive(), "Magnet respects infinite delay and claim ownership");
        helper.assertValueEqual(count(bag, Items.DIAMOND), 9, "World-to-storage quantity is exact");
        helper.runAfterDelay(1, () -> {
            bag.upgrades().setItem(0, new ItemStack(BackpackRegistry.item(UpgradeKind.ADVANCED_MAGNET)));
            UpgradeEngine.tick(bag, helper.getLevel(), position, null);
            helper.assertFalse(farther.isAlive(), "Advanced magnet reaches the larger five-block volume");
            helper.assertValueEqual(count(bag, Items.DIAMOND), 12, "A second tick cannot duplicate the first collected entity");
            ServerPlayer player = BackpackTestSupport.player(helper);
            BagInventory pickup = bag(UpgradeKind.PICKUP);
            for (int slot = 0; slot < pickup.getContainerSize(); slot++) pickup.setItem(slot, new ItemStack(Items.DIRT, 64));
            pickup.setItem(0, new ItemStack(Items.DIAMOND, 60));
            ItemEntity partial = item(helper, position, 2, new ItemStack(Items.DIAMOND, 10));
            helper.assertFalse(UpgradeEngine.pickup(pickup, partial, player), "Partial pickup lets vanilla handle the remainder");
            helper.assertValueEqual(count(pickup, Items.DIAMOND), 64, "Partial pickup fills only the available room");
            helper.assertValueEqual(partial.getItem().getCount(), 6, "Unaccepted pickup items remain in the world");
            delayed.discard(); owned.discard(); partial.discard();
            helper.succeed();
        });
    }

    private static ItemEntity item(GameTestHelper helper, BlockPos origin, double xOffset, ItemStack stack) {
        ItemEntity item = new ItemEntity(helper.getLevel(), origin.getX() + xOffset, origin.getY() + .5, origin.getZ() + .5, stack);
        item.setNoPickUpDelay();
        item.setNoGravity(true);
        item.setDeltaMovement(Vec3.ZERO);
        helper.getLevel().addFreshEntity(item);
        return item;
    }

    public static void feeding(GameTestHelper helper) {
        ServerPlayer player = BackpackTestSupport.player(helper);
        player.setGameMode(GameType.SURVIVAL);
        player.getFoodData().setFoodLevel(6);
        player.setHealth(player.getMaxHealth());
        ItemStack held = new ItemStack(Items.DIAMOND_PICKAXE);
        held.setDamageValue(7);
        player.getInventory().setItem(player.getInventory().selected, held);
        BagInventory bag = bag(UpgradeKind.FEEDING);
        bag.setItem(0, new ItemStack(Items.MUSHROOM_STEW));
        ConsumptionRuntime.feed(bag, upgrade(bag), helper.getLevel(), player.blockPosition(), player);
        helper.assertValueEqual(player.getFoodData().getFoodLevel(), 12, "Feeding uses real food nutrition");
        helper.assertValueEqual(count(bag, Items.BOWL), 1, "Stew bowl is preserved");
        helper.assertTrue(ItemStack.matches(player.getMainHandItem(), held), "Feeding never swaps or loses the held tool");
        bag.setItem(1, new ItemStack(Items.COOKED_BEEF));
        bag.updateSettings(upgrade(bag), state -> state.putLong("feeding_next", 0));
        player.getFoodData().setFoodLevel(19);
        ConsumptionRuntime.feed(bag, upgrade(bag), helper.getLevel(), player.blockPosition(), player);
        helper.assertValueEqual(count(bag, Items.COOKED_BEEF), 1, "Default HALF mode avoids wasting large food on one missing hunger point");
        player.setHealth(10);
        bag.updateSettings(upgrade(bag), state -> state.putLong("feeding_next", 0));
        ConsumptionRuntime.feed(bag, upgrade(bag), helper.getLevel(), player.blockPosition(), player);
        helper.assertValueEqual(count(bag, Items.COOKED_BEEF), 0, "Default hurt override permits feeding with any real hunger deficit");
        helper.assertValueEqual(player.getFoodData().getFoodLevel(), 20, "Nutrition is capped through the vanilla food pipeline");
        helper.succeed();
    }

    public static void transferConservation(GameTestHelper helper) {
        BagInventory deposit = bag(UpgradeKind.DEPOSIT);
        deposit.setItem(0, new ItemStack(Items.DIAMOND, 20));
        SimpleContainer chest = new SimpleContainer(2);
        chest.setItem(0, new ItemStack(Items.DIAMOND, 60));
        chest.setItem(1, new ItemStack(Items.DIRT, 64));
        helper.assertValueEqual(UpgradeEngine.transfer(deposit, chest, true), 1, "Deposit reports one partially moved source stack");
        helper.assertValueEqual(count(deposit, Items.DIAMOND), 16, "Deposit retains the rejected remainder");
        helper.assertValueEqual(count(chest, Items.DIAMOND), 64, "Deposit fills exactly the four free spaces");
        deposit.upgrades().setItem(0, new ItemStack(BackpackRegistry.item(UpgradeKind.RESTOCK)));
        helper.assertValueEqual(UpgradeEngine.transfer(deposit, chest, false), 2, "Restock moves both eligible chest stacks");
        helper.assertValueEqual(count(deposit, Items.DIAMOND) + count(chest, Items.DIAMOND), 80, "Roundtrip conserves all diamonds");
        helper.assertValueEqual(count(deposit, Items.DIRT) + count(chest, Items.DIRT), 64, "Roundtrip conserves all dirt");
        ServerPlayer player = BackpackTestSupport.player(helper);
        player.setGameMode(GameType.SURVIVAL);
        BagInventory refill = bag(UpgradeKind.ADVANCED_REFILL);
        refill.setFilter(upgrade(refill), 0, new ItemStack(Items.TORCH));
        refill.updateSettings(upgrade(refill), state -> state.putString("refill_target_0", "OFF_HAND"));
        refill.setItem(0, new ItemStack(Items.TORCH, 64));
        // Refill deliberately runs only at its five-tick cadence.
        helper.onEachTick(() -> TransferRuntime.refill(refill, upgrade(refill), helper.getLevel(), player.blockPosition(), player));
        helper.runAfterDelay(6, () -> {
            helper.assertValueEqual(player.getOffhandItem().getCount(), 64, "Advanced refill respects the offhand destination");
            helper.assertValueEqual(count(refill, Items.TORCH), 0, "Refill moves owned items without generating a second stack");
            helper.succeed();
        });
    }

    public static void jukebox(GameTestHelper helper) {
        BagInventory bag = bag(UpgradeKind.ADVANCED_JUKEBOX);
        InstalledUpgrade upgrade = upgrade(bag);
        Container discs = bag.upgradeInventory(upgrade);
        List<ItemStack> available = BuiltInRegistries.ITEM.stream().map(Item::getDefaultInstance).filter(JukeboxRuntime::isDisc).limit(12).toList();
        helper.assertValueEqual(available.size(), 12, "Actual registry supplies twelve distinct supported discs");
        for (int slot = 0; slot < 12; slot++) discs.setItem(slot, available.get(slot).copy());
        BlockPos position = helper.absolutePos(new BlockPos(1, 2, 1));
        JukeboxRuntime.tick(bag, upgrade, helper.getLevel(), position, null);
        JukeboxRuntime.action(bag, upgrade, helper.getLevel(), position, null, "play");
        helper.assertValueEqual(NbtAccess.getIntOr(bag.settings(upgrade), "active_slot", -1), 0, "Playback begins at first occupied record slot");
        long finish = NbtAccess.getLongOr(bag.settings(upgrade), "song_finish", 0);
        JukeboxRuntime.action(bag, upgrade, helper.getLevel(), position, null, "play");
        helper.assertValueEqual(NbtAccess.getLongOr(bag.settings(upgrade), "song_finish", 0), finish, "Repeated play does not restart active audio");
        JukeboxRuntime.action(bag, upgrade, helper.getLevel(), position, null, "next");
        helper.assertValueEqual(NbtAccess.getIntOr(bag.settings(upgrade), "active_slot", -1), 1, "Next selects the next physical disc");
        JukeboxRuntime.action(bag, upgrade, helper.getLevel(), position, null, "previous");
        helper.assertValueEqual(NbtAccess.getIntOr(bag.settings(upgrade), "active_slot", -1), 0, "Previous uses the played history");
        JukeboxRuntime.action(bag, upgrade, helper.getLevel(), position, null, "shuffle");
        JukeboxRuntime.action(bag, upgrade, helper.getLevel(), position, null, "repeat");
        JukeboxRuntime.action(bag, upgrade, helper.getLevel(), position, null, "stop");
        helper.assertTrue(NbtAccess.getBooleanOr(bag.settings(upgrade), "shuffle", false), "Stop preserves shuffle preference");
        helper.assertValueEqual(NbtAccess.getStringOr(bag.settings(upgrade), "repeat", "OFF"), "ALL", "Stop preserves repeat preference");
        helper.assertValueEqual((int) InventoryMoves.snapshot(discs).stream().filter(JukeboxRuntime::isDisc).count(), 12, "All twelve physical discs survive stop");
        JukeboxRuntime.action(bag, upgrade, helper.getLevel(), position, null, "play");
        discs.setItem(NbtAccess.getIntOr(bag.settings(upgrade), "active_slot", 0), ItemStack.EMPTY);
        JukeboxRuntime.tick(bag, upgrade, helper.getLevel(), position, null);
        helper.assertFalse(NbtAccess.getBooleanOr(bag.settings(upgrade), "playing", true), "Changing an active slot stops playback");

        BagInventory timed = bag(UpgradeKind.ADVANCED_JUKEBOX);
        InstalledUpgrade timedUpgrade = upgrade(timed);
        Holder<JukeboxSong> shortSong = helper.getLevel().registryAccess().lookupOrThrow(Registries.JUKEBOX_SONG)
                .getOrThrow(ResourceKey.create(Registries.JUKEBOX_SONG, ResourceLocation.fromNamespaceAndPath("fabricated_backpacks_tests", "short_record")));
        ItemStack shortDisc = available.getFirst().copy();
        shortDisc.set(DataComponents.JUKEBOX_PLAYABLE, new JukeboxPlayable(new net.minecraft.world.item.EitherHolder<>(shortSong), true));
        timed.upgradeInventory(timedUpgrade).setItem(0, shortDisc);
        timed.upgradeInventory(timedUpgrade).setItem(1, shortDisc.copy());
        JukeboxRuntime.action(timed, timedUpgrade, helper.getLevel(), position, null, "play");
        helper.onEachTick(() -> JukeboxRuntime.tick(timed, timedUpgrade, helper.getLevel(), position, null));
        helper.runAfterDelay(22, () -> helper.assertValueEqual(NbtAccess.getIntOr(timed.settings(timedUpgrade), "active_slot", -1), 1, "Natural completion advances to the next song"));
        helper.runAfterDelay(44, () -> {
            helper.assertFalse(NbtAccess.getBooleanOr(timed.settings(timedUpgrade), "playing", true), "Repeat OFF stops naturally at end of playlist");
            JukeboxRuntime.stopUpgrade(bag, upgrade.slot(), helper.getLevel().getServer());
            JukeboxRuntime.stopUpgrade(timed, timedUpgrade.slot(), helper.getLevel().getServer());
            helper.succeed();
        });
    }

    public static void toolsAndAttackHooks(GameTestHelper helper) {
        ServerPlayer player = BackpackTestSupport.player(helper);
        BagInventory basic = bag(UpgradeKind.TOOL_SWAPPER);
        ItemStack pick = new ItemStack(Items.DIAMOND_PICKAXE);
        pick.setDamageValue(17);
        basic.setItem(0, pick.copy());
        basic.setItem(1, new ItemStack(Items.IRON_PICKAXE));
        basic.setItem(2, new ItemStack(Items.DIAMOND_SWORD));
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
        BackpackEquipment.set(player, basic.stack());
        BlockPos stone = helper.absolutePos(new BlockPos(3, 1, 3));
        helper.getLevel().setBlockAndUpdate(stone, Blocks.STONE.defaultBlockState());
        net.fabricmc.fabric.api.event.player.AttackBlockCallback.EVENT.invoker().interact(player, helper.getLevel(), InteractionHand.MAIN_HAND, stone, Direction.UP);
        BackpackTestSupport.assertStack(helper, player.getMainHandItem(), pick, "Real attack-block hook selects the fastest valid owned tool with its damage intact");
        BagInventory live = BagInventory.of(BackpackEquipment.get(player));
        helper.assertValueEqual(count(live, Items.STICK), 1, "Tool replacement stores the original held item");
        helper.assertValueEqual(count(live, Items.DIAMOND_PICKAXE), 0, "Tool movement never leaves a duplicate in the backpack");
        var target = helper.spawn(EntityType.PIG, new BlockPos(5, 1, 4));
        net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.invoker().interact(player, helper.getLevel(), InteractionHand.MAIN_HAND, target, null);
        helper.assertTrue(player.getMainHandItem().is(Items.DIAMOND_SWORD), "Real attack-entity hook selects the owned sword");
        helper.assertValueEqual(InventoryMoves.count(BagInventory.of(BackpackEquipment.get(player)), pick), 1, "Weapon swap returns the previous tool with its exact damage once");
        target.discard();
        BackpackEquipment.set(player, ItemStack.EMPTY);

        BagInventory advanced = bag(UpgradeKind.ADVANCED_TOOL_SWAPPER);
        advanced.setItem(0, new ItemStack(Items.IRON_PICKAXE));
        advanced.setItem(1, new ItemStack(Items.DIAMOND_PICKAXE));
        advanced.setFilter(upgrade(advanced), 0, new ItemStack(Items.IRON_PICKAXE));
        advanced.updateSettings(upgrade(advanced), state -> { state.putString("filter_mode", "ALLOW"); state.putString("tool_mode", "MANUAL"); });
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
        helper.assertFalse(ToolRuntime.forBlock(advanced, player, Blocks.STONE.defaultBlockState(), false), "Manual advanced mode suppresses automatic selection");
        helper.assertTrue(ToolRuntime.forBlock(advanced, player, Blocks.STONE.defaultBlockState(), true), "Manual action selects a filtered owned tool");
        helper.assertTrue(player.getMainHandItem().is(Items.IRON_PICKAXE), "Advanced filter excludes a faster unrelated tool");

        BagInventory full = BackpackTestSupport.bag(BackpackTier.NETHERITE, UpgradeKind.ADVANCED_TOOL_SWAPPER, UpgradeKind.STACK_UPGRADE_TIER_1);
        for (int slot = 0; slot < full.getContainerSize(); slot++) full.setItem(slot, new ItemStack(Items.DIRT, 128));
        full.setItem(0, new ItemStack(Items.DIAMOND_PICKAXE, 2));
        for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot, new ItemStack(Items.DIRT, 64));
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_AXE));
        ItemStack before = full.stack().copy();
        var inventoryBefore = InventoryMoves.snapshot(player.getInventory());
        helper.assertFalse(ToolRuntime.swapToHand(full, player, 0, 1), "Swap aborts when neither inventory can retain the previous held item");
        BackpackTestSupport.assertStack(helper, full.stack(), before, "Failed tool swap preserves the full backpack snapshot");
        for (int slot = 0; slot < inventoryBefore.size(); slot++) BackpackTestSupport.assertStack(helper, player.getInventory().getItem(slot), inventoryBefore.get(slot), "Failed tool swap preserves player slot " + slot);
        player.getInventory().setItem(9, ItemStack.EMPTY);
        helper.assertTrue(ToolRuntime.swapToHand(full, player, 0, 1), "Swap can use an available ordinary player slot for its remainder");
        helper.assertValueEqual(count(full, Items.DIAMOND_PICKAXE) + count(player.getInventory(), Items.DIAMOND_PICKAXE), 2, "Successful partial tool swap conserves both enhanced-stack tools");
        helper.assertValueEqual(count(full, Items.IRON_AXE) + count(player.getInventory(), Items.IRON_AXE), 1, "Successful tool swap retains the old held item once");
        helper.succeed();
    }

    private static void alchemyProbe(GameTestHelper helper, LivingEntity target, ItemStack ghost, ItemStack source,
                                     String condition, int health, Consumer<CompoundTag> preferences, boolean expected, String message) {
        BagInventory bag = bag(UpgradeKind.ADVANCED_ALCHEMY);
        InstalledUpgrade upgrade = upgrade(bag);
        bag.setFilter(upgrade, 0, ghost);
        bag.setItem(0, source.copy());
        bag.updateSettings(upgrade, state -> {
            state.putString("alchemy_condition_0", condition); state.putInt("alchemy_health_0", health); preferences.accept(state);
        });
        UpgradeEngine.tick(bag, helper.getLevel(), target.blockPosition(), target);
        helper.assertValueEqual(NbtAccess.getIntOr(bag.settings(upgrade), "alchemy_active_row", -1) == 0, expected, message);
        BackpackTestSupport.assertStack(helper, bag.getItem(0), source, "A pending or rejected condition never removes its item: " + message);
        AlchemyRuntime.cancel(bag, 0, helper.getLevel().getServer());
        helper.assertValueEqual(NbtAccess.getIntOr(bag.settings(upgrade), "alchemy_active_row", -1), -1, "Cancellation clears the visible active row");
        helper.assertValueEqual(NbtAccess.getLongOr(bag.settings(upgrade), "alchemy_finish", 0), 0L, "Cancellation clears the runtime deadline");
    }

    public static void toolModesAndDataRules(GameTestHelper helper) {
        ServerPlayer player = BackpackTestSupport.player(helper);
        BagInventory advanced = bag(UpgradeKind.ADVANCED_TOOL_SWAPPER);
        InstalledUpgrade control = upgrade(advanced);
        advanced.setItem(0, new ItemStack(Items.DIAMOND_PICKAXE));
        advanced.setItem(1, new ItemStack(Items.DIAMOND_SWORD));
        helper.assertTrue(UpgradeEngine.action(advanced, 0, "tool_mode", player), "Advanced mode is a real server-authorized action");
        helper.assertValueEqual(NbtAccess.getStringOr(advanced.settings(control), "tool_mode", ""), "ONLY_TOOLS", "Mode cycle exposes the third supported policy");
        for (ItemStack held : List.of(new ItemStack(Items.IRON_SWORD), new ItemStack(Items.APPLE), ItemStack.EMPTY)) {
            player.setItemInHand(InteractionHand.MAIN_HAND, held.copy());
            helper.assertFalse(ToolRuntime.forBlock(advanced, player, Blocks.STONE.defaultBlockState(), false), "Only-tools mode does not replace a weapon, food, or empty hand");
            BackpackTestSupport.assertStack(helper, player.getMainHandItem(), held, "Rejected automatic swap preserves the held stack");
        }
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
        helper.assertTrue(ToolRuntime.forBlock(advanced, player, Blocks.STONE.defaultBlockState(), false), "Only-tools mode can upgrade an actual held mining tool");
        helper.assertTrue(player.getMainHandItem().is(Items.DIAMOND_PICKAXE), "A real faster tool is selected");
        helper.assertValueEqual(count(advanced, Items.IRON_PICKAXE), 1, "Automatic mode keeps the previous tool once");
        UpgradeEngine.action(advanced, 0, "tool_mode", player);
        helper.assertValueEqual(NbtAccess.getStringOr(advanced.settings(control), "tool_mode", ""), "MANUAL", "Mode cycle reaches manual");
        var pig = helper.spawn(EntityType.PIG, new BlockPos(5, 1, 4));
        helper.assertFalse(ToolRuntime.forEntity(advanced, player, pig, false), "Manual mode suppresses automatic entity attacks as well as block attacks");
        helper.assertFalse(ToolRuntime.forBlock(advanced, player, Blocks.STONE.defaultBlockState(), false), "Manual mode suppresses block attacks");
        helper.assertTrue(ToolRuntime.forEntity(advanced, player, pig, true), "An explicit manual entity action may select a weapon");
        helper.assertTrue(player.getMainHandItem().is(Items.DIAMOND_SWORD), "Manual entity selection returns the actual owned sword");
        helper.assertValueEqual(count(advanced, Items.DIAMOND_PICKAXE), 1, "Manual selection returns the old tool exactly once");
        UpgradeEngine.action(advanced, 0, "swap_weapons", player);
        helper.assertFalse(ToolRuntime.forEntity(advanced, player, pig, true), "Weapon switch also applies to deliberate manual weapon selection");
        helper.assertTrue(ToolRuntime.forBlock(advanced, player, Blocks.STONE.defaultBlockState(), true), "Manual block action is independent of the weapon switch");
        helper.assertValueEqual(count(advanced, Items.DIAMOND_SWORD), 1, "Manual block action stashes the held weapon safely");
        pig.discard();

        BagInventory basic = bag(UpgradeKind.TOOL_SWAPPER);
        basic.setItem(0, new ItemStack(Items.IRON_PICKAXE));
        basic.updateSettings(upgrade(basic), tag -> tag.putString("tool_mode", "MANUAL"));
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
        helper.assertFalse(UpgradeEngine.action(basic, 0, "tool_mode", player), "Basic upgrades reject advanced mode packets");
        helper.assertFalse(ToolRuntime.forBlock(basic, player, Blocks.STONE.defaultBlockState(), true), "Basic upgrades do not expose the advanced manual action");
        helper.assertTrue(ToolRuntime.forBlock(basic, player, Blocks.STONE.defaultBlockState(), false), "Forged advanced mode data cannot disable the basic tool contract");

        var server = helper.getLevel().getServer();
        helper.assertFalse(com.kadamitas.fabricatedbackpacks.config.RuleMatchers.block(Blocks.AIR.defaultBlockState(), java.util.Set.of("missing_optional_mod:work_block")), "An absent optional block ID never aliases the registry's default air block");
        var rules = com.kadamitas.fabricatedbackpacks.upgrade.ToolRules.rules(server);
        helper.assertTrue(rules.containsKey(net.minecraft.resources.ResourceLocation.parse("fabricated_backpacks_tests:bookshelf_utility")), "Actual loaded datapack provides the block rule");
        helper.assertTrue(rules.containsKey(net.minecraft.resources.ResourceLocation.parse("fabricated_backpacks:shearing")), "Production utility rules load from the mod's actual datapack");
        helper.assertTrue(com.kadamitas.fabricatedbackpacks.upgrade.ToolRules.reload(server), "A complete valid resource catalog can be republished");
        helper.assertValueEqual(com.kadamitas.fabricatedbackpacks.upgrade.ToolRules.rules(server), rules, "Reload preserves the actual decoded rules and tag selectors");

        BagInventory mapped = bag(UpgradeKind.ADVANCED_TOOL_SWAPPER);
        mapped.setItem(0, new ItemStack(Items.DIAMOND_AXE));
        mapped.setItem(1, new ItemStack(Items.WOODEN_SHOVEL));
        mapped.setItem(2, new ItemStack(Items.DIAMOND_SWORD));
        mapped.setItem(3, new ItemStack(Items.BRUSH));
        mapped.setItem(4, new ItemStack(Items.IRON_PICKAXE));
        mapped.setItem(5, new ItemStack(Items.SHEARS));
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
        helper.assertTrue(ToolRuntime.forBlock(mapped, player, Blocks.BOOKSHELF.defaultBlockState(), false), "Automatic selection ignores a manual-only utility rule");
        helper.assertTrue(player.getMainHandItem().is(Items.DIAMOND_AXE), "Native tool behavior remains active without a matching automatic override");
        helper.assertTrue(ToolRuntime.forBlock(mapped, player, Blocks.BOOKSHELF.defaultBlockState(), true), "Manual utility mapping matches actual block and item registry tags");
        helper.assertTrue(player.getMainHandItem().is(Items.WOODEN_SHOVEL), "Explicit utility priority can select a mapped non-mining tool");
        helper.assertTrue(ToolRuntime.forBlock(mapped, player, Blocks.STONE.defaultBlockState(), true), "A correct mining tool remains selectable");
        helper.assertTrue(player.getMainHandItem().is(Items.IRON_PICKAXE), "Even a high-priority rule cannot bypass correct drops without explicit server permission");
        var cow = helper.spawn(EntityType.COW, new BlockPos(5, 1, 4));
        helper.assertTrue(ToolRuntime.forEntity(mapped, player, cow, false), "Entity tag mapping participates in actual tool-to-weapon selection");
        helper.assertTrue(player.getMainHandItem().is(Items.DIAMOND_AXE), "Server entity mapping outranks the ordinary sword preference");
        cow.discard();
        var sheep = helper.spawn(EntityType.SHEEP, new BlockPos(5, 1, 4));
        helper.assertTrue(ToolRuntime.forEntity(mapped, player, sheep, true), "The production shearing rule supplies a manual entity utility choice");
        helper.assertTrue(player.getMainHandItem().is(Items.SHEARS), "Manual shearing selects real shears instead of a sword");
        helper.assertValueEqual(count(mapped, Items.SHEARS) + count(player.getInventory(), Items.SHEARS), 1, "Mapped utility swaps never duplicate items");
        sheep.discard();
        helper.succeed();
    }

    private static void conditionProbe(GameTestHelper helper, LivingEntity target, String condition, int health, boolean expected, String message) {
        ItemStack potion = PotionContents.createItemStack(Items.POTION, Potions.NIGHT_VISION);
        alchemyProbe(helper, target, potion, potion, condition, health, state -> { }, expected, message);
    }

    public static void alchemyConditions(GameTestHelper helper) {
        ServerPlayer player = BackpackTestSupport.player(helper);
        int[] stage = {0};
        helper.onEachTick(() -> {
            // Embedded fixtures are not in the server's socket poller. Tick the vanilla
            // play listener here so player eye-fluid detection follows real movement.
            player.connection.tick();
            if (helper.getLevel().getGameTime() % 5 != 0) return;
            if (stage[0] == 0) {
                player.clearFire(); player.removeAllEffects(); player.setSprinting(false); player.fallDistance = 0;
                player.setHealth(player.getMaxHealth());
                helper.assertFalse(player.isUnderWater(), "The initial condition fixture is dry");
                for (AlchemyRuntime.Condition condition : AlchemyRuntime.Condition.values()) {
                    conditionProbe(helper, player, condition.name(), 75, condition == AlchemyRuntime.Condition.ALWAYS,
                            "Healthy idle player condition " + condition);
                }
                player.igniteForTicks(80);
                conditionProbe(helper, player, "ON_FIRE", 75, true, "A real burning entity satisfies ON_FIRE");
                conditionProbe(helper, player, "NEVER", 75, false, "NEVER remains disabled even while a contextual need is present");
                player.clearFire();
                player.fallDistance = 2;
                conditionProbe(helper, player, "FALLING", 75, false, "FALLING excludes exactly two blocks");
                player.fallDistance = 2.1F;
                conditionProbe(helper, player, "FALLING", 75, true, "FALLING requires more than two blocks");
                player.fallDistance = 0;
                player.setSprinting(true);
                conditionProbe(helper, player, "SPRINTING", 75, true, "Sprinting state activates its row");
                player.setSprinting(false);
                player.setHealth(player.getMaxHealth() * .75F);
                conditionProbe(helper, player, "HURT", 75, false, "HURT is strict at the 75 percent boundary");
                player.setHealth(player.getMaxHealth() * .70F);
                conditionProbe(helper, player, "HURT", 75, true, "HURT activates below the selected fraction");
                conditionProbe(helper, player, "HURT", 0, false, "Zero percent does not activate on a living player");
                conditionProbe(helper, player, "HURT", 100, true, "One hundred percent permits any actual missing health");
                player.setHealth(player.getMaxHealth());
                conditionProbe(helper, player, "HURT", 100, false, "Full health still never triggers HURT");
                player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 200));
                conditionProbe(helper, player, "NEGATIVE_EFFECT", 75, false, "A beneficial effect is not a harmful-status condition");
                player.addEffect(new MobEffectInstance(MobEffects.POISON, 200));
                conditionProbe(helper, player, "NEGATIVE_EFFECT", 75, true, "Actual harmful status activates NEGATIVE_EFFECT");
                player.removeAllEffects();

                BlockPos mining = helper.absolutePos(new BlockPos(5, 1, 6));
                helper.getLevel().setBlockAndUpdate(mining, Blocks.OBSIDIAN.defaultBlockState());
                player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                player.gameMode.handleBlockBreakAction(mining, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, Direction.UP, helper.getLevel().getMaxBuildHeight(), 1);
                helper.assertTrue(((UpgradeAccess.Mining) player.gameMode).fabricatedBackpacks$isDestroyingBlock(), "Vanilla block-break handling started an actual mining attempt");
                conditionProbe(helper, player, "MINING", 75, true, "MINING observes the vanilla server destruction state");
                player.gameMode.handleBlockBreakAction(mining, ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, Direction.UP, helper.getLevel().getMaxBuildHeight(), 2);
                conditionProbe(helper, player, "MINING", 75, false, "Aborting the block break stops the mining condition");
                var pig = helper.spawn(EntityType.PIG, new BlockPos(4, 1, 4));
                conditionProbe(helper, pig, "MINING", 75, false, "Nonplayer carriers cannot satisfy server-player mining");
                pig.discard();

                BlockPos water = helper.absolutePos(new BlockPos(2, 1, 2));
                for (int y = 0; y < 3; y++) helper.getLevel().setBlockAndUpdate(water.above(y), Blocks.WATER.defaultBlockState());
                player.setPos(Vec3.atBottomCenterOf(water));
                stage[0] = 1;
            } else if (stage[0] == 1) {
                helper.assertTrue(player.isUnderWater(), "Vanilla entity ticking detects submerged eyes");
                conditionProbe(helper, player, "UNDER_WATER", 75, true, "Underwater state activates the underwater row");
                player.setPos(Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(6, 1, 6))));
                stage[0] = 2;
            } else {
                helper.assertFalse(player.isUnderWater(), "Leaving the water updates the actual eye-fluid state");
                conditionProbe(helper, player, "UNDER_WATER", 75, false, "Underwater condition stops after leaving the water");
                helper.succeed();
            }
        });
    }

    private static ItemStack effectPotion(MobEffectInstance... effects) {
        ItemStack stack = new ItemStack(Items.POTION);
        PotionContents contents = PotionContents.EMPTY;
        for (MobEffectInstance effect : effects) contents = contents.withEffectAdded(effect);
        stack.set(DataComponents.POTION_CONTENTS, contents);
        return stack;
    }

    public static void alchemyEffectMatching(GameTestHelper helper) {
        ServerPlayer player = BackpackTestSupport.player(helper);
        helper.onEachTick(() -> {
            if (helper.getLevel().getGameTime() % 5 != 0) return;
            player.removeAllEffects();
            ItemStack ghost = effectPotion(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 600), new MobEffectInstance(MobEffects.REGENERATION, 600));
            ItemStack duration = effectPotion(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 1200), new MobEffectInstance(MobEffects.REGENERATION, 600));
            ItemStack amplifier = effectPotion(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 600, 1), new MobEffectInstance(MobEffects.REGENERATION, 600));
            ItemStack onlyFirst = effectPotion(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 600));
            ItemStack unrelated = effectPotion(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 600));
            ItemStack extra = effectPotion(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 600), new MobEffectInstance(MobEffects.REGENERATION, 600), new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 600));
            ItemStack named = ghost.copy(); named.set(DataComponents.CUSTOM_NAME, Component.literal("Named formula"));
            List<ItemStack> candidates = List.of(ghost, duration, amplifier, onlyFirst, unrelated, extra, named);
            for (boolean matchDuration : List.of(false, true)) for (boolean matchAmplifier : List.of(false, true)) for (boolean all : List.of(false, true)) {
                for (int index = 0; index < candidates.size(); index++) {
                    boolean strict = matchDuration && matchAmplifier && all;
                    boolean expected = strict ? index == 0 : switch (index) {
                        case 1 -> !all || !matchDuration;
                        case 2 -> !all || !matchAmplifier;
                        case 3 -> !all;
                        case 4 -> false;
                        default -> true;
                    };
                    alchemyProbe(helper, player, ghost, candidates.get(index), "ALWAYS", 75, state -> {
                        state.putBoolean("alchemy_match_duration", matchDuration); state.putBoolean("alchemy_match_amplifier", matchAmplifier);
                        state.putBoolean("alchemy_match_all", all);
                    }, expected, "Alchemy effect matrix duration=" + matchDuration + "/amplifier=" + matchAmplifier + "/all=" + all + "/variant=" + index);
                }
            }
            player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 100, 1));
            alchemyProbe(helper, player, ghost, ghost, "ALWAYS", 75, state -> { }, false, "Equal or stronger present effects block default all-missing use");
            alchemyProbe(helper, player, ghost, ghost, "ALWAYS", 75, state -> state.putBoolean("alchemy_all_missing", false), true, "Any-missing mode permits a missing regeneration effect");
            player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 100));
            alchemyProbe(helper, player, ghost, ghost, "ALWAYS", 75, state -> state.putBoolean("alchemy_all_missing", false), false, "Any-missing mode still rejects a fully covered formula");
            player.removeAllEffects();
            player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 100));
            alchemyProbe(helper, player, amplifier, amplifier, "ALWAYS", 75, state -> { }, true, "A weaker active amplifier can be upgraded");
            player.removeAllEffects();

            BagInventory rows = bag(UpgradeKind.ALCHEMY);
            InstalledUpgrade upgrade = upgrade(rows);
            ItemStack fire = PotionContents.createItemStack(Items.POTION, Potions.FIRE_RESISTANCE);
            rows.setFilter(upgrade, 0, fire);
            helper.assertValueEqual(NbtAccess.getStringOr(rows.settings(upgrade), "alchemy_condition_0", AlchemyRuntime.defaultCondition(rows.ghost(upgrade, 0)).name()), "ON_FIRE", "First ghost selects its effect-specific default");
            rows.updateSettings(upgrade, state -> state.putString("alchemy_condition_0", "HURT"));
            rows.setFilter(upgrade, 0, PotionContents.createItemStack(Items.POTION, Potions.WATER_BREATHING));
            helper.assertValueEqual(NbtAccess.getStringOr(rows.settings(upgrade), "alchemy_condition_0", ""), "HURT", "Replacing a nonempty ghost preserves the chosen condition");
            rows.setFilter(upgrade, 0, ItemStack.EMPTY);
            rows.setFilter(upgrade, 0, fire);
            helper.assertValueEqual(NbtAccess.getStringOr(rows.settings(upgrade), "alchemy_condition_0", ""), "ON_FIRE", "Removing and readding a ghost does not reuse a stale explicit condition");
            helper.assertFalse(UpgradeEngine.action(rows, 0, "alchemy_health:0:NaN", player), "Noninteger health adjustments are rejected");
            helper.assertFalse(UpgradeEngine.action(rows, 0, "alchemy_health:0:2147483647", player), "Only a bounded five-point health step is accepted");
            for (int n = 0; n < 30; n++) UpgradeEngine.action(rows, 0, "alchemy_health:0:5", player);
            helper.assertValueEqual(NbtAccess.getIntOr(rows.settings(upgrade), "alchemy_health_0", -1), 100, "Repeated health steps clamp at one hundred");
            for (int n = 0; n < 30; n++) UpgradeEngine.action(rows, 0, "alchemy_health:0:-5", player);
            helper.assertValueEqual(NbtAccess.getIntOr(rows.settings(upgrade), "alchemy_health_0", -1), 0, "Repeated health steps clamp at zero");
            rows.updateSettings(upgrade, state -> { state.putInt("alchemy_active_row", 3); state.putLong("alchemy_finish", Long.MAX_VALUE); state.putString("alchemy_condition_0", "NEVER"); });
            BagInventory restored = BagInventory.of(BackpackTestSupport.roundTrip(helper.getLevel(), rows.stack()));
            UpgradeEngine.tick(restored, helper.getLevel(), player.blockPosition(), player);
            helper.assertValueEqual(NbtAccess.getIntOr(restored.settings(upgrade(restored)), "alchemy_active_row", -1), -1, "Restored preferences cannot resurrect a runtime consumption timer");
            helper.succeed();
        });
    }

    public static void alchemyConsumableFamilies(GameTestHelper helper) {
        record Dose(BagInventory bag, net.minecraft.world.entity.LivingEntity player, ItemStack item, Item remainder) { }
        List<Dose> doses = new ArrayList<>();
        List<ItemStack> items = List.of(PotionContents.createItemStack(Items.POTION, Potions.FIRE_RESISTANCE),
                new ItemStack(Items.MILK_BUCKET), new ItemStack(Items.HONEY_BOTTLE), new ItemStack(Items.OMINOUS_BOTTLE), new ItemStack(Items.GOLDEN_APPLE));
        for (ItemStack item : items) {
            ServerPlayer player = BackpackTestSupport.player(helper);
            player.getFoodData().setFoodLevel(10);
            BagInventory bag = BackpackTestSupport.bag(BackpackTier.NETHERITE, UpgradeKind.ALCHEMY, UpgradeKind.STACK_UPGRADE_TIER_1);
            InstalledUpgrade upgrade = upgrade(bag);
            bag.setItem(0, item.copyWithCount(2));
            bag.setFilter(upgrade, 0, item);
            boolean remover = item.is(Items.MILK_BUCKET) || item.is(Items.HONEY_BOTTLE);
            if (remover) player.addEffect(new MobEffectInstance(MobEffects.POISON, 400));
            if (item.is(Items.MILK_BUCKET)) player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 400));
            bag.updateSettings(upgrade, state -> state.putString("alchemy_condition_0", remover ? "NEGATIVE_EFFECT" : "ALWAYS"));
            Item remainder = item.is(Items.POTION) || item.is(Items.HONEY_BOTTLE) ? Items.GLASS_BOTTLE : item.is(Items.MILK_BUCKET) ? Items.BUCKET : Items.AIR;
            helper.assertTrue(AlchemyRuntime.supported(item), "Actual effect-bearing consumable is supported: " + item);
            doses.add(new Dose(bag, player, item, remainder));
        }
        var milkTarget = helper.spawn(EntityType.PIG, new BlockPos(2, 1, 2));
        milkTarget.setNoAi(true);
        milkTarget.addEffect(new MobEffectInstance(MobEffects.POISON, 400));
        BagInventory mobMilk = BackpackTestSupport.bag(BackpackTier.NETHERITE, UpgradeKind.ALCHEMY, UpgradeKind.STACK_UPGRADE_TIER_1);
        ItemStack milk = new ItemStack(Items.MILK_BUCKET);
        mobMilk.setItem(0, milk.copyWithCount(2));
        mobMilk.setFilter(upgrade(mobMilk), 0, milk);
        mobMilk.updateSettings(upgrade(mobMilk), state -> state.putString("alchemy_condition_0", "NEGATIVE_EFFECT"));
        doses.add(new Dose(mobMilk, milkTarget, milk, Items.BUCKET));
        helper.assertValueEqual(AlchemyRuntime.defaultCondition(PotionContents.createItemStack(Items.POTION, Potions.WATER_BREATHING)), AlchemyRuntime.Condition.UNDER_WATER, "Water breathing defaults to underwater");
        helper.assertValueEqual(AlchemyRuntime.defaultCondition(PotionContents.createItemStack(Items.POTION, Potions.HEALING)), AlchemyRuntime.Condition.HURT, "Healing defaults to hurt");
        helper.assertValueEqual(AlchemyRuntime.defaultCondition(PotionContents.createItemStack(Items.POTION, Potions.REGENERATION)), AlchemyRuntime.Condition.HURT, "Regeneration defaults to hurt");
        helper.assertValueEqual(AlchemyRuntime.defaultCondition(PotionContents.createItemStack(Items.POTION, Potions.SWIFTNESS)), AlchemyRuntime.Condition.SPRINTING, "Swiftness defaults to sprinting");
        helper.assertValueEqual(AlchemyRuntime.defaultCondition(PotionContents.createItemStack(Items.POTION, Potions.SLOW_FALLING)), AlchemyRuntime.Condition.FALLING, "Slow falling defaults to falling");
        helper.assertValueEqual(AlchemyRuntime.defaultCondition(effectPotion(new MobEffectInstance(MobEffects.DIG_SPEED, 600))), AlchemyRuntime.Condition.MINING, "An actual haste effect defaults to mining");
        helper.assertValueEqual(AlchemyRuntime.defaultCondition(new ItemStack(Items.MILK_BUCKET)), AlchemyRuntime.Condition.NEGATIVE_EFFECT, "Status removal defaults to a negative-effect condition");
        helper.assertValueEqual(AlchemyRuntime.defaultCondition(PotionContents.createItemStack(Items.POTION, Potions.WATER)), AlchemyRuntime.Condition.NEVER, "Effectless water defaults to never");
        long start = helper.getLevel().getGameTime();
        helper.onEachTick(() -> {
            for (Dose dose : doses) UpgradeEngine.tick(dose.bag(), helper.getLevel(), dose.player().blockPosition(), dose.player());
            if (helper.getLevel().getGameTime() - start < 65 || helper.getLevel().getGameTime() % 5 != 0) return;
            for (Dose dose : doses) {
                helper.assertValueEqual(InventoryMoves.count(dose.bag(), dose.item()), 1, "Existing effects prevent a second unnecessary dose of " + dose.item());
                if (dose.remainder() != Items.AIR) helper.assertValueEqual(count(dose.bag(), dose.remainder()), 1, "The actual finish-use remainder is retained once for " + dose.item());
                else helper.assertValueEqual(count(dose.bag(), Items.GLASS_BOTTLE) + count(dose.bag(), Items.BUCKET), 0, "Items without a vanilla remainder do not invent one");
                if (dose.item().is(Items.POTION)) helper.assertTrue(dose.player().hasEffect(MobEffects.FIRE_RESISTANCE), "Drinkable potion applied its real effect");
                if (dose.item().is(Items.MILK_BUCKET)) helper.assertTrue(dose.player().getActiveEffects().isEmpty(), "Milk clears beneficial and harmful effects through its vanilla component");
                if (dose.item().is(Items.HONEY_BOTTLE)) helper.assertFalse(dose.player().hasEffect(MobEffects.POISON), "Honey's actual removal effect cured poison");
                if (dose.item().is(Items.OMINOUS_BOTTLE)) helper.assertTrue(dose.player().hasEffect(MobEffects.BAD_OMEN), "Ominous bottle applied its real amplifier component");
                if (dose.item().is(Items.GOLDEN_APPLE)) helper.assertTrue(dose.player().hasEffect(MobEffects.ABSORPTION), "Golden apple consumed as an effect-bearing food");
            }
            ServerPlayer splashTarget = BackpackTestSupport.player(helper);
            ItemStack splash = PotionContents.createItemStack(Items.SPLASH_POTION, Potions.FIRE_RESISTANCE);
            BagInventory splashBag = bag(UpgradeKind.ALCHEMY);
            splashBag.setFilter(upgrade(splashBag), 0, splash);
            splashBag.setItem(0, splash.copy());
            splashBag.updateSettings(upgrade(splashBag), state -> state.putString("alchemy_condition_0", "ALWAYS"));
            UpgradeEngine.tick(splashBag, helper.getLevel(), splashTarget.blockPosition(), splashTarget);
            helper.assertTrue(splashTarget.hasEffect(MobEffects.FIRE_RESISTANCE), "Splash applies through the actual vanilla hit immediately");
            helper.assertTrue(splashBag.getItem(0).isEmpty(), "A splash spends exactly its physical potion");
            helper.assertValueEqual(count(splashBag, Items.GLASS_BOTTLE), 0, "A broken splash bottle is not returned as a drinking remainder");

            var villager = helper.spawn(EntityType.ZOMBIE_VILLAGER, new BlockPos(2, 1, 2));
            villager.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 400));
            BagInventory appleBag = bag(UpgradeKind.ALCHEMY);
            appleBag.setItem(0, new ItemStack(Items.GOLDEN_APPLE, 2));
            appleBag.setFilter(upgrade(appleBag), 0, new ItemStack(Items.GOLDEN_APPLE));
            appleBag.updateSettings(upgrade(appleBag), state -> state.putString("alchemy_condition_0", "ALWAYS"));
            UpgradeEngine.tick(appleBag, helper.getLevel(), villager.blockPosition(), villager);
            helper.assertTrue(villager.isConverting(), "A weakened zombie villager starts its actual conversion");
            helper.assertValueEqual(count(appleBag, Items.GOLDEN_APPLE), 1, "Conversion consumes one golden apple");
            alchemyProbe(helper, villager, new ItemStack(Items.GOLDEN_APPLE), new ItemStack(Items.GOLDEN_APPLE), "ALWAYS", 75, state -> { }, false, "An already converting villager does not consume a redundant golden apple");
            villager.discard();
            helper.succeed();
        });
    }

    public static void alchemy(GameTestHelper helper) {
        ServerPlayer player = BackpackTestSupport.player(helper);
        ServerPlayer interrupted = BackpackTestSupport.player(helper);
        player.setGameMode(GameType.SURVIVAL);
        interrupted.setGameMode(GameType.SURVIVAL);
        ItemStack potion = PotionContents.createItemStack(Items.POTION, Potions.FIRE_RESISTANCE);
        BagInventory bag = bag(UpgradeKind.ALCHEMY);
        bag.setFilter(upgrade(bag), 0, potion);
        bag.setItem(0, potion.copy());
        bag.updateSettings(upgrade(bag), state -> state.putString("alchemy_condition_0", "ALWAYS"));
        BagInventory interruptedBag = bag(UpgradeKind.ALCHEMY);
        interruptedBag.setFilter(upgrade(interruptedBag), 0, potion);
        interruptedBag.setItem(0, potion.copy());
        interruptedBag.updateSettings(upgrade(interruptedBag), state -> state.putString("alchemy_condition_0", "ALWAYS"));
        helper.assertFalse(AlchemyRuntime.supported(PotionContents.createItemStack(Items.POTION, Potions.WATER)), "Effectless water is not an alchemy consumable");
        helper.assertFalse(AlchemyRuntime.supported(PotionContents.createItemStack(Items.LINGERING_POTION, Potions.FIRE_RESISTANCE)), "Lingering potions are not silently treated as drinkable effects");
        helper.assertValueEqual(AlchemyRuntime.defaultCondition(potion), AlchemyRuntime.Condition.ON_FIRE, "Effect filter supplies its contextual default");
        helper.onEachTick(() -> {
            UpgradeEngine.tick(bag, helper.getLevel(), player.blockPosition(), player);
            UpgradeEngine.tick(interruptedBag, helper.getLevel(), interrupted.blockPosition(), interrupted);
        });
        helper.runAfterDelay(10, () -> {
            helper.assertTrue(ItemStack.isSameItemSameComponents(interruptedBag.getItem(0), potion), "Pending consumption leaves its item durably stored");
            UpgradeEngine.action(interruptedBag, 0, "toggle", interrupted);
        });
        helper.runAfterDelay(45, () -> {
            helper.assertTrue(player.hasEffect(MobEffects.FIRE_RESISTANCE), "Alchemy uses actual vanilla potion effects after the consume duration");
            helper.assertValueEqual(count(bag, Items.GLASS_BOTTLE), 1, "Alchemy returns the glass bottle");
            helper.assertTrue(ItemStack.isSameItemSameComponents(interruptedBag.getItem(0), potion), "Disabling mid-use cannot lose a potion");
            helper.assertFalse(interrupted.hasEffect(MobEffects.FIRE_RESISTANCE), "Disabling mid-use cancels the effect");
            helper.succeed();
        });
    }
}
