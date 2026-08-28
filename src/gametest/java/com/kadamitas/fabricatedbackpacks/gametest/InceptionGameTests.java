package com.kadamitas.fabricatedbackpacks.gametest;

import com.kadamitas.fabricatedbackpacks.compat.NbtAccess;
import com.kadamitas.fabricatedbackpacks.block.BackpackBlockEntity;
import com.kadamitas.fabricatedbackpacks.config.BackpackConfig;
import com.kadamitas.fabricatedbackpacks.config.ConfigFile;
import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.gameplay.BackpackTraversal;
import com.kadamitas.fabricatedbackpacks.item.BackpackTooltip;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.resource.BackpackItemStorage;
import com.kadamitas.fabricatedbackpacks.resource.BackpackTank;
import com.kadamitas.fabricatedbackpacks.resource.ResourceRuntime;
import com.kadamitas.fabricatedbackpacks.storage.BagComponents;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.storage.InventorySnapshot;
import com.kadamitas.fabricatedbackpacks.upgrade.CompactingRuntime;
import com.kadamitas.fabricatedbackpacks.upgrade.ConsumptionRuntime;
import com.kadamitas.fabricatedbackpacks.upgrade.CookingRuntime;
import com.kadamitas.fabricatedbackpacks.upgrade.InventoryMoves;
import com.kadamitas.fabricatedbackpacks.upgrade.ToolRuntime;
import com.kadamitas.fabricatedbackpacks.upgrade.TransferRuntime;
import com.kadamitas.fabricatedbackpacks.upgrade.UpgradeEngine;
import net.fabricmc.fabric.api.transfer.v1.context.ContainerItemContext;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.material.Fluids;
import team.reborn.energy.api.EnergyStorage;

import static com.kadamitas.fabricatedbackpacks.gametest.BackpackTestSupport.*;

/** Physical containment, ordered processing and real API transactions, without enlarging the root menu. */
public final class InceptionGameTests {
    private static final FluidVariant WATER = FluidVariant.of(Fluids.WATER);
    private InceptionGameTests() {}

    private static BagInventory root(UpgradeKind... extra) {
        var kinds = new UpgradeKind[extra.length + 1];
        kinds[0] = UpgradeKind.INCEPTION;
        System.arraycopy(extra, 0, kinds, 1, extra.length);
        return bag(BackpackTier.NETHERITE, kinds);
    }

    private static void attach(BagInventory root, int slot, BagInventory child) {
        if (!root.canPlaceItem(slot, child.stack())) throw new IllegalArgumentException("Invalid child fixture");
        root.setItem(slot, child.stack());
    }

    private static BagInventory savedChild(GameTestHelper helper, BagInventory root, int slot) {
        BagInventory saved = BagInventory.of(roundTrip(helper.getLevel(), root.stack()));
        return BagInventory.of(saved.getItem(slot));
    }

    private static BackpackTank tank(BagInventory bag, int slot) { return new BackpackTank(bag, upgrade(bag, slot), false); }

    private static StorageView<ItemVariant> viewOf(Storage<ItemVariant> storage, ItemVariant item) {
        for (var view : storage) if (view.getResource().equals(item)) return view;
        throw new IllegalArgumentException("Missing test resource view");
    }

    public static void nestedStructureAndOrdering(GameTestHelper helper) {
        BagInventory root = root(UpgradeKind.STACK_UPGRADE_TIER_1);
        int size = root.getContainerSize();
        ItemStack fresh = new ItemStack(BackpackRegistry.item(BackpackTier.LEATHER), 2);
        helper.assertTrue(UpgradeEngine.insert(root, fresh, false).isEmpty(), "Two fresh bags fit as separate physical children");
        helper.assertValueEqual(root.getItem(0).getCount(), 1, "A stack upgrade cannot merge child carriers");
        helper.assertValueEqual(root.getItem(1).getCount(), 1, "The second child has its own slot");
        var children = BackpackTraversal.children(root);
        helper.assertValueEqual(children.size(), 2, "Both fresh children become independently addressable");
        helper.assertFalse(children.get(0).inventory().identity().equals(children.get(1).inventory().identity()), "New child identities are distinct");
        BagInventory first = children.getFirst().inventory();
        BagInventory second = children.getLast().inventory();
        root.setItem(2, new ItemStack(Items.STONE, 11));
        helper.assertTrue(UpgradeEngine.insert(root, new ItemStack(Items.STONE, 68), false).isEmpty(), "Child-first insertion handles all offered items");
        helper.assertValueEqual(first.getItem(0).getCount(), 64, "A child retains its own slot capacity, not the outer multiplier");
        helper.assertValueEqual(first.getItem(1).getCount(), 4, "Excess occupies the next child cell");
        helper.assertValueEqual(root.getItem(2).getCount(), 11, "An existing outer stack cannot override children-first ordering");
        helper.assertTrue(second.isEmpty(), "Physical child order is stable");
        helper.assertValueEqual(root.getContainerSize(), size, "Processing never expands the root's physical menu");

        root.updateSettings(tag -> tag.putBoolean("inception_nested_first", false));
        UpgradeEngine.insert(root, new ItemStack(Items.STONE, 9), false);
        helper.assertValueEqual(root.getItem(2).getCount(), 20, "Main-first processing visits the root before children");
        root.updateSettings(tag -> tag.putBoolean("inception_outer_inventory", false));
        UpgradeEngine.insert(root, new ItemStack(Items.REDSTONE, 7), false);
        helper.assertValueEqual(count(root, Items.REDSTONE), 7, "Disabling outer access confines insertion to root storage");
        helper.assertValueEqual(count(first, Items.REDSTONE), 0, "Child storage is unchanged while outer access is off");
        helper.assertTrue(BackpackTraversal.ticksChildren(root), "Child-upgrade ticking is independent of outer inventory access");
        helper.assertFalse(root.canRemoveUpgrade(0), "Inception cannot be removed while physical children remain");
        helper.assertFalse(root.canPlaceItem(7, root.stack()), "A root cannot be its own child");
        helper.assertFalse(root.canPlaceItem(7, root().stack()), "A child cannot itself contain Inception");
        helper.assertFalse(first.canPlaceItem(7, second.stack()), "The child cannot accept grandchildren");

        root.setItem(6, first.stack().copy());
        BagInventory recursive = bag(BackpackTier.LEATHER);
        recursive.setItem(0, new ItemStack(BackpackRegistry.item(BackpackTier.LEATHER)));
        root.setItem(7, recursive.stack());
        helper.assertValueEqual(BackpackTraversal.children(root).size(), 2, "Copied identities and malformed recursive children are excluded from traversal");
        helper.succeed();
    }

    public static void nestedSimulationAndStaleViews(GameTestHelper helper) {
        BagInventory root = root();
        root.setItem(0, new ItemStack(BackpackRegistry.item(BackpackTier.LEATHER)));
        ItemStack beforeRoot = root.stack().copy();
        ItemStack beforeChild = root.getItem(0).copy();
        helper.assertTrue(UpgradeEngine.insert(root, new ItemStack(Items.STONE, 100), true).isEmpty(), "Simulation can plan capacity in an uninitialized child");
        assertStack(helper, root.stack(), beforeRoot, "Simulation does not change root components");
        assertStack(helper, root.getItem(0), beforeChild, "Simulation does not allocate an identity or change the actual child");

        Container view = BackpackTraversal.processingInventory(root);
        BagInventory child = BagInventory.of(root.getItem(0));
        helper.assertTrue(UpgradeEngine.insert(root, new ItemStack(Items.DIAMOND, 21), false).isEmpty(), "Actual insertion publishes child contents");
        helper.assertValueEqual(count(savedChild(helper, root, 0), Items.DIAMOND), 21, "Child contents survive the real parent item codec");
        var plan = InventoryMoves.snapshot(view);
        ItemStack detached = root.getItem(0);
        root.setItem(0, new ItemStack(BackpackRegistry.item(BackpackTier.IRON)));
        view.setItem(0, new ItemStack(Items.EMERALD, 64));
        helper.assertTrue(view.removeItem(0, 1).isEmpty(), "A retained processing cell cannot extract from a removed child");
        helper.assertValueEqual(count(child, Items.DIAMOND), 21, "A stale write leaves the detached child intact");
        helper.assertValueEqual(count(BagInventory.of(root.getItem(0)), Items.EMERALD), 0, "A stale write cannot target the replacement child");
        boolean rejected = false;
        try { InventoryMoves.commit(view, plan); } catch (IllegalStateException expected) { rejected = true; }
        helper.assertTrue(rejected, "A plan spanning a changed child graph is rejected before commit");
        helper.assertFalse(root.getItem(0) == detached, "A stale plan cannot resurrect a removed carrier");
        helper.succeed();
    }

    public static void nestedProcessingAndFilters(GameTestHelper helper) {
        BagInventory root = root(UpgradeKind.ADVANCED_COMPACTING, UpgradeKind.TOOL_SWAPPER, UpgradeKind.ADVANCED_REFILL);
        BagInventory child = bag(BackpackTier.LEATHER, UpgradeKind.FILTER);
        attach(root, 0, child);
        child.setItem(0, new ItemStack(Items.IRON_INGOT, 9));
        child.updateSettings(upgrade(child, 0), tag -> { tag.putString("filter_direction", "OUTPUT"); tag.putString("filter_mode", "ALLOW"); });
        helper.assertValueEqual(CompactingRuntime.compact(root, upgrade(root, 1), helper.getLevel(), 1), 0, "Outer compaction respects a child's output filter");
        child.setFilter(upgrade(child, 0), 0, new ItemStack(Items.IRON_INGOT));
        helper.assertValueEqual(CompactingRuntime.compact(root, upgrade(root, 1), helper.getLevel(), 1), 1, "Outer compaction can use an allowed child's real vanilla recipe");
        helper.assertValueEqual(count(child, Items.IRON_BLOCK), 1, "The result follows child-first insertion");
        helper.assertValueEqual(count(child, Items.IRON_INGOT), 0, "Exactly nine physical ingots were consumed");
        helper.assertTrue(root.getItem(0) == child.stack(), "Committing child contents preserves the actual carrier object");
        helper.assertValueEqual(count(savedChild(helper, root, 0), Items.IRON_BLOCK), 1, "The parent serializes the committed compressed item");

        child.updateSettings(upgrade(child, 0), tag -> tag.putString("filter_mode", "BLOCK"));
        child.setFilter(upgrade(child, 0), 0, ItemStack.EMPTY);
        child.setItem(1, new ItemStack(Items.DIAMOND_PICKAXE));
        var player = player(helper);
        player.getInventory().setItem(10, root.stack());
        player.getInventory().setItem(player.getInventory().selected, new ItemStack(Items.STICK, 3));
        helper.assertTrue(ToolRuntime.forBlock(root, player, Blocks.STONE.defaultBlockState(), false), "Outer tool selection reaches an owned child's pickaxe");
        helper.assertTrue(player.getMainHandItem().is(Items.DIAMOND_PICKAXE), "The physical tool is moved to the player's hand");
        helper.assertValueEqual(count(child, Items.DIAMOND_PICKAXE), 0, "The selected tool is not duplicated");
        helper.assertValueEqual(count(child, Items.STICK), 3, "The previous held stack returns through the same ordered storage view");
        helper.assertTrue(player.getInventory().getItem(10) == root.stack(), "The tool's player plan does not replace the active outer carrier");
        helper.assertValueEqual(count(savedChild(helper, root, 0), Items.STICK), 3, "Both tool mutations persist through the parent codec");
        child.setFilter(upgrade(child, 0), 0, new ItemStack(Items.STONE));
        child.setItem(2, new ItemStack(Items.STONE, 6));
        root.setItem(2, new ItemStack(Items.STONE, 7));
        helper.assertTrue(TransferRuntime.pickBlock(root, player, new ItemStack(Items.STONE)),
                "Advanced pick-block skips a filtered first child and reaches a later allowed identical source");
        helper.assertTrue(player.getMainHandItem().is(Items.STONE) && player.getMainHandItem().getCount() == 7,
                "Pick-block moves the permitted outer stack into the actual hand");
        helper.assertValueEqual(count(child, Items.STONE), 6, "The child's rejected matching stack remains untouched");
        helper.assertValueEqual(count(root, Items.STONE), 0, "The allowed outer source is consumed exactly once");
        helper.assertValueEqual(count(child, Items.DIAMOND_PICKAXE), 1, "The prior held pickaxe returns intact through ordered storage");
        helper.assertValueEqual(count(savedChild(helper, root, 0), Items.STONE), 6, "Pick-block preserves filtered child contents through the parent codec");
        helper.succeed();
    }

    public static void nestedTickSettings(GameTestHelper helper) {
        BagInventory root = root();
        BagInventory child = bag(BackpackTier.LEATHER, UpgradeKind.SMELTING);
        child.updateSettings(upgrade(child, 0), tag -> { tag.putInt("burn_remaining", 10); tag.putBoolean("burning", true); });
        attach(root, 0, child);
        BlockPos position = helper.absolutePos(new BlockPos(2, 1, 2));
        BackpackTraversal.tick(root, helper.getLevel(), position, null);
        BackpackTraversal.tick(root, helper.getLevel(), position, null);
        helper.assertValueEqual(NbtAccess.getIntOr(child.settings(upgrade(child, 0)), "burn_remaining", -1), 9, "Carried-style child dispatch runs once in a server tick");
        helper.assertValueEqual(NbtAccess.getIntOr(savedChild(helper, root, 0).settings(upgrade(savedChild(helper, root, 0), 0)), "burn_remaining", -1), 9,
                "Child progress is serialized into the parent after ticking");

        BagInventory disabled = root();
        BagInventory paused = bag(BackpackTier.LEATHER, UpgradeKind.SMELTING);
        paused.updateSettings(upgrade(paused, 0), tag -> { tag.putInt("burn_remaining", 10); tag.putBoolean("burning", true); });
        attach(disabled, 0, paused);
        disabled.updateSettings(tag -> tag.putBoolean("inception_inner_upgrades", false));
        BackpackTraversal.tick(disabled, helper.getLevel(), position, null);
        helper.assertValueEqual(NbtAccess.getIntOr(paused.settings(upgrade(paused, 0)), "burn_remaining", -1), 10, "Disabled child upgrades preserve remaining fuel");
        helper.assertFalse(NbtAccess.getBooleanOr(paused.settings(upgrade(paused, 0)), "burning", true), "Paused cooking does not remain visually active");
        helper.assertTrue(BackpackTraversal.usesChildren(disabled), "Pausing child upgrades leaves outer inventory access independent");

        BagInventory placed = root();
        BagInventory placedChild = bag(BackpackTier.LEATHER, UpgradeKind.SMELTING);
        placedChild.updateSettings(upgrade(placedChild, 0), tag -> tag.putInt("burn_remaining", 10));
        attach(placed, 0, placedChild);
        placed.updateSettings(tag -> tag.putBoolean("inception_outer_inventory", false));
        BlockPos relative = new BlockPos(2, 1, 2);
        helper.setBlock(relative, BackpackRegistry.block(BackpackTier.NETHERITE).defaultBlockState());
        BackpackBlockEntity entity = (BackpackBlockEntity) helper.getLevel().getBlockEntity(position);
        entity.setStack(placed.stack());
        BackpackBlockEntity.tick(helper.getLevel(), position, entity.getBlockState(), entity);
        BackpackBlockEntity.tick(helper.getLevel(), position, entity.getBlockState(), entity);
        BagInventory actualChild = BackpackTraversal.children(entity.inventory()).getFirst().inventory();
        helper.assertValueEqual(NbtAccess.getIntOr(actualChild.settings(upgrade(actualChild, 0)), "burn_remaining", -1), 9, "The actual placed block ticks its child once even with outer access off");
        helper.succeed();
    }

    public static void nestedResourceTransactions(GameTestHelper helper) {
        BagInventory root = root(UpgradeKind.TANK, UpgradeKind.BATTERY);
        BagInventory child = bag(BackpackTier.IRON, UpgradeKind.TANK, UpgradeKind.BATTERY);
        attach(root, 0, child);
        root.setItem(1, new ItemStack(Items.EMERALD, 4));
        Storage<FluidVariant> fluid = ResourceRuntime.fluidStorage(root);
        EnergyStorage energy = ResourceRuntime.energyStorage(root);
        Storage<ItemVariant> items = ResourceRuntime.itemStorage(root, null);
        try (Transaction outer = Transaction.openOuter()) {
            helper.assertValueEqual(fluid.insert(WATER, 17, outer), 17L, "An outer tank view reaches the first child's tank");
            try (Transaction nested = outer.openNested()) { energy.insert(37, nested); nested.commit(); }
            new BackpackItemStorage(root, null).insert(ItemVariant.of(Items.EMERALD), 9, outer);
            helper.assertValueEqual(tank(child, 0).getAmount(), 17L, "The leaf exposes the same transactional resource");
            helper.assertValueEqual(ResourceRuntime.batteryStored(child, 1), 37L, "Nested energy changes use the physical child upgrade");
        }
        helper.assertValueEqual(tank(child, 0).getAmount(), 0L, "Outer abort restores child fluid");
        helper.assertValueEqual(ResourceRuntime.batteryStored(child, 1), 0L, "Outer abort unwinds an already-committed nested energy mutation");
        helper.assertValueEqual(root.getItem(1).getCount(), 4, "An independent root item adapter rolls back in the same transaction");
        BagInventory rolledBack = savedChild(helper, root, 0);
        helper.assertValueEqual(tank(rolledBack, 0).getAmount(), 0L, "Parent serialization cannot retain a rolled-back child resource");
        helper.assertValueEqual(ResourceRuntime.batteryStored(rolledBack, 1), 0L, "Rolled-back child energy is absent from a real codec round trip");
        try (Transaction outer = Transaction.openOuter()) {
            fluid.insert(WATER, 12345, outer);
            energy.insert(75, outer);
            helper.assertValueEqual(items.insert(ItemVariant.of(Items.STONE), 70, outer), 70L, "Item API insertion uses the child's own free slots");
            outer.commit();
        }
        BagInventory saved = savedChild(helper, root, 0);
        helper.assertValueEqual(tank(saved, 0).getAmount(), 12345L, "Committed sub-millibucket fluid survives parent serialization");
        helper.assertValueEqual(ResourceRuntime.batteryStored(saved, 1), 75L, "Committed energy survives parent serialization");
        helper.assertValueEqual(count(saved, Items.STONE), 70, "Committed child item counts survive parent serialization");
        helper.assertValueEqual(child.getItem(0).getCount(), 64, "A small child's resource API retains its native stack limit");
        helper.assertValueEqual(tank(root, 1).getAmount(), 0L, "Children-first resource insertion leaves the outer tank untouched");
        try (Transaction outer = Transaction.openOuter()) {
            helper.assertValueEqual(items.extract(ItemVariant.of(child.stack()), 1, outer), 0L, "An active aggregate cannot extract its own child carrier");
        }
        var oldFluid = fluid.iterator().next();
        var oldItem = viewOf(items, ItemVariant.of(Items.STONE));
        BagInventory replacement = bag(BackpackTier.IRON, UpgradeKind.TANK, UpgradeKind.BATTERY);
        root.setItem(0, replacement.stack());
        try (Transaction outer = Transaction.openOuter()) {
            helper.assertValueEqual(oldFluid.extract(WATER, 1, outer), 0L, "A removed child's retained fluid view is inert");
            helper.assertValueEqual(oldItem.extract(ItemVariant.of(Items.STONE), 1, outer), 0L, "A removed child's retained item view is inert");
            outer.commit();
        }
        helper.assertValueEqual(tank(child, 0).getAmount(), 12345L, "Stale views cannot drain the detached physical child");
        helper.assertValueEqual(tank(replacement, 0).getAmount(), 0L, "Stale views cannot redirect to a replacement child");
        helper.assertValueEqual(oldFluid.getAmount(), 0L, "Stale capacity/amount queries are inert too");
        helper.succeed();
    }

    public static void nestedItemContextsAndConnections(GameTestHelper helper) {
        BagInventory root = root(UpgradeKind.TANK, UpgradeKind.BATTERY);
        BagInventory child = bag(BackpackTier.IRON, UpgradeKind.TANK, UpgradeKind.BATTERY);
        attach(root, 0, child);
        SimpleContainer holder = new SimpleContainer(root.stack());
        ContainerItemContext context = ContainerItemContext.ofSingleSlot(InventoryStorage.of(holder, null).getSlot(0));
        Storage<FluidVariant> fluid = context.find(FluidStorage.ITEM);
        EnergyStorage energy = context.find(EnergyStorage.ITEM);
        ItemStack before = holder.getItem(0).copy();
        helper.assertTrue(fluid != null && energy != null, "Real item lookups expose nested fluid and energy");
        try (Transaction outer = Transaction.openOuter()) {
            fluid.insert(WATER, 23, outer);
            energy.insert(39, outer);
        }
        assertStack(helper, holder.getItem(0), before, "Aborting item-context exchange restores every original parent component");
        try (Transaction outer = Transaction.openOuter()) {
            fluid.insert(WATER, 23, outer);
            energy.insert(39, outer);
            outer.commit();
        }
        BagInventory committed = BagInventory.of(holder.getItem(0));
        helper.assertValueEqual(tank(savedChild(helper, committed, 0), 0).getAmount(), 23L, "The actual holder item owns committed child fluid");
        helper.assertValueEqual(ResourceRuntime.batteryStored(savedChild(helper, committed, 0), 1), 39L, "The actual holder item owns committed child energy");
        var oldView = fluid.iterator().next();
        committed.setItem(0, bag(BackpackTier.IRON, UpgradeKind.TANK, UpgradeKind.BATTERY).stack());
        try (Transaction outer = Transaction.openOuter()) {
            helper.assertValueEqual(oldView.extract(WATER, 1, outer), 0L, "Retained item-context views reject a different child in the same physical slot");
        }

        BlockPos relative = new BlockPos(2, 1, 2);
        BlockPos position = helper.absolutePos(relative);
        helper.setBlock(relative, BackpackRegistry.block(BackpackTier.NETHERITE).defaultBlockState());
        BackpackBlockEntity entity = (BackpackBlockEntity) helper.getLevel().getBlockEntity(position);
        entity.setStack(holder.getItem(0));
        var exposedItems = ItemStorage.SIDED.find(helper.getLevel(), position, Direction.NORTH);
        var exposedFluid = FluidStorage.SIDED.find(helper.getLevel(), position, Direction.NORTH);
        var exposedEnergy = EnergyStorage.SIDED.find(helper.getLevel(), position, Direction.NORTH);
        helper.assertTrue(exposedItems != null && exposedFluid != null && exposedEnergy != null, "Placed lookup exposes all three adapters while permitted");
        var previous = BackpackConfig.get();
        try {
            BackpackConfig.configure(ConfigFile.decode("{\"storage\":{\"disableConnections\":true,\"itemFluidAccess\":false}}"));
            var deniedItems = ItemStorage.SIDED.find(helper.getLevel(), position, Direction.NORTH);
            try (Transaction outer = Transaction.openOuter()) {
                helper.assertTrue(deniedItems == null || deniedItems.insert(ItemVariant.of(Items.STONE), 1, outer) == 0,
                        "Disabled item connections cannot fall back to a generic container adapter");
            }
            helper.assertTrue(FluidStorage.SIDED.find(helper.getLevel(), position, Direction.NORTH) == null, "Disabled connections reject new fluid lookups");
            helper.assertTrue(EnergyStorage.SIDED.find(helper.getLevel(), position, Direction.NORTH) == null, "Disabled connections reject new energy lookups");
            helper.assertTrue(context.find(FluidStorage.ITEM) == null, "Disabled item fluid access rejects new lookups");
            try (Transaction outer = Transaction.openOuter()) {
                helper.assertValueEqual(exposedItems.insert(ItemVariant.of(Items.STONE), 1, outer), 0L, "Retained item adapters honor disabled connections");
                helper.assertValueEqual(exposedFluid.insert(WATER, 1, outer), 0L, "Retained fluid adapters honor disabled connections");
                helper.assertValueEqual(exposedEnergy.insert(1, outer), 0L, "Retained energy adapters honor disabled connections");
                helper.assertValueEqual(fluid.insert(WATER, 1, outer), 0L, "Retained item-fluid adapters honor the item access flag");
            }
            BackpackConfig.configure(ConfigFile.decode("{\"storage\":{\"blockedConnections\":[\"minecraft:stone\"]}}"));
            helper.setBlock(relative.relative(Direction.NORTH), Blocks.STONE.defaultBlockState());
            var blockedItems = ItemStorage.SIDED.find(helper.getLevel(), position, Direction.NORTH);
            try (Transaction outer = Transaction.openOuter()) {
                helper.assertTrue(blockedItems == null || blockedItems.insert(ItemVariant.of(Items.STONE), 1, outer) == 0,
                        "A configured neighboring block rejects sided access without generic fallback");
            }
            helper.assertTrue(FluidStorage.SIDED.find(helper.getLevel(), position, Direction.NORTH) == null, "Neighbor restrictions also cover fluid");
            helper.assertTrue(EnergyStorage.SIDED.find(helper.getLevel(), position, Direction.NORTH) == null, "Neighbor restrictions also cover energy");
            helper.assertTrue(ItemStorage.SIDED.find(helper.getLevel(), position, Direction.SOUTH) != null, "An unrelated side remains usable");
            BackpackConfig.configure(ConfigFile.decode("{\"storage\":{\"outerUsesChildren\":false,\"childUpgrades\":false}}"));
            helper.assertFalse(BackpackTraversal.usesChildren(root), "Server rules can prohibit aggregate storage");
            helper.assertFalse(BackpackTraversal.ticksChildren(root), "Server rules can independently prohibit child upgrades");
        } finally { BackpackConfig.configure(previous); }
        helper.succeed();
    }

    public static void nestedCookingAndFeeding(GameTestHelper helper) {
        BagInventory root = root(UpgradeKind.AUTO_SMELTING, UpgradeKind.FEEDING);
        BagInventory child = bag(BackpackTier.LEATHER);
        child.setItem(0, new ItemStack(Items.IRON_ORE, 3));
        child.setItem(1, new ItemStack(Items.COAL, 2));
        child.setItem(2, new ItemStack(Items.BREAD, 2));
        attach(root, 0, child);
        root.setFilter(upgrade(root, 1), 0, new ItemStack(Items.IRON_ORE));
        var player = player(helper);
        player.getFoodData().setFoodLevel(10);
        ConsumptionRuntime.feed(root, upgrade(root, 2), helper.getLevel(), player.blockPosition(), player);
        helper.assertValueEqual(count(child, Items.BREAD), 1, "The outer feeding upgrade consumes one child-owned food item");
        helper.assertValueEqual(player.getFoodData().getFoodLevel(), 15, "Native consumption applies the actual food nutrition");
        helper.assertValueEqual(count(savedChild(helper, root, 0), Items.BREAD), 1, "Food removal is persisted inside the parent");
        helper.onEachTick(() -> CookingRuntime.tick(root, upgrade(root, 1), helper.getLevel()));
        helper.runAfterDelay(11, () -> {
            Container furnace = root.upgradeInventory(upgrade(root, 1));
            helper.assertTrue(furnace.getItem(CookingRuntime.INPUT).is(Items.IRON_ORE), "Automatic cooking pulls a real ingredient from child storage");
            helper.assertValueEqual(furnace.getItem(CookingRuntime.INPUT).getCount(), 3, "The complete input count is transferred once");
            helper.assertValueEqual(count(child, Items.IRON_ORE), 0, "Pulled input is removed from the physical child");
            helper.assertValueEqual(count(child, Items.COAL) + furnace.getItem(CookingRuntime.FUEL).getCount(), 1, "Exactly one real fuel item starts burning");
            helper.assertTrue(root.getItem(0) == child.stack(), "Cooking does not replace the child carrier while committing its auxiliary inventory");
            helper.assertValueEqual(count(savedChild(helper, root, 0), Items.IRON_ORE), 0, "Automatic pull persists through the parent item codec");
            helper.succeed();
        });
    }

    private static HopperBlockEntity freshHopper(GameTestHelper helper, BlockPos relative, Direction facing, ItemStack input) {
        BlockPos absolute = helper.absolutePos(relative);
        if (helper.getLevel().getBlockEntity(absolute) instanceof HopperBlockEntity old) old.clearContent();
        helper.setBlock(relative, Blocks.AIR.defaultBlockState());
        helper.setBlock(relative, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, facing));
        HopperBlockEntity hopper = (HopperBlockEntity) helper.getLevel().getBlockEntity(absolute);
        hopper.setItem(0, input);
        return hopper;
    }

    private static void push(GameTestHelper helper, HopperBlockEntity hopper) {
        HopperBlockEntity.pushItemsTick(helper.getLevel(), hopper.getBlockPos(), hopper.getBlockState(), hopper);
    }

    public static void nativeHopperNestedRouting(GameTestHelper helper) {
        BagInventory root = root(UpgradeKind.FILTER, UpgradeKind.VOID);
        BagInventory child = bag(BackpackTier.LEATHER, UpgradeKind.FILTER);
        attach(root, 0, child);
        root.setFilter(upgrade(root, 1), 0, new ItemStack(Items.STONE));
        root.updateSettings(upgrade(root, 1), tag -> tag.putString("filter_mode", "ALLOW"));
        child.setFilter(upgrade(child, 0), 0, new ItemStack(Items.STONE));
        child.updateSettings(upgrade(child, 0), tag -> tag.putString("filter_mode", "ALLOW"));
        root.save();
        BlockPos position = new BlockPos(2, 1, 2);
        helper.setBlock(position, BackpackRegistry.block(BackpackTier.NETHERITE).defaultBlockState());
        BackpackBlockEntity entity = (BackpackBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(position));
        entity.setStack(root.stack());
        BagInventory actual = entity.inventory();
        BagInventory actualChild = BackpackTraversal.children(actual).getFirst().inventory();
        HopperBlockEntity hopper = freshHopper(helper, position.above(), Direction.DOWN, new ItemStack(Items.STONE));
        push(helper, hopper);
        helper.assertTrue(hopper.isEmpty(), "A real vanilla hopper transfers its actual source item");
        helper.assertValueEqual(count(actualChild, Items.STONE), 1, "Hopper insertion uses child-first processing through the Fabric adapter");
        helper.assertValueEqual(count(actual, Items.STONE), 0, "The hopper does not bypass nesting into a raw outer slot");
        hopper = freshHopper(helper, position.above(), Direction.DOWN, new ItemStack(Items.DIRT));
        push(helper, hopper);
        helper.assertValueEqual(count(hopper, Items.DIRT), 1, "An outer input filter rejects the real hopper's nonmatching item");
        helper.assertValueEqual(count(actualChild, Items.DIRT), 0, "Rejected input cannot leak into the child");
        actual.updateSettings(upgrade(actual, 1), tag -> tag.putBoolean("enabled", false));
        actual.setFilter(upgrade(actual, 2), 0, new ItemStack(Items.DIRT));
        actual.updateSettings(upgrade(actual, 2), tag -> tag.putString("void_mode", "ALWAYS"));
        hopper = freshHopper(helper, position.above(), Direction.DOWN, new ItemStack(Items.DIRT));
        push(helper, hopper);
        helper.assertTrue(hopper.isEmpty(), "Hopper admission honors an explicitly selected ALWAYS void filter");
        helper.assertValueEqual(count(actual, Items.DIRT) + count(actualChild, Items.DIRT), 0, "The intentionally discarded item is not retained or duplicated");

        BagInventory source = root();
        BagInventory sourceChild = bag(BackpackTier.LEATHER, UpgradeKind.FILTER);
        sourceChild.setItem(0, new ItemStack(Items.EMERALD, 7));
        attach(source, 0, sourceChild);
        sourceChild.updateSettings(upgrade(sourceChild, 0), tag -> { tag.putString("filter_direction", "OUTPUT"); tag.putString("filter_mode", "ALLOW"); });
        source.save();
        BlockPos sourcePosition = new BlockPos(5, 2, 2);
        helper.setBlock(sourcePosition, BackpackRegistry.block(BackpackTier.NETHERITE).defaultBlockState());
        BackpackBlockEntity from = (BackpackBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(sourcePosition));
        from.setStack(source.stack());
        BagInventory storedChild = BackpackTraversal.children(from.inventory()).getFirst().inventory();
        HopperBlockEntity receiving = freshHopper(helper, sourcePosition.below(), Direction.EAST, ItemStack.EMPTY);
        helper.assertFalse(HopperBlockEntity.suckInItems(helper.getLevel(), receiving), "A child output filter blocks actual vanilla hopper extraction");
        helper.assertTrue(receiving.isEmpty(), "The aggregate never extracts the child carrier itself");
        storedChild.updateSettings(upgrade(storedChild, 0), tag -> tag.putBoolean("enabled", false));
        helper.assertTrue(HopperBlockEntity.suckInItems(helper.getLevel(), receiving), "The actual hopper can extract after the child's output filter permits it");
        helper.assertValueEqual(count(receiving, Items.EMERALD), 1, "One physical emerald reaches the hopper");
        helper.assertValueEqual(count(storedChild, Items.EMERALD), 6, "Exactly one emerald leaves the physical child");
        helper.assertValueEqual(count(savedChild(helper, from.inventory(), 0), Items.EMERALD), 6, "Hopper extraction persists through the parent codec");
        helper.succeed();
    }

    public static void nativeHopperConnectionRules(GameTestHelper helper) {
        BlockPos position = new BlockPos(2, 1, 2);
        helper.setBlock(position, BackpackRegistry.block(BackpackTier.LEATHER).defaultBlockState());
        BackpackBlockEntity entity = (BackpackBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(position));
        HopperBlockEntity hopper = freshHopper(helper, position.above(), Direction.DOWN, new ItemStack(Items.STONE));
        var previous = BackpackConfig.get();
        try {
            BackpackConfig.configure(ConfigFile.decode("{\"storage\":{\"disableConnections\":true}}"));
            push(helper, hopper);
            helper.assertValueEqual(count(hopper, Items.STONE), 1, "Disabled connections retain the real hopper's source item");
            helper.assertTrue(entity.inventory().isEmpty(), "Vanilla container routing cannot bypass disabled connections");
            helper.assertValueEqual(entity.getSlotsForFace(Direction.UP).length, 0, "The block also denies raw WorldlyContainer access");
            BackpackConfig.configure(ConfigFile.decode("{\"storage\":{\"blockedConnections\":[\"minecraft:hopper\"]}}"));
            hopper = freshHopper(helper, position.above(), Direction.DOWN, new ItemStack(Items.STONE));
            push(helper, hopper);
            helper.assertValueEqual(count(hopper, Items.STONE), 1, "A blocked neighboring hopper cannot send into the backpack");
            helper.assertFalse(entity.canPlaceItemThroughFace(0, new ItemStack(Items.STONE), Direction.UP), "The physical source side is denied");
            helper.assertTrue(entity.canPlaceItemThroughFace(0, new ItemStack(Items.STONE), Direction.SOUTH), "An unrelated side remains available");
            BackpackConfig.configure(ConfigFile.decode("{\"storage\":{\"blockedConnections\":[\"minecraft:chest\"]}}"));
            hopper = freshHopper(helper, position.above(), Direction.DOWN, new ItemStack(Items.STONE));
            push(helper, hopper);
            helper.assertTrue(hopper.isEmpty(), "A nonmatching connection restriction does not disable the hopper");
            helper.assertValueEqual(count(entity.inventory(), Items.STONE), 1, "Allowed insertion stores exactly one real item");
        } finally { BackpackConfig.configure(previous); }
        helper.succeed();
    }
}
