package com.kadamitas.fabricatedbackpacks.gametest;

import com.kadamitas.fabricatedbackpacks.compat.NbtAccess;
import com.kadamitas.fabricatedbackpacks.automation.AutomationRegistry;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitBundleBlockEntity;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitKind;
import com.kadamitas.fabricatedbackpacks.block.BackpackBlockEntity;
import com.kadamitas.fabricatedbackpacks.config.BackpackConfig;
import com.kadamitas.fabricatedbackpacks.config.ConfigFile;
import com.kadamitas.fabricatedbackpacks.equipment.BackpackEquipment;
import com.kadamitas.fabricatedbackpacks.menu.BackpackMenu;
import com.kadamitas.fabricatedbackpacks.menu.BackpackMenus;
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
import com.kadamitas.fabricatedbackpacks.storage.BagComponents;
import com.kadamitas.fabricatedbackpacks.storage.InstalledUpgrade;
import com.kadamitas.fabricatedbackpacks.upgrade.UpgradeEngine;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.transfer.v1.context.ContainerItemContext;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.storage.StoragePreconditions;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageUtil;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import team.reborn.energy.api.EnergyStorage;
import team.reborn.energy.api.base.SimpleEnergyItem;
import team.reborn.energy.api.base.SimpleEnergyStorage;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;

/** Actual Fabric and Energy API fixtures live only in the GameTest mod, never in the release jar. */
public final class ResourceGameTests {
    private static final FluidVariant WATER = FluidVariant.of(Fluids.WATER);
    private static final Set<BlockPos> PROTECTED_POSITIONS = new HashSet<>();
    private static Item energyCell;
    private static Block energyReceiver;
    private record Endpoint(Level level, BlockPos position) {}
    private static final Map<Endpoint, Receiver> RECEIVERS = new HashMap<>();

    private ResourceGameTests() {}

    public static void registerFixtures() {
        if (energyCell != null) return;
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("fabricated_backpacks_tests", "energy_cell");
        // The test resource pack supplies the registered fixture item model; no fixture art enters the release jar.
        energyCell = Registry.register(BuiltInRegistries.ITEM, id,
                new TestEnergyCell(new Item.Properties().stacksTo(1)));
        ResourceLocation receiverId = ResourceLocation.fromNamespaceAndPath("fabricated_backpacks_tests", "energy_receiver");
        energyReceiver = Registry.register(BuiltInRegistries.BLOCK, receiverId,
                new Block(Block.Properties.of()) {
                    @Override protected void neighborChanged(BlockState state, Level level, BlockPos position,
                                                              Block source, BlockPos sourcePosition, boolean moved) {
                        Receiver receiver = RECEIVERS.get(new Endpoint(level, position));
                        if (receiver != null) receiver.neighborUpdates++;
                    }
                });
        EnergyStorage.SIDED.registerForBlocks((level, position, state, entity, direction) -> {
            Receiver receiver = RECEIVERS.get(new Endpoint(level, position));
            if (receiver == null) return null;
            receiver.queried.add(direction);
            return direction == receiver.face ? receiver.storage : null;
        }, energyReceiver);
        PlayerBlockBreakEvents.BEFORE.register((level, player, position, state, entity) -> !PROTECTED_POSITIONS.contains(position));
    }

    private static final class TestEnergyCell extends Item implements SimpleEnergyItem {
        TestEnergyCell(Properties properties) { super(properties); }
        @Override public long getEnergyCapacity(ItemStack stack) { return 1_000; }
        @Override public long getEnergyMaxInput(ItemStack stack) { return 100; }
        @Override public long getEnergyMaxOutput(ItemStack stack) { return 100; }
    }

    private static final class Receiver {
        final Direction face;
        final SimpleEnergyStorage storage;
        final Set<Direction> queried = new HashSet<>();
        int neighborUpdates;
        Receiver(Direction face, long rate) { this.face = face; storage = new SimpleEnergyStorage(1_000_000, rate, 0); }
    }

    private static BackpackBlockEntity place(GameTestHelper helper, BlockPos position, BagInventory bag) {
        helper.getLevel().setBlock(position, BackpackRegistry.block(bag.tier()).defaultBlockState(), 3);
        BackpackBlockEntity entity = (BackpackBlockEntity) helper.getLevel().getBlockEntity(position);
        entity.setStack(bag.stack());
        return entity;
    }

    private static void tickPlaced(GameTestHelper helper, BackpackBlockEntity entity) {
        BackpackBlockEntity.tick(helper.getLevel(), entity.getBlockPos(), entity.getBlockState(), entity);
    }

    private static List<Receiver> receivers(GameTestHelper helper, BlockPos source, long rate) {
        List<Receiver> receivers = new ArrayList<>();
        for (Direction side : Direction.values()) {
            BlockPos target = source.relative(side);
            helper.getLevel().setBlock(target, energyReceiver.defaultBlockState(), 3);
            Receiver receiver = new Receiver(side.getOpposite(), rate);
            RECEIVERS.put(new Endpoint(helper.getLevel(), target), receiver);
            receivers.add(receiver);
        }
        return receivers;
    }

    private static void removeReceivers(GameTestHelper helper, BlockPos source) {
        for (Direction side : Direction.values()) RECEIVERS.remove(new Endpoint(helper.getLevel(), source.relative(side)));
    }

    private static BagInventory bag(UpgradeKind... upgrades) {
        return BackpackTestSupport.bag(BackpackTier.NETHERITE, upgrades);
    }
    private static InstalledUpgrade upgrade(BagInventory bag, int slot) { return BackpackTestSupport.upgrade(bag, slot); }
    private static BackpackTank tank(BagInventory bag) { return new BackpackTank(bag, upgrade(bag, 0), false); }
    private static ContainerItemContext context(Container container, int slot) {
        return ContainerItemContext.ofSingleSlot(InventoryStorage.of(container, null).getSlot(slot));
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
    private static void assertExactItem(GameTestHelper helper, ItemStack actual, ItemStack expected, String message) {
        if (ItemStack.matches(actual, expected)) return;
        var ops = net.minecraft.resources.RegistryOps.create(net.minecraft.nbt.NbtOps.INSTANCE, helper.getLevel().registryAccess());
        helper.assertTrue(false, message + "; expected components=" + ItemStack.CODEC.encodeStart(ops, expected).getOrThrow()
                + "; actual components=" + ItemStack.CODEC.encodeStart(ops, actual).getOrThrow());
    }
    private static void resetPump(BagInventory bag) {
        bag.updateSettings(upgrade(bag, 1), settings -> settings.putLong("next_work", 0));
    }

    public static void tankTransactions(GameTestHelper helper) {
        BagInventory bag = bag(UpgradeKind.TANK);
        BackpackTank first = tank(bag);
        BackpackTank second = tank(bag);
        ItemStack initial = bag.stack().copy();
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
        assertExactItem(helper, bag.stack(), initial, "Tank rollback preserves absent resource settings, not just a zero quantity");
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

    public static void sharedItemAndEquipmentApis(GameTestHelper helper) {
        resourceSnapshotFields(helper);
        pristineItemViews(helper);
        nestedItemViews(helper);
        warmContextAndMenu(helper);
        equippedTransactions(helper);
        helper.succeed();
    }

    private static void resourceSnapshotFields(GameTestHelper helper) {
        BagInventory bag = bag(UpgradeKind.TANK, UpgradeKind.BATTERY);
        InstalledUpgrade tankUpgrade = upgrade(bag, 0);
        InstalledUpgrade batteryUpgrade = upgrade(bag, 1);
        BackpackTank tank = new BackpackTank(bag, tankUpgrade, false);
        BackpackBattery battery = new BackpackBattery(bag, batteryUpgrade);
        // Explicit empty fluid/components and numeric tag types are part of the snapshot too.
        tankUpgrade.stack().set(ResourceComponents.TANK_FLUID, FluidVariant.blank());
        bag.updateSettings(tankUpgrade, state -> { state.putInt("amount", 0); state.putByte("amount_droplets", (byte) 0); });
        bag.updateSettings(batteryUpgrade, state -> state.putInt("amount", 0));
        ItemStack before = bag.stack().copy();
        try (Transaction outer = Transaction.openOuter()) {
            tank.insert(WATER, 31, outer);
            battery.insert(47, outer);
        }
        assertExactItem(helper, bag.stack(), before, "Resource rollback preserves explicit empty components and original numeric tag types");
        // A preference edited independently is not a field owned by either resource adapter.
        try (Transaction outer = Transaction.openOuter()) {
            tank.insert(WATER, 31, outer);
            battery.insert(47, outer);
            bag.updateSettings(tankUpgrade, state -> state.putBoolean("separate_preference", true));
            bag.updateSettings(batteryUpgrade, state -> state.putBoolean("external_output", false));
        }
        BagInventory expected = BagInventory.of(before.copy());
        expected.updateSettings(upgrade(expected, 0), state -> state.putBoolean("separate_preference", true));
        expected.updateSettings(upgrade(expected, 1), state -> state.putBoolean("external_output", false));
        assertExactItem(helper, bag.stack(), expected.stack(), "Resource rollback restores only its owned fields and leaves unrelated preferences intact");
    }

    private static void pristineItemViews(GameTestHelper helper) {
        ItemStack pristine = bag(UpgradeKind.TANK, UpgradeKind.BATTERY).stack().copy();
        pristine.remove(BagComponents.IDENTITY);
        SimpleContainer holder = new SimpleContainer(pristine);
        ContainerItemContext context = context(holder, 0);
        Storage<ItemVariant> items = context.find(ItemStorage.ITEM);
        Storage<FluidVariant> fluid = context.find(FluidStorage.ITEM);
        EnergyStorage energy = context.find(EnergyStorage.ITEM);
        helper.assertTrue(items != null && fluid != null && energy != null, "All three standard item lookups are registered before the first write");
        StorageView<ItemVariant> cell = items.iterator().next();
        StorageView<FluidVariant> tank = fluid.iterator().next();
        ItemStack before = holder.getItem(0).copy();
        for (boolean commit : new boolean[]{false, true}) {
            try (Transaction outer = Transaction.openOuter()) {
                helper.assertValueEqual(items.insert(ItemVariant.of(Items.DIAMOND), 3, outer), 3L, "A pristine item accepts content through ItemStorage.ITEM");
                try (Transaction nested = outer.openNested()) {
                    helper.assertValueEqual(fluid.insert(WATER, 17, nested), 17L, "Other cached lookups follow this bag's first identity initialization");
                    helper.assertValueEqual(energy.insert(29, nested), 29L, "Energy shares the transactional context binding");
                    nested.commit();
                }
                helper.assertValueEqual(cell.getAmount(), 3L, "The retained physical item view survives initialization inside the transaction");
                helper.assertValueEqual(tank.getAmount(), 17L, "The retained fluid view survives initialization inside the transaction");
                if (commit) outer.commit();
            }
            if (!commit) {
                BackpackTestSupport.assertStack(helper, holder.getItem(0), before, "Abort restores the pristine item, including absence of its generated identity");
                helper.assertValueEqual(cell.getAmount(), 0L, "Aborted initialization restores the retained item view");
                helper.assertValueEqual(tank.getAmount(), 0L, "Aborted initialization restores the retained fluid view");
            }
        }
        try (Transaction outer = Transaction.openOuter()) {
            helper.assertValueEqual(cell.extract(ItemVariant.of(Items.DIAMOND), 1, outer), 1L, "A retained pristine view remains usable after commit");
            helper.assertValueEqual(tank.extract(WATER, 1, outer), 1L, "A retained pristine tank remains usable after commit");
            outer.commit();
        }
        BagInventory saved = BagInventory.of(BackpackTestSupport.roundTrip(helper.getLevel(), holder.getItem(0)));
        helper.assertValueEqual(BackpackTestSupport.count(saved, Items.DIAMOND), 2, "Item-context extraction persists through the real codec");
        helper.assertValueEqual(tank(saved).getAmount(), 16L, "Droplet counts persist alongside item extraction");
        helper.assertValueEqual(ResourceRuntime.batteryStored(saved, 1), 29L, "Unrelated energy stays exact");
        ItemStack replacement = bag(UpgradeKind.TANK, UpgradeKind.BATTERY).stack();
        holder.setItem(0, replacement);
        try (Transaction outer = Transaction.openOuter()) {
            helper.assertValueEqual(items.insert(ItemVariant.of(Items.DIRT), 1, outer), 0L, "A cached item lookup cannot address another backpack in the same context");
            helper.assertValueEqual(fluid.insert(WATER, 1, outer), 0L, "A cached fluid lookup cannot address another backpack");
            helper.assertValueEqual(energy.insert(1, outer), 0L, "A cached energy lookup cannot address another backpack");
            helper.assertValueEqual(cell.extract(ItemVariant.of(Items.DIAMOND), 1, outer), 0L, "A retained cell rejects replacement ownership");
            outer.commit();
        }
        helper.assertTrue(!energy.supportsInsertion() && !energy.supportsExtraction(), "Stale energy support flags are inert too");
        BackpackTestSupport.assertStack(helper, holder.getItem(0), replacement, "Rejected stale calls cannot rewrite the replacement's components");
        try (Transaction outer = Transaction.openOuter()) {
            helper.assertValueEqual(context.find(ItemStorage.ITEM).insert(ItemVariant.of(Items.DIRT), 1, outer), 1L,
                    "A fresh lookup on the same context can address its newly placed backpack");
            outer.commit();
        }
        ItemStack constantItem = bag(UpgradeKind.TANK, UpgradeKind.BATTERY).stack().copy();
        constantItem.remove(BagComponents.IDENTITY);
        ItemStack constantBefore = constantItem.copy();
        ContainerItemContext constant = ContainerItemContext.withConstant(constantItem);
        Storage<ItemVariant> constantItems = constant.find(ItemStorage.ITEM);
        Storage<FluidVariant> constantFluids = constant.find(FluidStorage.ITEM);
        EnergyStorage constantEnergy = constant.find(EnergyStorage.ITEM);
        // Fabric 8.0.12 deliberately reports successful extraction/overflow without changing its
        // fixed main variant. This is a virtual exchange, not a writable reference to constantItem.
        for (boolean commit : new boolean[]{false, true}) {
            try (Transaction outer = Transaction.openOuter()) {
                helper.assertValueEqual(constantItems.insert(ItemVariant.of(Items.DIRT), 1, outer), 1L, "A constant context permits virtual item replacement");
                helper.assertValueEqual(constantFluids.insert(WATER, 1, outer), 1L, "A cached constant fluid probe remains reusable after a virtual exchange");
                helper.assertValueEqual(constantEnergy.insert(1, outer), 1L, "A cached constant energy probe does not adopt an unpersisted identity");
                if (commit) outer.commit();
            }
            BackpackTestSupport.assertStack(helper, constantItem, constantBefore, "Neither abort nor commit of a constant context mutates the supplied item reference");
            helper.assertTrue(constant.getItemVariant().equals(ItemVariant.of(constantBefore)), "A constant context keeps its exact initial variant, including absence of identity");
            helper.assertValueEqual(constant.getMainSlot().getAmount(), 1L, "Virtual exchanges never consume the constant context item");
            helper.assertValueEqual(constantItems.iterator().next().getAmount(), 0L, "Virtual insertion creates no stored item contents");
            helper.assertValueEqual(amount(constantFluids), 0L, "Virtual fluid insertion creates no stored fluid");
            helper.assertValueEqual(constantEnergy.getAmount(), 0L, "Virtual energy insertion creates no stored energy");
        }
        readOnlyContext(helper);
    }

    private static void readOnlyContext(GameTestHelper helper) {
        BagInventory protectedBag = bag(UpgradeKind.TANK, UpgradeKind.BATTERY);
        protectedBag.setItem(0, new ItemStack(Items.DIAMOND, 2));
        fill(tank(protectedBag), WATER, 17);
        protectedBag.updateSettings(upgrade(protectedBag, 1), state -> state.putLong("amount", 23));
        ItemStack before = protectedBag.stack().copy();
        ItemVariant fixed = ItemVariant.of(before);
        // A normal public SingleSlotStorage whose owning machine disallows both replacement
        // operations. Unlike withConstant, its failed extraction/overflow cannot simulate success.
        ContainerItemContext context = ContainerItemContext.ofSingleSlot(new SingleSlotStorage<ItemVariant>() {
            @Override public boolean supportsInsertion() { return false; }
            @Override public boolean supportsExtraction() { return false; }
            @Override public boolean isResourceBlank() { return false; }
            @Override public ItemVariant getResource() { return fixed; }
            @Override public long getAmount() { return 1; }
            @Override public long getCapacity() { return 1; }
            @Override public long insert(ItemVariant item, long maximum, TransactionContext transaction) {
                StoragePreconditions.notBlankNotNegative(item, maximum);
                return 0;
            }
            @Override public long extract(ItemVariant item, long maximum, TransactionContext transaction) {
                StoragePreconditions.notBlankNotNegative(item, maximum);
                return 0;
            }
        });
        Storage<ItemVariant> items = context.find(ItemStorage.ITEM);
        Storage<FluidVariant> fluids = context.find(FluidStorage.ITEM);
        EnergyStorage energy = context.find(EnergyStorage.ITEM);
        try (Transaction outer = Transaction.openOuter()) {
            helper.assertValueEqual(items.insert(ItemVariant.of(Items.DIRT), 1, outer), 0L, "A read-only slot rejects item content replacement");
            helper.assertValueEqual(items.extract(ItemVariant.of(Items.DIAMOND), 1, outer), 0L, "A read-only slot cannot debit its owned items");
            try (Transaction nested = outer.openNested()) {
                helper.assertValueEqual(fluids.insert(WATER, 1, nested), 0L, "Read-only replacement rejects fluid insertion");
                helper.assertValueEqual(fluids.extract(WATER, 1, nested), 0L, "Read-only replacement rejects fluid extraction");
                helper.assertValueEqual(energy.insert(1, nested), 0L, "Read-only replacement rejects energy insertion");
                helper.assertValueEqual(energy.extract(1, nested), 0L, "Read-only replacement rejects energy extraction");
                nested.commit();
            }
            outer.commit();
        }
        SimpleContainer source = new SimpleContainer(new ItemStack(Items.DIRT, 4));
        try (Transaction outer = Transaction.openOuter()) {
            helper.assertValueEqual(StorageUtil.move(InventoryStorage.of(source, null), items, item -> true, 4, outer), 0L,
                    "The actual Fabric transfer helper rolls back a refused destination exchange");
            outer.commit();
        }
        BackpackTestSupport.assertStack(helper, source.getItem(0), Items.DIRT, 4, "Failed destination replacement leaves the external source exact");
        BackpackTestSupport.assertStack(helper, protectedBag.stack(), before, "Rejected nested resource operations leave the original backpack exact");
        helper.assertTrue(context.getItemVariant().equals(fixed), "Read-only context preserves every original component");
        helper.assertValueEqual(items.iterator().next().getAmount(), 2L, "Failed replacement rolls back tentative item debits");
        helper.assertValueEqual(amount(fluids), 17L, "Failed replacement rolls back tentative fluid changes");
        helper.assertValueEqual(energy.getAmount(), 23L, "Failed replacement rolls back tentative energy changes");
    }

    private static void nestedItemViews(GameTestHelper helper) {
        BagInventory child = bag(UpgradeKind.TANK, UpgradeKind.BATTERY, UpgradeKind.FILTER);
        child.setItem(0, new ItemStack(Items.EMERALD, 2));
        child.setFilter(upgrade(child, 2), 0, new ItemStack(Items.EMERALD));
        child.updateSettings(upgrade(child, 2), state -> { state.putString("filter_mode", "ALLOW"); state.putString("filter_direction", "BOTH"); });
        ItemStack childSeed = child.stack().copy();
        childSeed.remove(BagComponents.IDENTITY);
        BagInventory root = bag(UpgradeKind.INCEPTION, UpgradeKind.FILTER);
        root.setItem(0, childSeed);
        root.setFilter(upgrade(root, 1), 0, new ItemStack(Items.EMERALD));
        root.updateSettings(upgrade(root, 1), state -> { state.putString("filter_mode", "ALLOW"); state.putString("filter_direction", "BOTH"); });
        SimpleContainer holder = new SimpleContainer(root.stack());
        ContainerItemContext context = context(holder, 0);
        Storage<ItemVariant> items = context.find(ItemStorage.ITEM);
        Storage<FluidVariant> fluid = context.find(FluidStorage.ITEM);
        EnergyStorage energy = context.find(EnergyStorage.ITEM);
        StorageView<ItemVariant> cell = items.iterator().next();
        StorageView<FluidVariant> tank = fluid.iterator().next();
        helper.assertTrue(cell.getResource().getItem() == Items.EMERALD, "The ordered item API starts with the actual nested child's cell");
        ItemStack before = holder.getItem(0).copy();
        for (boolean commit : new boolean[]{false, true}) {
            try (Transaction outer = Transaction.openOuter()) {
                helper.assertValueEqual(items.insert(ItemVariant.of(Items.DIRT), 1, outer), 0L, "Root and child input filters apply to item-context access");
                helper.assertValueEqual(items.insert(ItemVariant.of(Items.EMERALD), 3, outer), 3L, "Permitted insertion reaches the ordered child inventory");
                helper.assertValueEqual(fluid.insert(WATER, 19, outer), 19L, "Nested tanks transact through the ordinary item context");
                helper.assertValueEqual(energy.insert(23, outer), 23L, "Nested batteries transact through the ordinary item context");
                helper.assertValueEqual(cell.getAmount(), 5L, "A retained child path adopts only its own initialized identity");
                helper.assertValueEqual(tank.getAmount(), 19L, "A retained tank path follows that same initialized child");
                if (commit) outer.commit();
            }
            if (!commit) BackpackTestSupport.assertStack(helper, holder.getItem(0), before, "Nested rollback restores parent, child, and uninitialized child identity together");
        }
        BagInventory saved = BagInventory.of(BackpackTestSupport.roundTrip(helper.getLevel(), holder.getItem(0)));
        BagInventory savedChild = BagInventory.of(saved.getItem(0));
        helper.assertValueEqual(BackpackTestSupport.count(savedChild, Items.EMERALD), 5, "Nested item quantities survive the enclosing parent codec");
        helper.assertValueEqual(tank(savedChild).getAmount(), 19L, "Nested tank quantities survive the parent codec");
        helper.assertValueEqual(ResourceRuntime.batteryStored(savedChild, 1), 23L, "Nested energy survives the parent codec");
        BagInventory live = BagInventory.of(holder.getItem(0));
        live.setItem(0, bag(UpgradeKind.TANK, UpgradeKind.BATTERY).stack());
        try (Transaction outer = Transaction.openOuter()) {
            helper.assertValueEqual(cell.extract(ItemVariant.of(Items.EMERALD), 1, outer), 0L, "A retained nested item view cannot adopt a different child in its old slot");
            helper.assertValueEqual(tank.extract(WATER, 1, outer), 0L, "A retained nested tank cannot adopt that replacement child");
        }
    }

    private static void warmContextAndMenu(GameTestHelper helper) {
        ServerPlayer player = BackpackTestSupport.player(helper);
        BagInventory warm = bag(UpgradeKind.TANK, UpgradeKind.BATTERY);
        Container tankSlots = warm.upgradeInventory(upgrade(warm, 0));
        tankSlots.setItem(2, new ItemStack(Items.BUCKET, 3));
        Container batterySlots = warm.upgradeInventory(upgrade(warm, 1));
        player.getInventory().setItem(0, warm.stack());
        BackpackMenus.openInventory(player, 0);
        BackpackMenu menu = (BackpackMenu) player.containerMenu;
        menu.clickMenuButton(player, 1000);
        try {
            ContainerItemContext context = ContainerItemContext.ofPlayerHand(player, InteractionHand.MAIN_HAND);
            Storage<ItemVariant> items = context.find(ItemStorage.ITEM);
            Storage<FluidVariant> fluid = context.find(FluidStorage.ITEM);
            EnergyStorage energy = context.find(EnergyStorage.ITEM);
            helper.assertTrue(menu.bag() == warm, "The opened menu retains the warm physical backpack handle");
            ItemStack before = warm.stack().copy();
            try (Transaction outer = Transaction.openOuter()) {
                items.insert(ItemVariant.of(Items.DIAMOND), 7, outer);
                fluid.insert(WATER, 73, outer);
                energy.insert(91, outer);
            }
            BackpackTestSupport.assertStack(helper, warm.stack(), before, "Aborted real hand-context transfers preserve a warm menu and auxiliary contents");
            try (Transaction outer = Transaction.openOuter()) {
                helper.assertValueEqual(items.insert(ItemVariant.of(Items.DIAMOND), 7, outer), 7L, "The actual held-item context commits inventory resources");
                helper.assertValueEqual(fluid.insert(WATER, 73, outer), 73L, "The same hand context commits fluid");
                helper.assertValueEqual(energy.insert(91, outer), 91L, "The same hand context commits energy");
                outer.commit();
            }
            helper.assertTrue(player.getMainHandItem() == warm.stack() && BagInventory.of(player.getMainHandItem()) == warm,
                    "Fabric's same-item final commit preserves the original physical handle");
            helper.assertValueEqual(BackpackTestSupport.count(warm, Items.DIAMOND), 7, "The warm handle reads committed item contents, not its old cache");
            helper.assertValueEqual(tank(warm).getAmount(), 73L, "The warm upgrade cache reads committed fluid");
            helper.assertValueEqual(ResourceRuntime.batteryStored(warm, 1), 91L, "The warm upgrade cache reads committed energy");
            helper.assertTrue(menu.stillValid(player), "An API exchange of the same physical bag does not invalidate its open menu");
            BackpackTestSupport.assertStack(helper, menu.getSlot(menu.auxiliaryStart() + 2).getItem(), Items.BUCKET, 3, "The selected menu's tank output survives resource exchange");
            tankSlots.setItem(3, new ItemStack(Items.WATER_BUCKET));
            ItemStack cell = new ItemStack(energyCell);
            cell.set(EnergyStorage.ENERGY_COMPONENT, 1_000L);
            batterySlots.setItem(1, cell);
            // The full output cell cannot consume the battery's separately asserted 91 units.
            ResourceRuntime.tick(warm, helper.getLevel(), player.blockPosition(), player);
            warm.save();
            BagInventory saved = BagInventory.of(BackpackTestSupport.roundTrip(helper.getLevel(), player.getMainHandItem()));
            helper.assertValueEqual(BackpackTestSupport.count(saved, Items.DIAMOND), 7, "A later runtime tick/save cannot erase the API's item commit");
            helper.assertValueEqual(tank(saved).getAmount(), 73L, "A later tick/save cannot erase the API's fluid commit");
            helper.assertValueEqual(ResourceRuntime.batteryStored(saved, 1), 91L, "A later tick/save cannot erase the API's energy commit");
            BackpackTestSupport.assertStack(helper, saved.upgradeInventory(upgrade(saved, 0)).getItem(3), Items.WATER_BUCKET, 1,
                    "A retained pre-exchange tank inventory still writes to the current upgrade");
            BackpackTestSupport.assertStack(helper, saved.upgradeInventory(upgrade(saved, 1)).getItem(1), cell,
                    "A retained pre-exchange battery inventory still writes to the current upgrade");
        } finally { player.closeContainer(); }
        player.containerMenu.setCarried(warm.stack().copy());
        ContainerItemContext cursor = ContainerItemContext.ofPlayerCursor(player, player.containerMenu);
        try (Transaction outer = Transaction.openOuter()) {
            helper.assertValueEqual(cursor.find(ItemStorage.ITEM).extract(ItemVariant.of(Items.DIAMOND), 2, outer), 2L, "The actual menu cursor is also a writable item context");
            outer.commit();
        }
        helper.assertValueEqual(BackpackTestSupport.count(BagInventory.of(player.containerMenu.getCarried()), Items.DIAMOND), 5, "Cursor mutation targets its own copy only");
        helper.assertValueEqual(BackpackTestSupport.count(warm, Items.DIAMOND), 7, "Cursor and held copies cannot alias their physical contents");
        player.containerMenu.setCarried(ItemStack.EMPTY);
    }

    private static void equippedTransactions(GameTestHelper helper) {
        ServerPlayer player = BackpackTestSupport.player(helper);
        ItemStack armor = new ItemStack(Items.DIAMOND_CHESTPLATE);
        player.setItemSlot(EquipmentSlot.CHEST, armor);
        BackpackEquipment.set(player, bag(UpgradeKind.TANK, UpgradeKind.BATTERY).stack());
        BagInventory warm = BackpackEquipment.inventory(player).orElseThrow();
        ContainerItemContext context = ResourceRuntime.equippedContext(player);
        Storage<ItemVariant> items = context.find(ItemStorage.ITEM);
        Storage<FluidVariant> fluid = context.find(FluidStorage.ITEM);
        EnergyStorage energy = context.find(EnergyStorage.ITEM);
        ItemStack before = BackpackEquipment.get(player).copy();
        for (boolean commit : new boolean[]{false, true}) {
            try (Transaction outer = Transaction.openOuter()) {
                helper.assertValueEqual(context.getMainSlot().extract(context.getItemVariant(), 1, outer), 0L, "The explicit equipment context does not expose the physical backpack as an extractable item");
                helper.assertValueEqual(items.insert(ItemVariant.of(Items.IRON_INGOT), 9, outer), 9L, "Equipment item access writes canonical owned slots");
                try (Transaction nested = outer.openNested()) {
                    helper.assertValueEqual(fluid.insert(WATER, 31, nested), 31L, "Equipment fluid participates in the same transaction");
                    helper.assertValueEqual(energy.insert(47, nested), 47L, "Equipment energy participates in the same transaction");
                    nested.commit();
                }
                if (commit) outer.commit();
            }
            if (!commit) {
                assertExactItem(helper, BackpackEquipment.get(player), before, "Outer abort restores equipped attachment components after nested resource commits");
                assertExactItem(helper, warm.stack(), before, "Outer abort also restores the retained canonical handle");
            }
        }
        helper.assertTrue(BackpackEquipment.inventory(player).orElseThrow() == warm, "Successful resource publication retains canonical equipment inventory identity");
        BackpackTestSupport.assertStack(helper, player.getItemBySlot(EquipmentSlot.CHEST), armor, "Native armor remains separate from the equipped resource context");
        BagInventory saved = BagInventory.of(BackpackTestSupport.roundTrip(helper.getLevel(), BackpackEquipment.get(player)));
        helper.assertValueEqual(BackpackTestSupport.count(saved, Items.IRON_INGOT), 9, "Equipped item resources persist in the attachment codec");
        helper.assertValueEqual(tank(saved).getAmount(), 31L, "Equipped fluid persists in the attachment codec");
        helper.assertValueEqual(ResourceRuntime.batteryStored(saved, 1), 47L, "Equipped energy persists in the attachment codec");
        player.setGameMode(GameType.SPECTATOR);
        try (Transaction outer = Transaction.openOuter()) {
            helper.assertValueEqual(items.insert(ItemVariant.of(Items.DIRT), 1, outer), 0L, "Spectators have no equipped item mutation capability");
            helper.assertValueEqual(fluid.extract(WATER, 1, outer), 0L, "Spectators have no equipped fluid mutation capability");
            helper.assertValueEqual(energy.extract(1, outer), 0L, "Spectators have no equipped energy mutation capability");
        }
        player.setGameMode(GameType.SURVIVAL);
        BackpackEquipment.set(player, BackpackEquipment.get(player).copy());
        try (Transaction outer = Transaction.openOuter()) {
            helper.assertTrue(context.getItemVariant().isBlank(), "Replaced equipment has a blank old context variant");
            helper.assertValueEqual(context.getMainSlot().getAmount(), 0L, "Even a same-UUID physical equipment replacement invalidates the old context");
            helper.assertValueEqual(items.insert(ItemVariant.of(Items.DIRT), 1, outer), 0L, "Old equipment item lookup is lifetime-bound");
            helper.assertValueEqual(fluid.insert(WATER, 1, outer), 0L, "Old equipment fluid lookup is lifetime-bound");
            helper.assertValueEqual(energy.insert(1, outer), 0L, "Old equipment energy lookup is lifetime-bound");
            outer.commit();
        }
        helper.assertTrue(BackpackEquipment.inventory(player).orElseThrow() != warm, "Replacement equipment has an independent canonical handle");
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
        helper.assertTrue(UpgradeEngine.action(bag, 0, "external_output", BackpackTestSupport.player(helper)), "Battery output can be disabled through its server action");
        helper.assertFalse(NbtAccess.getBooleanOr(bag.settings(upgrade), "external_output", true), "The action changes the external-output setting");
        BackpackBattery battery = new BackpackBattery(bag, upgrade);
        BackpackBattery alias = new BackpackBattery(bag, upgrade);
        ItemStack initial = bag.stack().copy();
        helper.assertValueEqual(battery.getCapacity(), 100_000L, "Battery capacity follows ten rows");
        try (Transaction outer = Transaction.openOuter()) {
            helper.assertValueEqual(battery.insert(1_000, outer), 200L, "Battery limits each operation to the row-scaled rate");
            try (Transaction nested = outer.openNested()) {
                alias.extract(70, nested);
                nested.commit();
            }
        }
        helper.assertValueEqual(alias.getAmount(), 0L, "Nested energy operations roll back across aliases");
        assertExactItem(helper, bag.stack(), initial, "Battery rollback preserves absent amount settings exactly");
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
        placed.inventory().updateSettings(upgrade(placed.inventory(), 0), state -> state.putBoolean("external_output", true));
        EnergyStorage sided = EnergyStorage.SIDED.find(helper.getLevel(), position, Direction.NORTH);
        helper.assertTrue(sided != null, "Placed backpacks expose the actual Team Reborn sided API");
        try (Transaction transaction = Transaction.openOuter()) { sided.extract(100, transaction); }
        helper.assertValueEqual(sided.getAmount(), battery.getAmount(), "Aborted sided extraction restores block entity contents");
        helper.succeed();
    }

    public static void sidedResourceTransactions(GameTestHelper helper) {
        fluidCapabilityLifecycle(helper);
        BagInventory seed = bag(UpgradeKind.TANK, UpgradeKind.BATTERY, UpgradeKind.FILTER);
        fill(tank(seed), WATER, 81);
        seed.updateSettings(upgrade(seed, 1), state -> state.putLong("amount", 500));
        seed.setItem(0, new ItemStack(Items.DIAMOND, 5));
        seed.remember(1, new ItemStack(Items.EMERALD));
        seed.setFilter(upgrade(seed, 2), 0, new ItemStack(Items.DIAMOND));
        seed.updateSettings(upgrade(seed, 2), state -> { state.putString("filter_mode", "BLOCK"); state.putString("filter_direction", "BOTH"); });
        BlockPos position = helper.absolutePos(new BlockPos(3, 2, 3));
        BackpackBlockEntity entity = place(helper, position, seed);
        BagInventory bag = entity.inventory();
        List<Storage<ItemVariant>> items = new ArrayList<>();
        List<Storage<FluidVariant>> fluids = new ArrayList<>();
        List<EnergyStorage> energy = new ArrayList<>();
        ItemStack before = bag.stack().copy();
        for (Direction side : Direction.values()) {
            Storage<ItemVariant> item = ItemStorage.SIDED.find(helper.getLevel(), position, side);
            Storage<FluidVariant> fluid = FluidStorage.SIDED.find(helper.getLevel(), position, side);
            EnergyStorage power = EnergyStorage.SIDED.find(helper.getLevel(), position, side);
            helper.assertTrue(item != null && fluid != null && power != null, "Every face exposes the shared item/fluid/energy interfaces: " + side);
            items.add(item); fluids.add(fluid); energy.add(power);
            try (Transaction outer = Transaction.openOuter()) {
                helper.assertValueEqual(item.insert(ItemVariant.of(Items.DIRT), 4, outer), 4L, "Sided item insertion reaches permitted storage");
                helper.assertValueEqual(fluid.insert(WATER, 31, outer), 31L, "Sided fluid insertion reaches the installed tank");
                helper.assertValueEqual(power.insert(17, outer), 17L, "Sided energy insertion reaches the installed battery");
                try (Transaction nested = outer.openNested()) {
                    helper.assertValueEqual(item.extract(ItemVariant.of(Items.DIRT), 1, nested), 1L, "Sided item extraction shares its transaction");
                    helper.assertValueEqual(fluid.extract(WATER, 1, nested), 1L, "Sided fluid extraction shares its transaction");
                    helper.assertValueEqual(power.extract(1, nested), 1L, "Sided energy extraction shares its transaction");
                    nested.commit();
                }
                helper.assertValueEqual(item.extract(ItemVariant.of(Items.DIAMOND), 1, outer), 0L, "An aggregate external item view respects output filters");
                for (StorageView<ItemVariant> view : item) if (view.getResource().getItem() == Items.DIAMOND)
                    helper.assertValueEqual(view.extract(ItemVariant.of(Items.DIAMOND), 1, outer), 0L, "A per-cell external view cannot bypass output filters");
            }
            BackpackTestSupport.assertStack(helper, bag.stack(), before, "Outer rollback restores all resource types and nested changes on " + side);
        }
        try (Transaction outer = Transaction.openOuter()) {
            for (int index = 0; index < 6; index++) {
                items.get(index).insert(ItemVariant.of(Items.DIRT), 1, outer);
                fluids.get(index).insert(WATER, 2, outer);
                energy.get(index).insert(3, outer);
            }
            outer.commit();
        }
        helper.assertValueEqual(BackpackTestSupport.count(bag, Items.DIRT), 6, "All sided item handles share the same six committed items");
        helper.assertTrue(bag.getItem(1).isEmpty(), "External items respect the remembered emerald-only cell");
        helper.assertValueEqual(tank(bag).getAmount(), 93L, "All sided fluid handles share one exact tank amount");
        helper.assertValueEqual(ResourceRuntime.batteryStored(bag, 1), 518L, "All sided energy handles share one battery amount");
        try (Transaction outer = Transaction.openOuter()) {
            helper.assertValueEqual(energy.get(0).extract(125, outer), 125L, "One face can consume part of this tick's allowance");
            try (Transaction nested = outer.openNested()) {
                helper.assertValueEqual(energy.get(1).extract(100, nested), 75L, "Another face sees only the shared remaining allowance");
                nested.commit();
            }
            helper.assertValueEqual(energy.get(2).extract(1, outer), 0L, "A third alias cannot multiply the output rate");
        }
        helper.assertValueEqual(energy.get(0).getAmount(), 518L, "Aborted extraction restores physical energy");
        try (Transaction outer = Transaction.openOuter()) {
            helper.assertValueEqual(energy.get(5).extract(200, outer), 200L, "Aborted extraction also restores the shared output allowance");
            helper.assertValueEqual(energy.get(0).extract(1, outer), 0L, "A committed allowance cannot be reset by switching sides");
            outer.commit();
        }
        ServerPlayer player = BackpackTestSupport.player(helper);
        helper.assertFalse(UpgradeEngine.action(bag, 0, "external_output", player), "The battery-only output action rejects a tank");
        helper.assertFalse(bag.settings(upgrade(bag, 0)).contains("external_output"), "Rejected action does not write settings on another family");
        helper.assertTrue(UpgradeEngine.action(bag, 1, "external_output", player), "The server action switches the battery to input-only");
        for (EnergyStorage power : energy) helper.assertTrue(power.supportsInsertion() && !power.supportsExtraction(), "Cached handles advertise changed extraction support immediately");
        helper.assertValueEqual(energy.get(0).getAmount(), 318L, "Disabling output never erases stored energy");
        try (Transaction outer = Transaction.openOuter()) {
            helper.assertValueEqual(energy.get(0).insert(7, outer), 7L, "An input-only port still accepts compatible power");
            outer.commit();
        }
        var publicTag = entity.getUpdateTag(helper.getLevel().registryAccess());
        helper.assertValueEqual(NbtAccess.getIntOr(publicTag, "energy_ports", 0), 0x1555, "The update publishes only insertion support on all six faces and unsided access");
        ItemStack publicStack = com.kadamitas.fabricatedbackpacks.compat.ItemStackTemplate.CODEC.parse(net.minecraft.resources.RegistryOps.create(
                net.minecraft.nbt.NbtOps.INSTANCE, helper.getLevel().registryAccess()), publicTag.get("backpack")).getOrThrow().create();
        helper.assertFalse(publicStack.has(BagComponents.UPGRADES) || publicTag.toString().contains("tank_fluid") || publicTag.toString().contains("amount:"),
                "Capability updates do not publish battery quantities, fluid state, or private upgrades");
        var rules = BackpackConfig.get();
        try {
            BackpackConfig.configure(ConfigFile.decode("{\"storage\":{\"disableConnections\":true}}"));
            for (int index = 0; index < 6; index++) try (Transaction outer = Transaction.openOuter()) {
                helper.assertValueEqual(items.get(index).insert(ItemVariant.of(Items.DIRT), 1, outer), 0L, "Cached items recheck the connection gate");
                helper.assertValueEqual(fluids.get(index).insert(WATER, 1, outer), 0L, "Cached fluids recheck the connection gate");
                helper.assertFalse(fluids.get(index).supportsInsertion() || fluids.get(index).supportsExtraction(),
                        "Disabled connections immediately clear cached fluid capability flags");
                helper.assertValueEqual(energy.get(index).insert(1, outer), 0L, "Cached energy rechecks the connection gate");
                helper.assertFalse(energy.get(index).supportsInsertion(), "Disabled connections also invalidate support flags");
            }
            BackpackConfig.configure(ConfigFile.decode("{\"storage\":{\"blockedConnections\":[\"minecraft:stone\"]}}"));
            helper.getLevel().setBlock(position.north(), Blocks.STONE.defaultBlockState(), 3);
            helper.assertTrue(EnergyStorage.SIDED.find(helper.getLevel(), position, Direction.NORTH) == null
                    && FluidStorage.SIDED.find(helper.getLevel(), position, Direction.NORTH) == null, "Neighbor block rules deny the affected face");
            try (Transaction outer = Transaction.openOuter()) {
                helper.assertValueEqual(energy.get(Direction.NORTH.ordinal()).insert(1, outer), 0L, "Previously cached north port sees the new blocked neighbor");
                helper.assertValueEqual(energy.get(Direction.SOUTH.ordinal()).insert(1, outer), 1L, "An unrelated face remains usable");
            }
        } finally { BackpackConfig.configure(rules); }
        entity.setStack(bag(UpgradeKind.TANK, UpgradeKind.BATTERY).stack());
        for (int index = 0; index < 6; index++) try (Transaction outer = Transaction.openOuter()) {
            helper.assertValueEqual(items.get(index).insert(ItemVariant.of(Items.DIRT), 1, outer), 0L, "Replaced placed bag invalidates cached item handles");
            helper.assertValueEqual(fluids.get(index).insert(WATER, 1, outer), 0L, "Replaced placed bag invalidates cached fluid handles");
            helper.assertFalse(fluids.get(index).supportsInsertion() || fluids.get(index).supportsExtraction(),
                    "Replaced placed bags cannot retain a fluid capability advertisement");
            helper.assertValueEqual(energy.get(index).insert(1, outer), 0L, "Replaced placed bag invalidates cached energy handles");
        }
        helper.succeed();
    }

    private static void fluidCapabilityLifecycle(GameTestHelper helper) {
        BlockPos position = helper.absolutePos(new BlockPos(5, 2, 5));
        BackpackBlockEntity entity = place(helper, position, bag(UpgradeKind.BATTERY));
        BagInventory inventory = entity.inventory();
        List<Storage<FluidVariant>> ports = new ArrayList<>();
        for (int index = 0; index <= Direction.values().length; index++) {
            Direction side = index == Direction.values().length ? null : Direction.values()[index];
            Storage<FluidVariant> port = FluidStorage.SIDED.find(helper.getLevel(), position, side);
            helper.assertTrue(port != null, "The dynamic fluid lookup remains available for later upgrade changes: " + side);
            helper.assertFalse(port.supportsInsertion() || port.supportsExtraction(), "A battery-only bag advertises no fluid endpoint: " + side);
            try (Transaction transaction = Transaction.openOuter()) {
                helper.assertValueEqual(port.insert(WATER, 1, transaction), 0L, "A bag without a tank or void policy cannot accept fluid");
                helper.assertValueEqual(port.extract(WATER, 1, transaction), 0L, "A bag without a tank cannot supply fluid");
            }
            ports.add(port);
        }
        BlockPos pipePosition = position.south();
        helper.getLevel().setBlock(pipePosition, AutomationRegistry.CONDUIT_BUNDLE.defaultBlockState(), 3);
        ConduitBundleBlockEntity pipe = (ConduitBundleBlockEntity) helper.getLevel().getBlockEntity(pipePosition);
        helper.assertTrue(pipe.install(ConduitKind.FLUID), "The real conduit fixture installs its fluid lane");
        helper.assertFalse(pipe.visualState().connected(ConduitKind.FLUID, Direction.NORTH)
                        || pipe.visualState().endpoint(ConduitKind.FLUID, Direction.NORTH),
                "A real conduit renders neither a fluid arm nor an interface plate on a battery-only bag");

        inventory.upgrades().setItem(1, new ItemStack(BackpackRegistry.item(UpgradeKind.TANK)));
        BackpackTank physical = new BackpackTank(inventory, upgrade(inventory, 1), false);
        for (Storage<FluidVariant> port : ports) helper.assertTrue(port.supportsInsertion() && port.supportsExtraction(),
                "All cached faces and unsided lookup recognize an added empty tank");
        StorageView<FluidVariant> retained = ports.getFirst().iterator().next();
        pipe.refreshVisual(); // The same production refresh runs periodically and on neighbor changes.
        helper.assertTrue(pipe.visualState().endpoint(ConduitKind.FLUID, Direction.NORTH), "Installing a tank produces a real fluid interface plate");
        ItemStack empty = inventory.stack().copy();
        try (Transaction transaction = Transaction.openOuter()) {
            helper.assertValueEqual(ports.getFirst().insert(WATER, 29, transaction), 29L, "The newly admitted endpoint supports real transactions");
        }
        assertExactItem(helper, inventory.stack(), empty, "Capability discovery does not change exact rollback semantics");
        fill(physical, WATER, physical.getCapacity());
        ItemStack filledTank = upgrade(inventory, 1).stack().copy();
        for (Storage<FluidVariant> port : ports) helper.assertTrue(port.supportsInsertion() && port.supportsExtraction(),
                "A full tank retains both capabilities even though immediate insertion has no space");
        ItemStack removed = inventory.upgrades().removeItemNoUpdate(1);
        inventory.save();
        helper.assertFalse(physical.supportsInsertion() || physical.supportsExtraction(), "A detached physical tank invalidates both capability flags");
        helper.assertTrue(retained instanceof Storage<?> storage && !storage.supportsInsertion() && !storage.supportsExtraction(),
                "A previously obtained fluid slot view also invalidates its capabilities");
        for (Storage<FluidVariant> port : ports) helper.assertFalse(port.supportsInsertion() || port.supportsExtraction(),
                "Removing the last tank clears every retained aggregate fluid capability");
        pipe.refreshVisual();
        helper.assertFalse(pipe.visualState().endpoint(ConduitKind.FLUID, Direction.NORTH), "Removing the last tank removes the actual fluid interface plate");
        assertExactItem(helper, removed, filledTank, "Removing a capability preserves every fluid quantity and component on its tank item");

        BagInventory itemBag = bag(UpgradeKind.BATTERY);
        SimpleContainer holder = new SimpleContainer(itemBag.stack());
        Storage<FluidVariant> itemPort = context(holder, 0).find(FluidStorage.ITEM);
        helper.assertTrue(itemPort != null && !itemPort.supportsInsertion() && !itemPort.supportsExtraction(),
                "The public item lookup also suppresses a battery-only fluid endpoint");
        itemBag.upgrades().setItem(1, removed);
        itemBag.save();
        helper.assertTrue(itemPort.supportsInsertion() && itemPort.supportsExtraction(), "An existing item context sees an added full tank");
        var rules = BackpackConfig.get();
        try {
            BackpackConfig.configure(ConfigFile.decode("{\"storage\":{\"itemFluidAccess\":false}}"));
            helper.assertFalse(itemPort.supportsInsertion() || itemPort.supportsExtraction(), "Disabled item fluid access clears cached support flags");
        } finally { BackpackConfig.configure(rules); }
        itemBag.upgrades().setItem(1, ItemStack.EMPTY);
        itemBag.save();
        helper.assertFalse(itemPort.supportsInsertion() || itemPort.supportsExtraction(), "Removing an item-context tank clears its cached support flags");
        holder.setItem(0, bag(UpgradeKind.TANK).stack());
        helper.assertFalse(itemPort.supportsInsertion() || itemPort.supportsExtraction(), "A stale item lookup cannot advertise a replacement bag's tank");
    }

    public static void placedEnergyOutput(GameTestHelper helper) {
        BlockPos position = helper.absolutePos(new BlockPos(3, 2, 3));
        BlockPos nestedPosition = helper.absolutePos(new BlockPos(1, 2, 6));
        List<Receiver> receivers = receivers(helper, position, 50);
        List<Receiver> nestedReceivers = receivers(helper, nestedPosition, 1_000);
        try {
            BagInventory seed = bag(UpgradeKind.BATTERY);
            seed.updateSettings(upgrade(seed, 0), state -> state.putLong("amount", 1_000));
            BackpackBlockEntity entity = place(helper, position, seed);
            EnergyStorage retained = EnergyStorage.SIDED.find(helper.getLevel(), position, Direction.NORTH);
            tickPlaced(helper, entity);
            helper.assertValueEqual(receivers.stream().mapToLong(value -> value.storage.amount).sum(), 200L, "Placed source pushes one shared 200-unit budget into real API receivers");
            helper.assertValueEqual(ResourceRuntime.batteryStored(entity.inventory(), 0), 800L, "The source loses exactly the receivers' accepted energy");
            for (Receiver receiver : receivers) helper.assertTrue(receiver.queried.equals(Set.of(receiver.face)), "Each adjacent receiver is queried on its opposite face");
            tickPlaced(helper, entity);
            helper.assertValueEqual(receivers.stream().mapToLong(value -> value.storage.amount).sum(), 200L, "Repeated same-tick calls do not mint another output budget");
            try (Transaction outer = Transaction.openOuter()) {
                helper.assertValueEqual(retained.extract(1, outer), 0L, "Automatic pushing and cached sided extraction share the same budget");
            }
            ServerPlayer player = BackpackTestSupport.player(helper);
            int notifications = receivers.stream().mapToInt(value -> value.neighborUpdates).sum();
            helper.assertTrue(UpgradeEngine.action(entity.inventory(), 0, "external_output", player), "The battery's server action switches automatic output off");
            tickPlaced(helper, entity);
            helper.assertTrue(receivers.stream().mapToInt(value -> value.neighborUpdates).sum() > notifications, "Changed support notifies neighboring cable/machine blocks");
            helper.assertTrue(retained.supportsInsertion() && !retained.supportsExtraction(), "A retained port becomes input-only without changing the inventory identity");
            helper.assertValueEqual(ResourceRuntime.batteryStored(entity.inventory(), 0), 800L, "Switching output off leaves stored energy untouched");
            ItemStack detached = entity.inventory().upgrades().getItem(0);
            entity.inventory().upgrades().setItem(0, ItemStack.EMPTY);
            notifications = receivers.stream().mapToInt(value -> value.neighborUpdates).sum();
            tickPlaced(helper, entity);
            helper.assertFalse(retained.supportsInsertion() || retained.supportsExtraction(), "Removing the last battery invalidates an existing port's capabilities");
            helper.assertTrue(receivers.stream().mapToInt(value -> value.neighborUpdates).sum() > notifications, "Capability removal also notifies neighboring blocks");
            entity.inventory().upgrades().setItem(0, detached);
            tickPlaced(helper, entity);
            helper.assertValueEqual(ResourceRuntime.batteryStored(entity.inventory(), 0), 800L, "Removing and reinstalling the same physical fixture battery preserves its resource");

            BagInventory root = bag(UpgradeKind.INCEPTION, UpgradeKind.BATTERY);
            root.updateSettings(upgrade(root, 1), state -> state.putLong("amount", 1_000));
            BagInventory child = BackpackTestSupport.bag(BackpackTier.LEATHER, UpgradeKind.BATTERY);
            child.updateSettings(upgrade(child, 0), state -> state.putLong("amount", 500));
            root.setItem(0, child.stack());
            BackpackBlockEntity nested = place(helper, nestedPosition, root);
            EnergyStorage nestedPort = EnergyStorage.SIDED.find(helper.getLevel(), nestedPosition, Direction.SOUTH);
            try (Transaction outer = Transaction.openOuter()) {
                helper.assertValueEqual(nestedPort.extract(Long.MAX_VALUE, outer), 260L, "Ordered nested/root batteries each contribute their own configured output rate");
                helper.assertValueEqual(EnergyStorage.SIDED.find(helper.getLevel(), nestedPosition, Direction.WEST).extract(1, outer), 0L,
                        "A second aggregate handle cannot spend either battery's allowance again");
            }
            tickPlaced(helper, nested);
            helper.assertValueEqual(nestedReceivers.stream().mapToLong(value -> value.storage.amount).sum(), 260L, "Aborted aggregate extraction restores both allowances before actual pushes");
            BagInventory nestedSaved = BagInventory.of(BackpackTestSupport.roundTrip(helper.getLevel(), nested.stack()));
            helper.assertValueEqual(ResourceRuntime.batteryStored(nestedSaved, 1), 800L, "Outer battery push persists in the root codec");
            helper.assertValueEqual(ResourceRuntime.batteryStored(BagInventory.of(nestedSaved.getItem(0)), 0), 440L, "Child battery push persists through its parent codec");
            nested.inventory().updateSettings(upgrade(nested.inventory(), 1), state -> state.putBoolean("external_output", false));
            nested.inventory().updateSettings(state -> state.putBoolean("inception_outer_inventory", false));

            BlockPos sourcePosition = helper.absolutePos(new BlockPos(6, 2, 6));
            BagInventory peerSeed = bag(UpgradeKind.BATTERY);
            peerSeed.updateSettings(upgrade(peerSeed, 0), state -> state.putLong("amount", 500));
            BackpackBlockEntity first = place(helper, sourcePosition, seed);
            BackpackBlockEntity second = place(helper, sourcePosition.north(), peerSeed);
            tickPlaced(helper, first);
            helper.assertValueEqual(ResourceRuntime.batteryStored(first.inventory(), 0), 1_000L, "An output-enabled backpack does not push into another output-enabled backpack");
            helper.assertValueEqual(ResourceRuntime.batteryStored(second.inventory(), 0), 500L, "The first source tick cannot hide a transfer by waiting for a return transfer");
            tickPlaced(helper, second);
            helper.assertValueEqual(ResourceRuntime.batteryStored(first.inventory(), 0), 1_000L, "The second source also refuses reverse circulation");
            helper.assertTrue(UpgradeEngine.action(second.inventory(), 0, "external_output", player), "A receiving backpack can explicitly become a sink");
            tickPlaced(helper, first);
            helper.assertValueEqual(ResourceRuntime.batteryStored(first.inventory(), 0), 800L, "A source pushes to an explicitly input-only backpack");
            helper.assertValueEqual(ResourceRuntime.batteryStored(second.inventory(), 0), 700L, "Backpack-to-backpack input preserves exact energy");
            tickPlaced(helper, second);
            helper.assertValueEqual(ResourceRuntime.batteryStored(second.inventory(), 0), 700L, "The input-only receiver never pushes that energy back");
            first.inventory().updateSettings(upgrade(first.inventory(), 0), state -> state.putBoolean("external_output", false));
            ResourceRuntime.tick(seed, helper.getLevel(), position, player);
            helper.assertValueEqual(ResourceRuntime.batteryStored(seed, 0), 1_000L, "A carried battery beside receivers never passively drains into the world");

            helper.runAfterDelay(1, () -> {
                try {
                    helper.assertTrue(UpgradeEngine.action(entity.inventory(), 0, "external_output", player), "The next real tick can re-enable this source");
                    long beforeNext = receivers.stream().mapToLong(value -> value.storage.amount).sum();
                    tickPlaced(helper, entity);
                    helper.assertValueEqual(receivers.stream().mapToLong(value -> value.storage.amount).sum() - beforeNext, 200L,
                            "A new server tick receives exactly one fresh output allowance");
                    tickPlaced(helper, entity);
                    helper.assertValueEqual(receivers.stream().mapToLong(value -> value.storage.amount).sum() - beforeNext, 200L,
                            "Repeated calls in that next tick still share one allowance");
                    tickPlaced(helper, nested);
                    helper.assertValueEqual(nestedReceivers.stream().mapToLong(value -> value.storage.amount).sum(), 260L,
                            "Disabled outer resource traversal does not expose a child's fresh tick allowance");
                    helper.succeed();
                } finally { removeReceivers(helper, position); removeReceivers(helper, nestedPosition); }
            });
        } catch (RuntimeException | Error failure) {
            removeReceivers(helper, position); removeReceivers(helper, nestedPosition);
            throw failure;
        }
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
        helper.assertValueEqual(NbtAccess.getIntOr(bag.settings(upgrade(bag, 1)), "target", -1), 10_000, "Target adjustment clamps without overflowing");
        helper.assertValueEqual(NbtAccess.getIntOr(bag.settings(upgrade(bag, 1)), "levels", -1), 1, "Level adjustment clamps without underflowing");
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
        ExperienceOrb orb = new ExperienceOrb(helper.getLevel(), orbPosition.x, orbPosition.y, orbPosition.z, 7);
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
            helper.assertValueEqual(NbtAccess.getDoubleOr(cooking.settings(upgrade(cooking, 1)), "experience", -1), 0.75, "Fractional cooking XP remains in the machine");
            long remaining = helper.getLevel().getEntitiesOfClass(ExperienceOrb.class, new AABB(magnetPosition).inflate(3), value -> !value.isRemoved()).stream()
                    .mapToLong(value -> (long) value.getValue() * ((ExperienceOrbAccessor) value).fabricatedBackpacks$getCount()).sum();
            long captured = (tank(magnet).getAmount() - before) / FluidAmount.DROPLETS_PER_XP;
            helper.assertValueEqual(captured, 10L, "A partly available tank accepts exactly its remaining whole points");
            helper.assertValueEqual(captured + remaining, 21L, "Splitting a merged orb preserves every point in all three original orbs");
            helper.succeed();
        });
    }
}
