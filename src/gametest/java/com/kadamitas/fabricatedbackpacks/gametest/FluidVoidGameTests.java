package com.kadamitas.fabricatedbackpacks.gametest;

import com.kadamitas.fabricatedbackpacks.block.BackpackBlockEntity;
import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.resource.BackpackTank;
import com.kadamitas.fabricatedbackpacks.resource.ResourceComponents;
import com.kadamitas.fabricatedbackpacks.resource.ResourceRuntime;
import com.kadamitas.fabricatedbackpacks.settings.SettingsTemplate;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.upgrade.UpgradeEngine;
import net.fabricmc.fabric.api.transfer.v1.context.ContainerItemContext;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.ContainerStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageUtil;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;

import static com.kadamitas.fabricatedbackpacks.gametest.BackpackTestSupport.*;

/** Real fluid/container/pump admission with explicit disposal and transaction rollback checks. */
public final class FluidVoidGameTests {
    private static final FluidVariant WATER = FluidVariant.of(Fluids.WATER);
    private static final FluidVariant LAVA = FluidVariant.of(Fluids.LAVA);
    private FluidVoidGameTests() {}

    private static BagInventory target() { return bag(BackpackTier.NETHERITE, UpgradeKind.TANK, UpgradeKind.ADVANCED_VOID); }
    private static BackpackTank tank(BagInventory bag, int slot) { return new BackpackTank(bag, upgrade(bag, slot), false); }
    private static void fill(Storage<FluidVariant> storage, FluidVariant fluid, long amount) {
        try (Transaction transaction = Transaction.openOuter()) {
            if (storage.insert(fluid, amount, transaction) != amount) throw new IllegalArgumentException("Invalid fluid fixture capacity");
            transaction.commit();
        }
    }
    private static void drain(BackpackTank tank) {
        try (Transaction transaction = Transaction.openOuter()) { tank.extract(tank.getResource(), tank.getAmount(), transaction); transaction.commit(); }
    }
    private static void mode(BagInventory bag, int slot, String mode) {
        bag.updateSettings(upgrade(bag, slot), tag -> { tag.putString("void_mode", mode); tag.putString("filter_mode", "ALLOW"); tag.putBoolean("enabled", true); });
    }
    private static long insert(Storage<FluidVariant> storage, FluidVariant fluid, long amount) {
        try (Transaction transaction = Transaction.openOuter()) { long accepted = storage.insert(fluid, amount, transaction); transaction.commit(); return accepted; }
    }

    public static void fluidVoidFiltersAndModes(GameTestHelper helper) {
        voidOnlyCapabilities(helper);
        BagInventory bag = target();
        BackpackTank physical = tank(bag, 0);
        Storage<FluidVariant> admission = ResourceRuntime.tankStorage(bag, 0, false);
        mode(bag, 1, "ALWAYS");
        helper.assertValueEqual(insert(admission, WATER, 17), 17L, "Empty allow filters still admit ordinary fluid storage");
        helper.assertValueEqual(physical.getAmount(), 17L, "Empty allow filters never turn ALWAYS into mass deletion");
        drain(physical);
        bag.setFilter(upgrade(bag, 1), 0, new ItemStack(Items.WATER_BUCKET));
        helper.assertValueEqual(insert(admission, WATER, 19), 19L, "A water bucket ghost explicitly matches water disposal");
        helper.assertValueEqual(physical.getAmount(), 0L, "Matched ALWAYS admission intentionally retains no fluid");
        helper.assertValueEqual(insert(admission, LAVA, 23), 23L, "A nonmatching fluid follows normal admission");
        helper.assertValueEqual(physical.getAmount(), 23L, "Rejected void filters preserve physical fluid");
        helper.assertTrue(physical.getResource().equals(LAVA), "Nonmatching lava identity is retained");
        drain(physical);
        bag.setFilter(upgrade(bag, 1), 0, new ItemStack(Items.LAVA_BUCKET));
        insert(admission, WATER, 29);
        helper.assertValueEqual(physical.getAmount(), 29L, "Changing the item ghost invalidates the old contained-fluid selection immediately");
        drain(physical);

        FluidVariant named = FluidVariant.of(Fluids.WATER, DataComponentPatch.builder()
                .set(DataComponents.CUSTOM_NAME, Component.literal("Glacial sample")).build());
        bag.setFilter(upgrade(bag, 1), 0, new ItemStack(Items.WATER_BUCKET));
        insert(admission, named, 31);
        helper.assertValueEqual(physical.getAmount(), 31L, "An ordinary bucket cannot void a component-distinct fluid");
        drain(physical);
        bag.setFilter(upgrade(bag, 1), 0, ItemStack.EMPTY);
        helper.assertTrue(ResourceRuntime.setFluidFilter(bag, 1, 2, named), "Typed filters accept a component-distinct resource");
        insert(admission, named, 37);
        helper.assertValueEqual(physical.getAmount(), 0L, "The exact typed variant is intentionally voided");
        insert(admission, WATER, 41);
        helper.assertValueEqual(physical.getAmount(), 41L, "An unqualified variant does not match a named-fluid filter");
        drain(physical);
        ResourceRuntime.setFluidFilter(bag, 1, 2, FluidVariant.blank());
        bag.setFilter(upgrade(bag, 1), 0, new ItemStack(Items.WATER_BUCKET));
        bag.updateSettings(upgrade(bag, 1), tag -> tag.putString("filter_mode", "BLOCK"));
        insert(admission, WATER, 43);
        insert(admission, LAVA, 47);
        helper.assertValueEqual(physical.getAmount(), 43L, "Block inversion excludes water from disposal but voids the other fluid");
        bag.updateSettings(upgrade(bag, 1), tag -> tag.putBoolean("enabled", false));
        helper.assertValueEqual(insert(admission, LAVA, 53), 0L, "A disabled void filter cannot bypass a tank's mixed-fluid rejection");
        helper.assertValueEqual(physical.getAmount(), 43L, "Disabling the upgrade preserves stored resources");
        helper.succeed();
    }

    private static void voidOnlyCapabilities(GameTestHelper helper) {
        BagInventory bag = bag(BackpackTier.NETHERITE, UpgradeKind.ADVANCED_VOID);
        Storage<FluidVariant> port = ResourceRuntime.fluidStorage(bag);
        mode(bag, 0, "ALWAYS");
        helper.assertFalse(port.supportsInsertion() || port.supportsExtraction(), "An empty allow list and no tank advertise no fluid capability");
        helper.assertValueEqual(insert(port, WATER, 13), 0L, "An empty allow list cannot erase fluid without storage");
        bag.setFilter(upgrade(bag, 0), 0, new ItemStack(Items.APPLE));
        helper.assertFalse(port.supportsInsertion(), "A non-fluid item ghost does not advertise void-fluid admission");
        bag.setFilter(upgrade(bag, 0), 0, new ItemStack(Items.WATER_BUCKET));
        helper.assertTrue(port.supportsInsertion() && !port.supportsExtraction(), "A matched container ghost advertises an input-only void endpoint");
        helper.assertValueEqual(insert(port, WATER, 17), 17L, "A void-only endpoint intentionally accepts its matched fluid");
        helper.assertValueEqual(insert(port, LAVA, 17), 0L, "An input-only void endpoint still rejects unmatched fluid");
        helper.assertFalse(port.iterator().hasNext(), "Void-only admission never fabricates a stored fluid view");
        bag.setFilter(upgrade(bag, 0), 0, ItemStack.EMPTY);
        ResourceRuntime.setFluidFilter(bag, 0, 0, WATER);
        helper.assertTrue(port.supportsInsertion() && !port.supportsExtraction(), "Typed fluid filters also enable input-only void admission");
        mode(bag, 0, "STORAGE_OVERFLOW");
        helper.assertTrue(port.supportsInsertion(), "Storage overflow with an explicit match can dispose fluid when no tanks are installed");
        helper.assertValueEqual(insert(port, WATER, 19), 19L, "No-storage overflow admission agrees with its advertised capability");
        mode(bag, 0, "SLOT_OVERFLOW");
        helper.assertFalse(port.supportsInsertion() || port.supportsExtraction(), "Slot overflow cannot advertise a fluid endpoint without a stored representation");
        helper.assertValueEqual(insert(port, WATER, 23), 0L, "Slot overflow cannot create a representation by deleting the first fluid");
        mode(bag, 0, "ALWAYS");
        bag.updateSettings(upgrade(bag, 0), tag -> tag.putString("filter_mode", "CONTENTS"));
        helper.assertFalse(port.supportsInsertion(), "Contents matching without tanks has no fluid to admit");
        ResourceRuntime.setFluidFilter(bag, 0, 0, FluidVariant.blank());
        bag.updateSettings(upgrade(bag, 0), tag -> tag.putString("filter_mode", "BLOCK"));
        helper.assertTrue(port.supportsInsertion() && !port.supportsExtraction(), "An empty block list intentionally permits void-only insertion");
        helper.assertValueEqual(insert(port, LAVA, 29), 29L, "Inverted void filters preserve their actual admission semantics");
        bag.updateSettings(upgrade(bag, 0), tag -> tag.putBoolean("enabled", false));
        helper.assertFalse(port.supportsInsertion() || port.supportsExtraction(), "Disabling the last void policy clears the cached fluid endpoint");
        helper.assertValueEqual(insert(port, WATER, 31), 0L, "Disabled void admission preserves supplied resources");

        mode(bag, 0, "ALWAYS");
        ResourceRuntime.setFluidFilter(bag, 0, 0, WATER);
        BagInventory source = bag(BackpackTier.IRON, UpgradeKind.TANK);
        fill(tank(source, 0), WATER, 37);
        ItemStack sourceBefore = source.stack().copy();
        ItemStack destinationBefore = bag.stack().copy();
        try (Transaction transaction = Transaction.openOuter()) {
            helper.assertValueEqual(StorageUtil.move(tank(source, 0), port, fluid -> fluid.equals(WATER), 37, transaction),
                    37L, "Advertised void-only admission participates in a real source transaction");
        }
        assertStack(helper, source.stack(), sourceBefore, "Aborted void-only transfer restores the exact source components");
        assertStack(helper, bag.stack(), destinationBefore, "Aborted void-only transfer does not invent destination state");

        BagInventory parent = bag(BackpackTier.NETHERITE, UpgradeKind.INCEPTION);
        parent.setItem(0, bag.stack());
        Storage<FluidVariant> nested = ResourceRuntime.fluidStorage(parent);
        helper.assertTrue(nested.supportsInsertion() && !nested.supportsExtraction(), "An eligible nested void policy contributes only insertion support");
        parent.updateSettings(tag -> tag.putBoolean("inception_outer_inventory", false));
        helper.assertFalse(nested.supportsInsertion() || nested.supportsExtraction(), "Disabling child access clears cached nested capabilities immediately");
        parent.updateSettings(tag -> tag.putBoolean("inception_outer_inventory", true));
        BagInventory child = BagInventory.of(parent.getItem(0));
        child.updateSettings(upgrade(child, 0), tag -> tag.putBoolean("enabled", false));
        child.upgrades().setItem(1, new ItemStack(BackpackRegistry.item(UpgradeKind.TANK)));
        parent.save();
        helper.assertTrue(nested.supportsInsertion() && nested.supportsExtraction(), "An empty nested tank contributes both real fluid capabilities");
        parent.setItem(0, ItemStack.EMPTY);
        helper.assertFalse(nested.supportsInsertion() || nested.supportsExtraction(), "Removing the child invalidates its aggregate fluid capability");
    }

    public static void fluidVoidOverflowAndRollback(GameTestHelper helper) {
        BagInventory bag = target();
        bag.setFilter(upgrade(bag, 1), 0, new ItemStack(Items.WATER_BUCKET));
        mode(bag, 1, "STORAGE_OVERFLOW");
        BackpackTank physical = tank(bag, 0);
        long capacity = physical.getCapacity();
        fill(physical, WATER, capacity - 10);
        BagInventory source = bag(BackpackTier.IRON, UpgradeKind.TANK);
        BackpackTank supply = tank(source, 0);
        fill(supply, WATER, 30);
        Storage<FluidVariant> admission = ResourceRuntime.tankStorage(bag, 0, true);
        try (Transaction outer = Transaction.openOuter()) {
            helper.assertValueEqual(StorageUtil.move(supply, admission, fluid -> true, 30, outer), 30L, "Overflow handles the stored ten plus explicitly discarded twenty droplets");
            helper.assertValueEqual(physical.getAmount(), capacity, "The tank stores only its free capacity");
            helper.assertValueEqual(supply.getAmount(), 0L, "The source has one transactional debit");
        }
        helper.assertValueEqual(physical.getAmount(), capacity - 10, "Abort restores the target's pre-overflow amount");
        helper.assertValueEqual(supply.getAmount(), 30L, "Abort also restores fluid that would have been intentionally voided");
        try (Transaction outer = Transaction.openOuter()) {
            StorageUtil.move(supply, admission, fluid -> true, 30, outer);
            outer.commit();
        }
        helper.assertValueEqual(physical.getAmount(), capacity, "Committed capacity overflow does not inflate the tank");
        helper.assertValueEqual(supply.getAmount(), 0L, "Committed disposal cannot leave the original source behind");

        BagInventory rate = target();
        rate.setFilter(upgrade(rate, 1), 0, new ItemStack(Items.WATER_BUCKET));
        mode(rate, 1, "STORAGE_OVERFLOW");
        helper.assertValueEqual(insert(ResourceRuntime.tankStorage(rate, 0, true), WATER, 2 * FluidConstants.BUCKET), FluidConstants.BUCKET,
                "A transfer-rate remainder is not mistaken for capacity overflow");
        helper.assertValueEqual(tank(rate, 0).getAmount(), FluidConstants.BUCKET, "Only the admitted rate is stored or acknowledged");

        BagInventory two = bag(BackpackTier.NETHERITE, UpgradeKind.TANK, UpgradeKind.TANK, UpgradeKind.ADVANCED_VOID);
        two.setFilter(upgrade(two, 2), 0, new ItemStack(Items.WATER_BUCKET));
        mode(two, 2, "SLOT_OVERFLOW");
        fill(tank(two, 0), WATER, capacity - 10);
        helper.assertValueEqual(insert(ResourceRuntime.fluidStorage(two), WATER, 30), 30L, "Slot overflow completes the existing tank representation");
        helper.assertValueEqual(tank(two, 0).getAmount(), capacity, "The represented tank fills exactly");
        helper.assertValueEqual(tank(two, 1).getAmount(), 0L, "Slot overflow does not spread excess into a second empty tank");
        BagInventory none = bag(BackpackTier.LEATHER, UpgradeKind.VOID);
        none.setFilter(upgrade(none, 0), 0, new ItemStack(Items.WATER_BUCKET));
        mode(none, 0, "SLOT_OVERFLOW");
        helper.assertValueEqual(insert(ResourceRuntime.fluidStorage(none), WATER, 61), 0L, "No first representation means slot overflow cannot erase the resource");

        BagInventory forbiddenAlways = target();
        forbiddenAlways.setFilter(upgrade(forbiddenAlways, 1), 0, new ItemStack(Items.WATER_BUCKET));
        mode(forbiddenAlways, 1, "ALWAYS");
        try {
            UpgradeEngine.allowAlwaysVoiding(false);
            insert(ResourceRuntime.tankStorage(forbiddenAlways, 0, false), WATER, 67);
            helper.assertValueEqual(tank(forbiddenAlways, 0).getAmount(), 67L, "A stored ALWAYS setting follows the server's overflow-only restriction");
        } finally { UpgradeEngine.allowAlwaysVoiding(true); }
        helper.succeed();
    }

    public static void fluidVoidItemContextsAndTemplates(GameTestHelper helper) {
        FluidVariant named = FluidVariant.of(Fluids.WATER, DataComponentPatch.builder()
                .set(DataComponents.CUSTOM_NAME, Component.literal("Template sample")).build());
        BagInventory source = target();
        mode(source, 1, "ALWAYS");
        ResourceRuntime.setFluidFilter(source, 1, 2, named);
        fill(tank(source, 0), WATER, 500);
        var ops = RegistryOps.create(NbtOps.INSTANCE, helper.getLevel().registryAccess());
        var encoded = SettingsTemplate.CODEC.encodeStart(ops, SettingsTemplate.capture(source)).getOrThrow();
        SettingsTemplate template = SettingsTemplate.CODEC.parse(ops, encoded).getOrThrow();
        BagInventory destination = target();
        fill(tank(destination, 0), LAVA, 71);
        template.apply(destination);
        helper.assertTrue(ResourceRuntime.fluidFilter(destination, 1, 2).equals(named), "Settings templates preserve the exact typed filter");
        helper.assertTrue(ResourceRuntime.fluidFilterDescription(destination, 1, 2).toString().contains("Template sample"), "Native filter description arguments include the selected resource name");
        helper.assertValueEqual(tank(destination, 0).getAmount(), 71L, "Template application cannot copy source fluid quantities");
        helper.assertTrue(tank(destination, 0).getResource().equals(LAVA), "Template application leaves destination fluid identity intact");
        BagInventory restored = BagInventory.of(roundTrip(helper.getLevel(), destination.stack()));
        helper.assertTrue(ResourceRuntime.fluidFilter(restored, 1, 2).equals(named), "Fluid filters survive the real item component codec");
        helper.assertTrue(ResourceRuntime.fluidFilter(restored, 1, 0).isBlank(), "Sparse typed filter holes survive serialization");

        SimpleContainer inventory = new SimpleContainer(restored.stack());
        var context = ContainerItemContext.ofSingleSlot(ContainerStorage.of(inventory, null).getSlot(0));
        Storage<FluidVariant> itemStorage = context.find(FluidStorage.ITEM);
        BagInventory supplyBag = bag(BackpackTier.IRON, UpgradeKind.TANK);
        BackpackTank supply = tank(supplyBag, 0);
        fill(supply, named, 73);
        ItemStack before = inventory.getItem(0).copy();
        try (Transaction outer = Transaction.openOuter()) {
            helper.assertValueEqual(StorageUtil.move(supply, itemStorage, fluid -> true, 73, outer), 73L, "Real item-fluid admission honors exact-variant disposal");
        }
        helper.assertValueEqual(supply.getAmount(), 73L, "Aborting an item-context void returns the complete source resource");
        assertStack(helper, inventory.getItem(0), before, "Aborted disposal leaves every target item component unchanged");
        try (Transaction outer = Transaction.openOuter()) { StorageUtil.move(supply, itemStorage, fluid -> true, 73, outer); outer.commit(); }
        helper.assertValueEqual(supply.getAmount(), 0L, "Committed item-context disposal consumes the real source");
        helper.assertValueEqual(tank(BagInventory.of(inventory.getItem(0)), 0).getAmount(), 71L, "Voiding another variant never overwrites stored lava");

        var player = player(helper);
        player.containerMenu.setCarried(new ItemStack(Items.WATER_BUCKET));
        ItemStack cursor = player.containerMenu.getCarried().copy();
        ResourceRuntime.action(destination, 1, "fluid_filter:3", player);
        helper.assertTrue(ResourceRuntime.fluidFilter(destination, 1, 3).equals(WATER), "Server action reads the fluid from the authoritative cursor container");
        assertStack(helper, player.containerMenu.getCarried(), cursor, "Copying a filter does not consume or modify the cursor container");
        ResourceRuntime.action(destination, 1, "fluid_filter:99", player);
        helper.assertTrue(upgrade(destination, 1).stack().get(ResourceComponents.VOID_FLUID_FILTERS).size() <= ResourceComponents.MAX_FLUID_FILTERS,
                "Untrusted filter rows cannot enlarge the persisted component");
        player.containerMenu.setCarried(ItemStack.EMPTY);
        ResourceRuntime.action(destination, 1, "fluid_filter:3", player);
        helper.assertTrue(ResourceRuntime.fluidFilter(destination, 1, 3).isBlank(), "An empty cursor clears the selected explicit filter");
        helper.succeed();
    }

    public static void fluidVoidNativePumpAndCursor(GameTestHelper helper) {
        BlockPos position = helper.absolutePos(new BlockPos(3, 2, 3));
        BlockPos neighbor = position.east();
        BagInventory supply = bag(BackpackTier.NETHERITE, UpgradeKind.TANK);
        fill(tank(supply, 0), WATER, 2 * FluidConstants.BUCKET);
        helper.getLevel().setBlock(neighbor, BackpackRegistry.block(BackpackTier.NETHERITE).defaultBlockState(), 3);
        BackpackBlockEntity placed = (BackpackBlockEntity) helper.getLevel().getBlockEntity(neighbor);
        placed.setStack(supply.stack());
        BagInventory pumping = bag(BackpackTier.NETHERITE, UpgradeKind.TANK, UpgradeKind.PUMP, UpgradeKind.VOID);
        fill(tank(pumping, 0), WATER, tank(pumping, 0).getCapacity());
        pumping.setFilter(upgrade(pumping, 2), 0, new ItemStack(Items.WATER_BUCKET));
        mode(pumping, 2, "STORAGE_OVERFLOW");
        ResourceRuntime.tick(pumping, helper.getLevel(), position, null);
        helper.assertValueEqual(tank(placed.inventory(), 0).getAmount(), FluidConstants.BUCKET, "The actual pump transfers one bucket from a real neighboring Fabric handler");
        helper.assertValueEqual(tank(pumping, 0).getAmount(), tank(pumping, 0).getCapacity(), "Matching pump overflow is intentionally discarded without inflating capacity");
        pumping.setFilter(upgrade(pumping, 2), 0, new ItemStack(Items.LAVA_BUCKET));
        pumping.updateSettings(upgrade(pumping, 1), tag -> tag.putLong("next_work", 0));
        ResourceRuntime.tick(pumping, helper.getLevel(), position, null);
        helper.assertValueEqual(tank(placed.inventory(), 0).getAmount(), FluidConstants.BUCKET, "Changing the filter to lava preserves the remaining water source");

        pumping.setFilter(upgrade(pumping, 2), 0, new ItemStack(Items.WATER_BUCKET));
        var player = player(helper);
        player.containerMenu.setCarried(new ItemStack(Items.WATER_BUCKET));
        ResourceRuntime.action(pumping, 0, "container", player);
        helper.assertTrue(player.containerMenu.getCarried().is(Items.BUCKET), "A real cursor bucket empties through selected-tank overflow admission");
        helper.assertValueEqual(tank(pumping, 0).getAmount(), tank(pumping, 0).getCapacity(), "Cursor disposal does not duplicate stored fluid");

        BagInventory world = bag(BackpackTier.NETHERITE, UpgradeKind.TANK, UpgradeKind.ADVANCED_PUMP, UpgradeKind.VOID);
        world.setFilter(upgrade(world, 2), 0, new ItemStack(Items.WATER_BUCKET));
        mode(world, 2, "STORAGE_OVERFLOW");
        fill(tank(world, 0), WATER, tank(world, 0).getCapacity());
        world.updateSettings(upgrade(world, 1), tag -> { tag.putBoolean("handlers", false); tag.putBoolean("hands", false); tag.putBoolean("world", true); });
        BlockPos worldSource = position.north();
        helper.getLevel().setBlock(worldSource, Blocks.WATER.defaultBlockState(), 3);
        ResourceRuntime.tick(world, helper.getLevel(), position, player);
        helper.assertTrue(helper.getLevel().getBlockState(worldSource).isAir(), "An allowed world source is removed only after the matching void transfer commits");
        helper.assertValueEqual(tank(world, 0).getAmount(), tank(world, 0).getCapacity(), "Intentional world disposal leaves the existing tank quantity unchanged");
        helper.succeed();
    }
}
