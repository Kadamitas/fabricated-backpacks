package com.kadamitas.fabricatedbackpacks.gametest;

import com.kadamitas.fabricatedbackpacks.block.BackpackBlockEntity;
import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.domain.ExperienceMath;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.mixin.ExperienceOrbAccessor;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.resource.BackpackBattery;
import com.kadamitas.fabricatedbackpacks.resource.BackpackTank;
import com.kadamitas.fabricatedbackpacks.resource.FluidAmount;
import com.kadamitas.fabricatedbackpacks.resource.ResourceComponents;
import com.kadamitas.fabricatedbackpacks.resource.ResourceRuntime;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.storage.InstalledUpgrade;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.transfer.v1.context.ContainerItemContext;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.ContainerStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import team.reborn.energy.api.EnergyStorage;
import team.reborn.energy.api.base.SimpleEnergyItem;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Actual Fabric and Energy API fixtures live only in the GameTest mod, never in the release jar. */
public final class ResourceGameTests {
    private static final FluidVariant WATER = FluidVariant.of(Fluids.WATER);
    private static final Set<BlockPos> PROTECTED_POSITIONS = new HashSet<>();
    private static Item energyCell;

    private ResourceGameTests() {}

    public static void registerFixtures() {
        if (energyCell != null) return;
        Identifier id = Identifier.fromNamespaceAndPath("fabricated_backpacks_tests", "energy_cell");
        // Item.Properties derives ITEM_MODEL from the registry ID after component initialization.
        // The test resource pack supplies its item definition; no fixture art enters the release jar.
        energyCell = Registry.register(BuiltInRegistries.ITEM, id,
                new TestEnergyCell(new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id)).stacksTo(1)));
        PlayerBlockBreakEvents.BEFORE.register((level, player, position, state, entity) -> !PROTECTED_POSITIONS.contains(position));
    }

    private static final class TestEnergyCell extends Item implements SimpleEnergyItem {
        TestEnergyCell(Properties properties) { super(properties); }
        @Override public long getEnergyCapacity(ItemStack stack) { return 1_000; }
        @Override public long getEnergyMaxInput(ItemStack stack) { return 100; }
        @Override public long getEnergyMaxOutput(ItemStack stack) { return 100; }
    }

    private static BagInventory bag(UpgradeKind... upgrades) {
        return BackpackTestSupport.bag(BackpackTier.NETHERITE, upgrades);
    }
    private static InstalledUpgrade upgrade(BagInventory bag, int slot) { return BackpackTestSupport.upgrade(bag, slot); }
    private static BackpackTank tank(BagInventory bag) { return new BackpackTank(bag, upgrade(bag, 0), false); }
    private static ContainerItemContext context(Container container, int slot) {
        return ContainerItemContext.ofSingleSlot(ContainerStorage.of(container, null).getSlot(slot));
    }
    private static void fill(BackpackTank tank, FluidVariant resource, long droplets) {
        try (Transaction transaction = Transaction.openOuter()) {
            if (tank.insert(resource, droplets, transaction) != droplets) throw new IllegalArgumentException("Test fill does not fit");
            transaction.commit();
        }
    }
    private static long amount(Storage<FluidVariant> storage) {
        long total = 0;
        for (var view : storage) total += view.getAmount();
        return total;
    }
    private static void resetPump(BagInventory bag) {
        bag.updateSettings(upgrade(bag, 1), settings -> settings.putLong("next_work", 0));
    }

    public static void tankTransactions(GameTestHelper helper) {
        BagInventory bag = bag(UpgradeKind.TANK);
        BackpackTank first = tank(bag);
        BackpackTank second = tank(bag);
        long capacity = 40_000L * FluidAmount.DROPLETS_PER_MB;
        helper.assertValueEqual(first.getCapacity(), capacity, "Netherite tank has ten rows of capacity");
        try (Transaction outer = Transaction.openOuter()) {
            helper.assertValueEqual(first.insert(WATER, 100, outer), 100L, "First alias accepts individual droplets");
            try (Transaction nested = outer.openNested()) {
                second.insert(WATER, 200, nested);
                nested.commit();
            }
            helper.assertValueEqual(first.getAmount(), 300L, "Both API handles share one live state");
        }
        helper.assertValueEqual(second.getAmount(), 0L, "Outer rollback also undoes committed nested aliases");
        helper.assertTrue(first.isResourceBlank(), "Rolled-back empty tank has no phantom fluid");
        try (Transaction outer = Transaction.openOuter()) {
            first.insert(WATER, 500, outer);
            try (Transaction nested = outer.openNested()) { second.extract(WATER, 123, nested); }
            helper.assertValueEqual(first.getAmount(), 500L, "Nested abort restores only its own debit");
            outer.commit();
        }
        try (Transaction transaction = Transaction.openOuter()) {
            helper.assertValueEqual(second.insert(FluidVariant.of(Fluids.LAVA), 1, transaction), 0L, "A tank cannot mix fluid identities");
            helper.assertValueEqual(second.insert(WATER, Long.MAX_VALUE, transaction), capacity - 500, "Insertion stops at exact capacity");
            transaction.commit();
        }
        helper.assertValueEqual(first.getAmount(), capacity, "Saturating request does not overflow");
        InstalledUpgrade detached = upgrade(bag, 0);
        bag.upgrades().setItem(0, new ItemStack(BackpackRegistry.item(UpgradeKind.TANK)));
        try (Transaction transaction = Transaction.openOuter()) {
            helper.assertValueEqual(first.extract(WATER, 1, transaction), 0L, "A stale API cannot access a replaced upgrade");
        }
        helper.assertTrue(detached.stack().get(ResourceComponents.TANK_FLUID).equals(WATER), "Detached upgrade still owns its resource");
        helper.succeed();
    }

    public static void tankPersistenceAndCapacity(GameTestHelper helper) {
        BagInventory bag = bag(UpgradeKind.TANK, UpgradeKind.STACK_UPGRADE_TIER_1);
        FluidVariant namedWater = FluidVariant.of(Fluids.WATER, DataComponentPatch.builder()
                .set(DataComponents.CUSTOM_NAME, Component.literal("Sample A")).build());
        long base = 40_000L * FluidAmount.DROPLETS_PER_MB;
        fill(tank(bag), namedWater, base + 1);
        helper.assertFalse(bag.canRemoveUpgrade(1), "One excess droplet blocks a capacity-reducing upgrade removal");
        BagInventory restored = BagInventory.of(BackpackTestSupport.roundTrip(helper.getLevel(), bag.stack()));
        helper.assertValueEqual(tank(restored).getAmount(), base + 1, "Item codec retains sub-millibucket quantities");
        helper.assertTrue(tank(restored).getResource().equals(namedWater), "Fluid component data survives the real item codec");
        helper.assertFalse(restored.canRemoveUpgrade(1), "Reloading cannot bypass capacity constraints");
        try (Transaction transaction = Transaction.openOuter()) {
            helper.assertValueEqual(tank(restored).extract(WATER, 1, transaction), 0L, "Unnamed fluid does not match a named fluid variant");
            helper.assertValueEqual(tank(restored).extract(namedWater, 1, transaction), 1L, "Exact component variant can be drained");
            transaction.commit();
        }
        helper.assertTrue(restored.canRemoveUpgrade(1), "Exactly fitting contents allow stack upgrade removal");
        restored.upgrades().setItem(1, ItemStack.EMPTY);
        helper.assertValueEqual(tank(restored).getCapacity(), base, "Capacity follows the installed upgrades after removal");
        helper.assertValueEqual(tank(restored).getAmount(), base, "Capacity change never truncates stored fluid");
        helper.succeed();
    }

    public static void itemApiTransactions(GameTestHelper helper) {
        BagInventory bag = bag(UpgradeKind.TANK, UpgradeKind.BATTERY);
        SimpleContainer inventory = new SimpleContainer(bag.stack());
        ItemStack original = inventory.getItem(0).copy();
        ContainerItemContext context = context(inventory, 0);
        Storage<FluidVariant> fluid = context.find(FluidStorage.ITEM);
        EnergyStorage energy = context.find(EnergyStorage.ITEM);
        helper.assertTrue(fluid != null && energy != null, "Backpack items expose both real item APIs");
        try (Transaction outer = Transaction.openOuter()) {
            helper.assertValueEqual(fluid.insert(WATER, 17, outer), 17L, "Fluid API writes its real context");
            try (Transaction nested = outer.openNested()) {
                helper.assertValueEqual(energy.insert(37, nested), 37L, "Energy API sees the current context variant");
                nested.commit();
            }
        }
        helper.assertTrue(ItemStack.matches(original, inventory.getItem(0)), "Aborting both item APIs restores the original item components");
        try (Transaction transaction = Transaction.openOuter()) {
            fluid.insert(WATER, 12345, transaction);
            energy.insert(75, transaction);
            transaction.commit();
        }
        BagInventory saved = BagInventory.of(BackpackTestSupport.roundTrip(helper.getLevel(), inventory.getItem(0)));
        helper.assertValueEqual(tank(saved).getAmount(), 12345L, "Fluid item API persists to the actual inventory slot");
        helper.assertValueEqual(ResourceRuntime.batteryStored(saved, 1), 75L, "Energy item API persists alongside fluid components");
        var retainedView = fluid.iterator().next();
        inventory.setItem(0, new ItemStack(Items.STONE));
        try (Transaction transaction = Transaction.openOuter()) {
            helper.assertValueEqual(retainedView.extract(WATER, 1, transaction), 0L, "Old item views cannot mutate a replacement item");
            helper.assertValueEqual(energy.insert(1, transaction), 0L, "Old energy lookups also reject a replacement item");
        }
        helper.succeed();
    }

    public static void containerTransfers(GameTestHelper helper) {
        BagInventory exchange = bag(UpgradeKind.TANK);
        Container exchangeSlots = exchange.upgradeInventory(upgrade(exchange, 0));
        exchangeSlots.setItem(0, new ItemStack(Items.WATER_BUCKET));
        exchangeSlots.setItem(1, new ItemStack(Items.BUCKET));
        BagInventory blocked = bag(UpgradeKind.TANK);
        Container blockedSlots = blocked.upgradeInventory(upgrade(blocked, 0));
        blockedSlots.setItem(0, new ItemStack(Items.WATER_BUCKET));
        blockedSlots.setItem(2, new ItemStack(Items.STONE, 64));
        BagInventory tooFull = bag(UpgradeKind.TANK);
        Container fullSlots = tooFull.upgradeInventory(upgrade(tooFull, 0));
        fullSlots.setItem(0, new ItemStack(Items.WATER_BUCKET));
        fill(tank(tooFull), WATER, FluidAmount.dropletsForMb(39_500));
        BagInventory bottle = bag(UpgradeKind.TANK);
        ItemStack waterBottle = new ItemStack(Items.POTION);
        waterBottle.set(DataComponents.POTION_CONTENTS, new PotionContents(Potions.WATER));
        bottle.upgradeInventory(upgrade(bottle, 0)).setItem(0, waterBottle);
        BagInventory xpBottle = bag(UpgradeKind.TANK);
        xpBottle.upgradeInventory(upgrade(xpBottle, 0)).setItem(0, new ItemStack(Items.EXPERIENCE_BOTTLE));
        List<BagInventory> bags = List.of(exchange, blocked, tooFull, bottle, xpBottle);
        BlockPos position = helper.absolutePos(new BlockPos(2, 2, 2));
        helper.onEachTick(() -> bags.forEach(value -> ResourceRuntime.tick(value, helper.getLevel(), position, null)));
        helper.runAfterDelay(21, () -> {
            helper.assertTrue(exchangeSlots.getItem(0).isEmpty() && exchangeSlots.getItem(1).isEmpty(), "Both consumed containers leave their input slots");
            helper.assertTrue(exchangeSlots.getItem(2).is(Items.BUCKET) && exchangeSlots.getItem(3).is(Items.WATER_BUCKET), "Tank has separate drain and fill result slots");
            helper.assertValueEqual(tank(exchange).getAmount(), 0L, "Draining then filling conserves the bucket of water");
            helper.assertTrue(blockedSlots.getItem(0).is(Items.WATER_BUCKET), "An occupied output rolls back container consumption");
            helper.assertValueEqual(tank(blocked).getAmount(), 0L, "Failed output cannot leave copied fluid in the tank");
            helper.assertTrue(fullSlots.getItem(0).is(Items.WATER_BUCKET), "A bucket cannot be partially consumed into insufficient space");
            helper.assertValueEqual(tank(tooFull).getAmount(), FluidAmount.dropletsForMb(39_500), "Insufficient capacity leaves the original fluid quantity unchanged");
            helper.assertValueEqual(tank(bottle).getAmount(), FluidConstants.BOTTLE, "Water bottles use the canonical Fabric one-third-bucket quantity");
            helper.assertTrue(bottle.upgradeInventory(upgrade(bottle, 0)).getItem(2).is(Items.GLASS_BOTTLE), "Water bottle produces a real glass bottle");
            helper.assertValueEqual(tank(xpBottle).getAmount(), FluidAmount.dropletsForMb(160), "Experience bottle stores exactly eight points");
            helper.assertTrue(tank(xpBottle).getResource().equals(ResourceComponents.experience()), "XP bottle uses the registered experience fluid");
            helper.succeed();
        });
    }

    public static void energyTransactions(GameTestHelper helper) {
        BagInventory bag = bag(UpgradeKind.BATTERY);
        InstalledUpgrade upgrade = upgrade(bag, 0);
        BackpackBattery battery = new BackpackBattery(bag, upgrade);
        BackpackBattery alias = new BackpackBattery(bag, upgrade);
        helper.assertValueEqual(battery.getCapacity(), 100_000L, "Battery capacity follows ten rows");
        try (Transaction outer = Transaction.openOuter()) {
            helper.assertValueEqual(battery.insert(1_000, outer), 200L, "Battery limits each operation to the row-scaled rate");
            try (Transaction nested = outer.openNested()) {
                alias.extract(70, nested);
                nested.commit();
            }
        }
        helper.assertValueEqual(alias.getAmount(), 0L, "Nested energy operations roll back across aliases");
        bag.updateSettings(upgrade, state -> state.putLong("amount", 100));
        Container slots = bag.upgradeInventory(upgrade);
        ItemStack source = new ItemStack(energyCell);
        source.set(EnergyStorage.ENERGY_COMPONENT, 500L);
        ItemStack destination = new ItemStack(energyCell);
        destination.set(EnergyStorage.ENERGY_COMPONENT, 950L);
        slots.setItem(0, source);
        slots.setItem(1, destination);
        ResourceRuntime.tick(bag, helper.getLevel(), helper.absolutePos(new BlockPos(2, 2, 2)), null);
        long sourceLeft = context(slots, 0).find(EnergyStorage.ITEM).getAmount();
        long destinationStored = context(slots, 1).find(EnergyStorage.ITEM).getAmount();
        helper.assertValueEqual(sourceLeft, 400L, "Real Energy API item discharges only its own per-operation rate");
        helper.assertValueEqual(destinationStored, 1_000L, "Near-full Energy API item accepts only its remaining space");
        helper.assertValueEqual(battery.getAmount() + sourceLeft + destinationStored, 1_550L, "Partial energy acceptance conserves all three participants");
        bag.updateSettings(upgrade, state -> state.putLong("amount", battery.getCapacity() - 25));
        slots.setItem(1, ItemStack.EMPTY);
        long combined = battery.getAmount() + sourceLeft;
        ResourceRuntime.tick(bag, helper.getLevel(), helper.absolutePos(new BlockPos(2, 2, 2)), null);
        helper.assertValueEqual(battery.getAmount(), battery.getCapacity(), "Battery can accept an exact partial final charge");
        helper.assertValueEqual(battery.getAmount() + context(slots, 0).find(EnergyStorage.ITEM).getAmount(), combined, "The item loses only the battery's accepted energy");
        BlockPos position = helper.absolutePos(new BlockPos(4, 2, 4));
        helper.getLevel().setBlock(position, BackpackRegistry.block(BackpackTier.NETHERITE).defaultBlockState(), 3);
        BackpackBlockEntity placed = (BackpackBlockEntity) helper.getLevel().getBlockEntity(position);
        placed.setStack(bag.stack());
        EnergyStorage sided = EnergyStorage.SIDED.find(helper.getLevel(), position, Direction.NORTH);
        helper.assertTrue(sided != null, "Placed backpacks expose the actual Team Reborn sided API");
        try (Transaction transaction = Transaction.openOuter()) { sided.extract(100, transaction); }
        helper.assertValueEqual(sided.getAmount(), battery.getAmount(), "Aborted sided extraction restores block entity contents");
        helper.succeed();
    }

    public static void pumpHandlers(GameTestHelper helper) {
        BlockPos position = helper.absolutePos(new BlockPos(3, 2, 3));
        BlockPos neighbor = position.east();
        BagInventory supply = bag(UpgradeKind.TANK);
        fill(tank(supply), WATER, 2 * FluidConstants.BUCKET);
        helper.getLevel().setBlock(neighbor, BackpackRegistry.block(BackpackTier.NETHERITE).defaultBlockState(), 3);
        BackpackBlockEntity placed = (BackpackBlockEntity) helper.getLevel().getBlockEntity(neighbor);
        placed.setStack(supply.stack());
        BagInventory pumping = bag(UpgradeKind.TANK, UpgradeKind.PUMP);
        helper.assertTrue(FluidStorage.SIDED.find(helper.getLevel(), neighbor, Direction.WEST) != null, "Neighbor exposes real Fabric fluid storage");
        Storage<ItemVariant> itemStorage = ItemStorage.SIDED.find(helper.getLevel(), neighbor, Direction.WEST);
        try (Transaction transaction = Transaction.openOuter()) {
            helper.assertValueEqual(itemStorage.insert(ItemVariant.of(Items.DIRT), 4, transaction), 4L, "The placed backpack also exposes its owned item container");
            transaction.commit();
        }
        helper.assertValueEqual(BackpackTestSupport.count(placed.inventory(), Items.DIRT), 4, "Item API modifies the actual block inventory");
        ResourceRuntime.tick(pumping, helper.getLevel(), position, null);
        helper.assertValueEqual(tank(pumping).getAmount(), FluidConstants.BUCKET, "Basic pump pulls one bucket from an adjacent handler");
        helper.assertValueEqual(tank(placed.inventory()).getAmount(), FluidConstants.BUCKET, "Adjacent source loses exactly the accepted fluid");
        ResourceRuntime.tick(pumping, helper.getLevel(), position, null);
        helper.assertValueEqual(tank(pumping).getAmount(), FluidConstants.BUCKET, "Handler cooldown prevents a repeated operation in one tick");
        pumping.updateSettings(upgrade(pumping, 1), state -> state.putString("direction", "output"));
        resetPump(pumping);
        ResourceRuntime.tick(pumping, helper.getLevel(), position, null);
        helper.assertValueEqual(tank(pumping).getAmount() + tank(placed.inventory()).getAmount(), 2 * FluidConstants.BUCKET, "Reversing a handler transfer conserves total fluid");
        helper.assertValueEqual(tank(pumping).getAmount(), 0L, "Output mode returns the bucket to the adjacent handler");

        ServerPlayer player = BackpackTestSupport.player(helper);
        BagInventory handPump = bag(UpgradeKind.TANK, UpgradeKind.ADVANCED_PUMP);
        handPump.updateSettings(upgrade(handPump, 1), state -> state.putBoolean("handlers", false));
        handPump.setFilter(upgrade(handPump, 1), 0, new ItemStack(Items.LAVA_BUCKET));
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WATER_BUCKET));
        ResourceRuntime.tick(handPump, helper.getLevel(), position, player);
        helper.assertValueEqual(tank(handPump).getAmount(), 0L, "Advanced fluid filters reject a different held fluid");
        handPump.setFilter(upgrade(handPump, 1), 0, new ItemStack(Items.WATER_BUCKET));
        resetPump(handPump);
        ResourceRuntime.tick(handPump, helper.getLevel(), position, player);
        helper.assertTrue(player.getMainHandItem().is(Items.BUCKET), "Advanced pump exchanges the actual held bucket");
        helper.assertValueEqual(tank(handPump).getAmount(), FluidConstants.BUCKET, "Held input reaches the tank exactly once");
        handPump.updateSettings(upgrade(handPump, 1), state -> state.putString("direction", "output"));
        resetPump(handPump);
        ResourceRuntime.tick(handPump, helper.getLevel(), position, player);
        helper.assertTrue(player.getMainHandItem().is(Items.WATER_BUCKET), "Advanced output fills the player's actual hand");
        helper.assertValueEqual(tank(handPump).getAmount(), 0L, "Held round trip conserves its bucket");
        helper.succeed();
    }

    public static void pumpWorld(GameTestHelper helper) {
        BlockPos position = helper.absolutePos(new BlockPos(3, 2, 3));
        BlockPos source = position.north();
        ServerPlayer player = BackpackTestSupport.player(helper);
        for (Direction direction : Direction.values()) helper.getLevel().setBlock(position.relative(direction), Blocks.STONE.defaultBlockState(), 3);
        helper.getLevel().setBlock(source, Blocks.WATER.defaultBlockState(), 3);
        BagInventory bag = bag(UpgradeKind.TANK, UpgradeKind.ADVANCED_PUMP);
        bag.updateSettings(upgrade(bag, 1), state -> {
            state.putBoolean("hands", false);
            state.putBoolean("handlers", false);
            state.putBoolean("world", true);
        });
        PROTECTED_POSITIONS.add(source);
        try { ResourceRuntime.tick(bag, helper.getLevel(), position, player); }
        finally { PROTECTED_POSITIONS.remove(source); }
        helper.assertTrue(helper.getLevel().getFluidState(source).isSource(), "Protection event veto leaves world source untouched");
        helper.assertValueEqual(tank(bag).getAmount(), 0L, "Protection veto cannot copy source fluid into a tank");
        fill(tank(bag), WATER, FluidAmount.dropletsForMb(39_500));
        resetPump(bag);
        ResourceRuntime.tick(bag, helper.getLevel(), position, player);
        helper.assertTrue(helper.getLevel().getFluidState(source).isSource(), "Insufficient capacity does not destroy a source block");
        helper.assertValueEqual(tank(bag).getAmount(), FluidAmount.dropletsForMb(39_500), "Partial source insertion rolls back entirely");
        try (Transaction transaction = Transaction.openOuter()) {
            tank(bag).extract(WATER, Long.MAX_VALUE, transaction);
            transaction.commit();
        }
        resetPump(bag);
        ResourceRuntime.tick(bag, helper.getLevel(), position, player);
        helper.assertTrue(helper.getLevel().getBlockState(source).isAir(), "Successful source input replaces the source with air");
        helper.assertValueEqual(tank(bag).getAmount(), FluidConstants.BUCKET, "World input consumes exactly one full source bucket");
        helper.getLevel().setBlock(source, Blocks.STONE.defaultBlockState(), 3);
        helper.getLevel().setBlock(position.above(), Blocks.AIR.defaultBlockState(), 3);
        bag.updateSettings(upgrade(bag, 1), state -> state.putString("direction", "output"));
        resetPump(bag);
        ResourceRuntime.tick(bag, helper.getLevel(), position, player);
        helper.assertTrue(helper.getLevel().getBlockState(position.above()).isAir(), "World output never targets the upper face");
        helper.assertValueEqual(tank(bag).getAmount(), FluidConstants.BUCKET, "A blocked output keeps its full bucket");
        helper.getLevel().setBlock(position.east(), Blocks.AIR.defaultBlockState(), 3);
        resetPump(bag);
        ResourceRuntime.tick(bag, helper.getLevel(), position, player);
        helper.assertTrue(helper.getLevel().getFluidState(position.east()).isSource(), "World output creates a real source in an eligible adjacent cell");
        helper.assertValueEqual(tank(bag).getAmount(), 0L, "Successful world output debits exactly the placed bucket");
        helper.succeed();
    }

    public static void xpTransfers(GameTestHelper helper) {
        ServerPlayer player = BackpackTestSupport.player(helper);
        player.setExperienceLevels(17);
        player.setExperiencePoints(5);
        long original = ExperienceMath.pointsAtLevel(17) + 5;
        BagInventory bag = bag(UpgradeKind.TANK, UpgradeKind.XP_PUMP);
        ResourceRuntime.action(bag, 1, "store", player);
        long stored = original - ExperienceMath.pointsAtLevel(16);
        helper.assertValueEqual(player.experienceLevel, 16, "Store-one-level uses nonlinear vanilla level boundaries");
        helper.assertValueEqual(tank(bag).getAmount(), stored * FluidAmount.DROPLETS_PER_XP, "Level transfer stores actual points at twenty millibuckets each");
        ResourceRuntime.action(bag, 1, "take", player);
        helper.assertValueEqual(player.experienceLevel, 17, "Take-one-level reaches the next level boundary");
        helper.assertValueEqual(tank(bag).getAmount(), 5 * FluidAmount.DROPLETS_PER_XP, "A partial original level remains represented in the tank");
        ResourceRuntime.action(bag, 1, "store_all", player);
        helper.assertValueEqual(player.experienceLevel, 0, "Store-all can drain a survival player's experience");
        helper.assertValueEqual(tank(bag).getAmount(), original * FluidAmount.DROPLETS_PER_XP, "Full transfer conserves the original point total");
        ResourceRuntime.action(bag, 1, "take_all", player);
        helper.assertValueEqual(player.experienceLevel, 17, "Taking all restores the original nonlinear level");
        helper.assertValueEqual(Math.round(player.experienceProgress * player.getXpNeededForNextLevel()), 5, "Taking all restores the five partial points");
        helper.assertValueEqual(tank(bag).getAmount(), 0L, "Full round trip empties its experience tank");
        bag.updateSettings(upgrade(bag, 1), state -> { state.putInt("target", Integer.MAX_VALUE); state.putInt("levels", Integer.MIN_VALUE); });
        ResourceRuntime.action(bag, 1, "target_up", player);
        ResourceRuntime.action(bag, 1, "levels_down", player);
        helper.assertValueEqual(bag.settings(upgrade(bag, 1)).getIntOr("target", -1), 10_000, "Target adjustment clamps without overflowing");
        helper.assertValueEqual(bag.settings(upgrade(bag, 1)).getIntOr("levels", -1), 1, "Level adjustment clamps without underflowing");
        long nearlyFull = tank(bag).getCapacity() - FluidAmount.DROPLETS_PER_XP + 1;
        fill(tank(bag), ResourceComponents.experience(), nearlyFull);
        helper.assertValueEqual(ResourceRuntime.offerExperience(bag, 1), 0L, "A space smaller than one point rejects a whole-point source");
        ResourceRuntime.action(bag, 1, "store_all", player);
        helper.assertValueEqual(tank(bag).getAmount(), nearlyFull, "Whole-point transfer leaves fractional free space unchanged");
        helper.assertValueEqual(player.experienceLevel, 17, "A failed whole-point transfer leaves the player unchanged");
        helper.succeed();
    }

    public static void xpMendingAndCollection(GameTestHelper helper) {
        ServerPlayer player = BackpackTestSupport.player(helper);
        BagInventory mending = bag(UpgradeKind.TANK, UpgradeKind.XP_PUMP);
        mending.updateSettings(upgrade(mending, 1), state -> state.putString("direction", "off"));
        ResourceRuntime.offerExperience(mending, 1);
        ItemStack tool = new ItemStack(Items.DIAMOND_PICKAXE);
        tool.enchant(helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.MENDING), 1);
        tool.setDamageValue(1);
        player.setItemInHand(InteractionHand.MAIN_HAND, tool);
        BagInventory cooking = bag(UpgradeKind.TANK, UpgradeKind.AUTO_SMELTING);
        cooking.updateSettings(upgrade(cooking, 1), state -> state.putDouble("experience", 2.75));
        BagInventory magnet = bag(UpgradeKind.TANK, UpgradeKind.MAGNET);
        long before = tank(magnet).getCapacity() - 10 * FluidAmount.DROPLETS_PER_XP;
        fill(tank(magnet), ResourceComponents.experience(), before);
        Vec3 orbPosition = helper.absoluteVec(new Vec3(1.5, 2, 1.5));
        ExperienceOrb orb = new ExperienceOrb(helper.getLevel(), orbPosition, Vec3.ZERO, 7);
        ((ExperienceOrbAccessor) orb).fabricatedBackpacks$setCount(3);
        orb.setNoGravity(true);
        helper.getLevel().addFreshEntity(orb);
        BlockPos magnetPosition = BlockPos.containing(orbPosition);
        helper.onEachTick(() -> {
            ResourceRuntime.tick(mending, helper.getLevel(), player.blockPosition(), player);
            ResourceRuntime.tick(cooking, helper.getLevel(), magnetPosition, null);
            ResourceRuntime.tick(magnet, helper.getLevel(), magnetPosition, null);
        });
        helper.runAfterDelay(12, () -> {
            helper.assertValueEqual(player.getMainHandItem().getDamageValue(), 0, "Mending repairs equipped tools even with XP transfer off");
            helper.assertValueEqual(tank(mending).getAmount(), FluidAmount.dropletsForMb(10), "One durability repair spends exactly half an XP point");
            helper.assertValueEqual(tank(cooking).getAmount(), 2 * FluidAmount.DROPLETS_PER_XP, "Auto-cooking hands off only complete stored experience points");
            helper.assertValueEqual(cooking.settings(upgrade(cooking, 1)).getDoubleOr("experience", -1), 0.75, "Fractional cooking XP remains in the machine");
            long remaining = helper.getLevel().getEntitiesOfClass(ExperienceOrb.class, new AABB(magnetPosition).inflate(3), value -> !value.isRemoved()).stream()
                    .mapToLong(value -> (long) value.getValue() * ((ExperienceOrbAccessor) value).fabricatedBackpacks$getCount()).sum();
            long captured = (tank(magnet).getAmount() - before) / FluidAmount.DROPLETS_PER_XP;
            helper.assertValueEqual(captured, 10L, "A partly available tank accepts exactly its remaining whole points");
            helper.assertValueEqual(captured + remaining, 21L, "Splitting a merged orb preserves every point in all three original orbs");
            helper.succeed();
        });
    }
}
