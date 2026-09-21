package com.kadamitas.fabricatedbackpacks.gametest;

import com.kadamitas.fabricatedbackpacks.block.BackpackBlockEntity;
import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.resource.BackpackTank;
import com.kadamitas.fabricatedbackpacks.resource.ResourceRuntime;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.ResourceHandlerUtil;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.VanillaContainerWrapper;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * Exercises the handlers other NeoForge mods see: the loader's own Capabilities lookups and
 * transfer types, never this mod's storage wrappers.
 */
final class NativeHandlerGameTests {
    private NativeHandlerGameTests() {}

    private static BackpackBlockEntity place(GameTestHelper helper, BlockPos position, BagInventory bag) {
        helper.getLevel().setBlock(position, BackpackRegistry.block(bag.tier()).defaultBlockState(), 3);
        BackpackBlockEntity entity = (BackpackBlockEntity) helper.getLevel().getBlockEntity(position);
        entity.setStack(bag.stack());
        return entity;
    }

    static void nativeItemHandlerExtraction(GameTestHelper helper) {
        BlockPos position = helper.absolutePos(new BlockPos(2, 2, 2));
        BagInventory seed = BackpackTestSupport.bag(BackpackTier.NETHERITE);
        seed.setItem(0, new ItemStack(Items.DIAMOND, 40));
        BackpackBlockEntity entity = place(helper, position, seed);
        ResourceHandler<ItemResource> handler = helper.getLevel().getCapability(Capabilities.Item.BLOCK, position, Direction.NORTH);
        helper.assertTrue(handler != null, "A placed backpack exposes NeoForge's block item handler");
        helper.assertTrue(handler.size() > 0 && handler.getResource(0).is(Items.DIAMOND) && handler.getAmountAsLong(0) == 40,
                "The native item handler reads the live backpack contents");
        SimpleContainer sink = new SimpleContainer(9);
        int moved = ResourceHandlerUtil.move(handler, VanillaContainerWrapper.of(sink), resource -> true, 13, null);
        helper.assertValueEqual(moved, 13, "A hopper-style native move extracts through the loader's handler API");
        helper.assertValueEqual(BackpackTestSupport.count(entity.inventory(), Items.DIAMOND), 27, "Native extraction leaves the remaining items in the backpack");
        helper.assertValueEqual(BackpackTestSupport.count(sink, Items.DIAMOND), 13, "The extracted items reach the vanilla sink");
        helper.assertValueEqual(BackpackTestSupport.count(BagInventory.of(entity.stack().copy()), Items.DIAMOND), 27, "Native extraction is persisted in the placed item's components");
        try (Transaction transaction = Transaction.openRoot()) {
            helper.assertValueEqual(handler.extract(0, ItemResource.of(Items.DIAMOND), 5, transaction), 5, "A native transaction can extract from the exposed slot");
        }
        helper.assertValueEqual(BackpackTestSupport.count(entity.inventory(), Items.DIAMOND), 27, "An aborted native extraction changes nothing");
        try (Transaction transaction = Transaction.openRoot()) {
            helper.assertValueEqual(handler.insert(ItemResource.of(Items.EMERALD), 7, transaction), 7, "Native insertion accepts a new item through the loader API");
            transaction.commit();
        }
        helper.assertValueEqual(BackpackTestSupport.count(entity.inventory(), Items.EMERALD), 7, "Native insertion is stored in the backpack");
        nativeIndexedVoidAdmission(helper);
        helper.succeed();
    }

    private static void nativeIndexedVoidAdmission(GameTestHelper helper) {
        BlockPos position = helper.absolutePos(new BlockPos(4, 2, 2));
        BagInventory seed = BackpackTestSupport.bag(BackpackTier.LEATHER, UpgradeKind.VOID);
        var upgrade = BackpackTestSupport.upgrade(seed, 0);
        seed.setFilter(upgrade, 0, new ItemStack(Items.DIRT));
        seed.updateSettings(upgrade, state -> state.putString("void_mode", "ALWAYS"));
        BackpackBlockEntity entity = place(helper, position, seed);
        BagInventory bag = entity.inventory();
        ResourceHandler<ItemResource> handler = helper.getLevel().getCapability(Capabilities.Item.BLOCK, position, Direction.NORTH);
        helper.assertTrue(handler != null, "The void backpack exposes a real native indexed item handler");
        try (Transaction transaction = Transaction.openRoot()) {
            helper.assertValueEqual(handler.insert(0, ItemResource.of(Items.DIRT), 7, transaction), 7,
                    "Indexed native insertion honors ALWAYS void admission");
            transaction.commit();
        }
        helper.assertValueEqual(BackpackTestSupport.count(bag, Items.DIRT), 0,
                "ALWAYS void does not leak the discarded item into the selected physical slot");
        bag.updateSettings(BackpackTestSupport.upgrade(bag, 0), state -> state.putString("void_mode", "OVERFLOW"));
        bag.setItem(0, new ItemStack(Items.STONE, 64));
        try (Transaction transaction = Transaction.openRoot()) {
            helper.assertValueEqual(handler.insert(0, ItemResource.of(Items.DIRT), 7, transaction), 0,
                    "A blocked indexed slot cannot void items while another physical slot has room");
            helper.assertValueEqual(handler.insert(1, ItemResource.of(Items.DIRT), 7, transaction), 7,
                    "The requested empty slot accepts overflow-mode input normally");
        }
        helper.assertValueEqual(BackpackTestSupport.count(bag, Items.DIRT), 0,
                "Aborted indexed admission and capacity probes leave all physical slots unchanged");
        try (Transaction transaction = Transaction.openRoot()) {
            handler.insert(1, ItemResource.of(Items.DIRT), 7, transaction);
            transaction.commit();
        }
        helper.assertValueEqual(bag.getItem(1).getCount(), 7, "Committed indexed insertion keeps its physical slot identity");
        helper.assertValueEqual(bag.getItem(0).getCount(), 64, "Indexed admission never rewrites the unrelated full slot");
    }

    static void nativeEnergyHandlerTransfer(GameTestHelper helper) {
        BlockPos position = helper.absolutePos(new BlockPos(5, 2, 2));
        BagInventory seed = BackpackTestSupport.bag(BackpackTier.NETHERITE, UpgradeKind.BATTERY);
        seed.updateSettings(BackpackTestSupport.upgrade(seed, 0), state -> state.putLong("amount", 1_000));
        BackpackBlockEntity entity = place(helper, position, seed);
        EnergyHandler handler = helper.getLevel().getCapability(Capabilities.Energy.BLOCK, position, Direction.NORTH);
        helper.assertTrue(handler != null, "A placed battery backpack exposes NeoForge's block energy handler");
        helper.assertValueEqual(handler.getAmountAsLong(), 1_000L, "The native energy handler reads the stored amount");
        try (Transaction transaction = Transaction.openRoot()) {
            helper.assertValueEqual(handler.extract(150, transaction), 150, "Native extraction honors the battery's transfer rate");
            transaction.commit();
        }
        helper.assertValueEqual(ResourceRuntime.batteryStored(entity.inventory(), 0), 850L, "Native extraction is persisted in the battery upgrade");
        try (Transaction transaction = Transaction.openRoot()) {
            helper.assertValueEqual(handler.insert(120, transaction), 120, "Native insertion charges the battery");
            transaction.commit();
        }
        helper.assertValueEqual(ResourceRuntime.batteryStored(entity.inventory(), 0), 970L, "Native insertion is persisted in the battery upgrade");
        try (Transaction transaction = Transaction.openRoot()) {
            // A placed battery shares one 200-unit per-tick output budget with its automatic pushing; 150 are spent.
            helper.assertValueEqual(handler.extract(100, transaction), 50, "Native extraction is bounded by the remaining per-tick output budget");
        }
        helper.assertValueEqual(ResourceRuntime.batteryStored(entity.inventory(), 0), 970L, "An aborted native extraction changes nothing");
        helper.succeed();
    }

    static void nativeFluidHandlerTransfer(GameTestHelper helper) {
        BlockPos position = helper.absolutePos(new BlockPos(2, 2, 5));
        BagInventory seed = BackpackTestSupport.bag(BackpackTier.NETHERITE, UpgradeKind.TANK);
        BackpackBlockEntity entity = place(helper, position, seed);
        ResourceHandler<FluidResource> handler = helper.getLevel().getCapability(Capabilities.Fluid.BLOCK, position, Direction.NORTH);
        helper.assertTrue(handler != null, "A placed tank backpack exposes NeoForge's block fluid handler");
        FluidResource water = FluidResource.of(Fluids.WATER);
        try (Transaction transaction = Transaction.openRoot()) {
            helper.assertValueEqual(handler.insert(water, 10, transaction), 10, "The native fluid handler accepts millibuckets");
            transaction.commit();
        }
        BackpackTank tank = new BackpackTank(entity.inventory(), BackpackTestSupport.upgrade(entity.inventory(), 0), false);
        helper.assertValueEqual(tank.getAmount(), 810L, "Each native millibucket is stored as 81 droplets");
        helper.assertValueEqual(handler.getAmountAsLong(0), 10L, "The native handler reports whole millibuckets");
        try (Transaction transaction = Transaction.openRoot()) {
            helper.assertValueEqual(handler.extract(water, 4, transaction), 4, "The native fluid handler drains millibuckets");
            transaction.commit();
        }
        helper.assertValueEqual(tank.getAmount(), 486L, "Native drain is persisted in the tank upgrade");
        try (Transaction transaction = Transaction.openRoot()) {
            handler.extract(water, 3, transaction);
        }
        helper.assertValueEqual(tank.getAmount(), 486L, "An aborted native drain changes nothing");
        helper.succeed();
    }
}
