package com.kadamitas.fabricatedbackpacks.gametest;

import com.kadamitas.fabricatedbackpacks.block.BackpackBlockEntity;
import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.equipment.BackpackEquipment;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.resource.BackpackTank;
import com.kadamitas.fabricatedbackpacks.resource.ResourceRuntime;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;

/**
 * Exercises the handlers other Forge mods see: ForgeCapabilities looked up on the block entity,
 * with IItemHandler, IEnergyStorage and IFluidHandler calls, never this mod's storage wrappers.
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
        IItemHandler handler = entity.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.NORTH).orElse(null);
        helper.assertTrue(handler != null, "A placed backpack exposes Forge's item handler capability");
        helper.assertTrue(handler.getSlots() > 0 && handler.getStackInSlot(0).is(Items.DIAMOND) && handler.getStackInSlot(0).getCount() == 40,
                "The native item handler reads the live backpack contents");
        ItemStack simulated = handler.extractItem(0, 13, true);
        helper.assertTrue(simulated.is(Items.DIAMOND) && simulated.getCount() == 13, "Simulated extraction reports the available items");
        helper.assertValueEqual(BackpackTestSupport.count(entity.inventory(), Items.DIAMOND), 40, "Simulated extraction changes nothing");
        ItemStack extracted = handler.extractItem(0, 13, false);
        helper.assertTrue(extracted.is(Items.DIAMOND) && extracted.getCount() == 13, "A hopper-style extraction goes through Forge's handler API");
        helper.assertValueEqual(BackpackTestSupport.count(entity.inventory(), Items.DIAMOND), 27, "Native extraction leaves the remaining items in the backpack");
        helper.assertValueEqual(BackpackTestSupport.count(BagInventory.of(entity.stack().copy()), Items.DIAMOND), 27, "Native extraction is persisted in the placed item's components");
        ItemStack remainder = handler.insertItem(0, new ItemStack(Items.DIAMOND, 7), false);
        helper.assertTrue(remainder.isEmpty(), "Native insertion accepts items through the loader API");
        helper.assertValueEqual(BackpackTestSupport.count(entity.inventory(), Items.DIAMOND), 34, "Native insertion is stored in the backpack");

        BagInventory overflow = BackpackTestSupport.bag(BackpackTier.LEATHER, UpgradeKind.VOID);
        overflow.setFilter(BackpackTestSupport.upgrade(overflow, 0), 0, new ItemStack(Items.DIRT));
        overflow.updateSettings(BackpackTestSupport.upgrade(overflow, 0), state -> state.putString("void_mode", "STORAGE_OVERFLOW"));
        overflow.setItem(0, new ItemStack(Items.STONE, 64));
        BackpackBlockEntity sink = place(helper, helper.absolutePos(new BlockPos(5, 2, 2)), overflow);
        IItemHandler sinkHandler = sink.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.NORTH).orElseThrow(AssertionError::new);
        int admission = sink.inventory().getContainerSize();
        helper.assertValueEqual(sinkHandler.getSlots(), admission + 1, "An enabled void policy exposes one separate insertion-only port");
        helper.assertTrue(sinkHandler.getStackInSlot(admission).isEmpty() && sinkHandler.extractItem(admission, 64, false).isEmpty(),
                "The policy port never invents visible or extractable items");
        helper.assertFalse(sinkHandler.isItemValid(admission, new ItemStack(BackpackRegistry.item(BackpackTier.LEATHER))),
                "Backpacks cannot enter the insertion-only policy port");
        helper.assertValueEqual(sinkHandler.insertItem(0, new ItemStack(Items.DIRT, 5), false).getCount(), 5,
                "A full selected slot cannot void items that fit in another physical slot");
        helper.assertValueEqual(BackpackTestSupport.count(sink.inventory(), Items.DIRT), 0,
                "Aggregate capacity probing rolls back and never reroutes an indexed insert");
        helper.assertTrue(sinkHandler.insertItem(1, new ItemStack(Items.DIRT, 5), true).isEmpty(),
                "Native simulation reports the selected empty slot's capacity");
        helper.assertValueEqual(BackpackTestSupport.count(sink.inventory(), Items.DIRT), 0,
                "Native simulation keeps every physical slot unchanged");
        helper.assertTrue(sinkHandler.insertItem(1, new ItemStack(Items.DIRT, 5), false).isEmpty(),
                "Accepted indexed insertion reaches exactly the selected slot");
        helper.assertValueEqual(sink.inventory().getItem(1).getCount(), 5, "The selected slot owns the real insertion");
        for (int slot = 0; slot < sink.inventory().getContainerSize(); slot++)
            sink.inventory().setItem(slot, new ItemStack(Items.STONE, 64));
        helper.assertTrue(sinkHandler.insertItem(0, new ItemStack(Items.DIRT, 5), false).isEmpty(),
                "Genuine aggregate overflow is intentionally discarded");
        helper.assertValueEqual(BackpackTestSupport.count(sink.inventory(), Items.DIRT), 0,
                "Voided indexed items are never retained or duplicated");
        helper.assertTrue(sinkHandler.insertItem(admission, new ItemStack(Items.DIAMOND), true).is(Items.DIAMOND),
                "The policy port rejects a nonmatching item instead of silently voiding it");
        BlockPos hopperPosition = sink.getBlockPos().above();
        var hopperState = net.minecraft.world.level.block.Blocks.HOPPER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.HopperBlock.FACING, Direction.DOWN);
        helper.getLevel().setBlock(hopperPosition, hopperState, 3);
        var hopper = (net.minecraft.world.level.block.entity.HopperBlockEntity) helper.getLevel().getBlockEntity(hopperPosition);
        hopper.setItem(0, new ItemStack(Items.DIRT));
        net.minecraft.world.level.block.entity.HopperBlockEntity.pushItemsTick(helper.getLevel(), hopperPosition, hopperState, hopper);
        helper.assertTrue(hopper.isEmpty(), "A real Forge hopper can feed an enabled overflow void even when all physical slots are full");
        helper.assertValueEqual(BackpackTestSupport.count(sink.inventory(), Items.DIRT), 0,
                "Full-backpack hopper overflow is discarded rather than retained");
        helper.succeed();
    }

    static void nativeEnergyHandlerTransfer(GameTestHelper helper) {
        BlockPos position = helper.absolutePos(new BlockPos(5, 2, 2));
        BagInventory seed = BackpackTestSupport.bag(BackpackTier.NETHERITE, UpgradeKind.BATTERY);
        seed.updateSettings(BackpackTestSupport.upgrade(seed, 0), state -> state.putLong("amount", 1_000));
        BackpackBlockEntity entity = place(helper, position, seed);
        IEnergyStorage handler = entity.getCapability(ForgeCapabilities.ENERGY, Direction.NORTH).orElse(null);
        helper.assertTrue(handler != null, "A placed battery backpack exposes Forge's energy capability");
        helper.assertValueEqual(handler.getEnergyStored(), 1_000, "The native energy storage reads the stored amount");
        helper.assertValueEqual(handler.extractEnergy(150, false), 150, "Native extraction honors the battery's transfer rate");
        helper.assertValueEqual(ResourceRuntime.batteryStored(entity.inventory(), 0), 850L, "Native extraction is persisted in the battery upgrade");
        helper.assertValueEqual(handler.receiveEnergy(120, false), 120, "Native insertion charges the battery");
        helper.assertValueEqual(ResourceRuntime.batteryStored(entity.inventory(), 0), 970L, "Native insertion is persisted in the battery upgrade");
        // A placed battery shares one 200-unit per-tick output budget with its automatic pushing; 150 are spent.
        helper.assertValueEqual(handler.extractEnergy(100, true), 50, "Simulated extraction reports the remaining per-tick output budget");
        helper.assertValueEqual(ResourceRuntime.batteryStored(entity.inventory(), 0), 970L, "Simulated extraction changes nothing");
        helper.succeed();
    }

    static void nativeFluidHandlerTransfer(GameTestHelper helper) {
        BlockPos position = helper.absolutePos(new BlockPos(2, 2, 5));
        BagInventory seed = BackpackTestSupport.bag(BackpackTier.NETHERITE, UpgradeKind.TANK);
        BackpackBlockEntity entity = place(helper, position, seed);
        IFluidHandler handler = entity.getCapability(ForgeCapabilities.FLUID_HANDLER, Direction.NORTH).orElse(null);
        helper.assertTrue(handler != null, "A placed tank backpack exposes Forge's fluid handler capability");
        helper.assertValueEqual(handler.fill(new FluidStack(Fluids.WATER, 10), IFluidHandler.FluidAction.EXECUTE), 10, "The native fluid handler accepts millibuckets");
        BackpackTank tank = new BackpackTank(entity.inventory(), BackpackTestSupport.upgrade(entity.inventory(), 0), false);
        helper.assertValueEqual(tank.getAmount(), 810L, "Each native millibucket is stored as 81 droplets");
        helper.assertValueEqual(handler.getFluidInTank(0).getAmount(), 10, "The native handler reports whole millibuckets");
        helper.assertValueEqual(handler.drain(new FluidStack(Fluids.WATER, 4), IFluidHandler.FluidAction.EXECUTE).getAmount(), 4, "The native fluid handler drains millibuckets");
        helper.assertValueEqual(tank.getAmount(), 486L, "Native drain is persisted in the tank upgrade");
        helper.assertValueEqual(handler.drain(new FluidStack(Fluids.WATER, 3), IFluidHandler.FluidAction.SIMULATE).getAmount(), 3, "Simulated drain reports the available fluid");
        helper.assertValueEqual(tank.getAmount(), 486L, "Simulated drain changes nothing");
        helper.succeed();
    }

    /**
     * The integrated client keeps its own Player with the server player's numeric id in the same
     * JVM. Accepting the owner sync for that copy must never replace the server's live attachment,
     * otherwise the open equipped menu is invalidated and closed on the next server tick.
     */
    static void nativeAttachmentIdentityPerEntity(GameTestHelper helper) {
        ServerPlayer owner = (ServerPlayer) helper.makeMockServerPlayer(GameType.SURVIVAL);
        Player clientCopy = helper.makeMockPlayer(GameType.SURVIVAL);
        clientCopy.setId(owner.getId());
        helper.assertTrue(owner.equals(clientCopy) && owner != clientCopy, "The fixture reproduces id-equal but distinct entities");

        BackpackEquipment.set(owner, BackpackTestSupport.bag(BackpackTier.GOLD).stack());
        ItemStack published = BackpackEquipment.get(owner);
        BagInventory bag = BackpackEquipment.inventory(owner).orElseThrow();
        helper.assertTrue(BackpackEquipment.isCurrent(owner, bag), "A freshly opened equipped inventory is current");

        var ops = RegistryOps.create(NbtOps.INSTANCE, owner.registryAccess());
        CompoundTag encoded = new CompoundTag();
        encoded.put("value", ItemStack.OPTIONAL_CODEC.encodeStart(ops, published).getOrThrow());
        BackpackEquipment.EQUIPPED.acceptClient(clientCopy, encoded);

        helper.assertTrue(BackpackEquipment.get(owner) == published, "The client's accepted copy must not replace the server's equipped stack");
        helper.assertTrue(BackpackEquipment.isCurrent(owner, bag), "The equipped inventory stays current after the client copy synchronizes");
        ItemStack accepted = BackpackEquipment.get(clientCopy);
        helper.assertTrue(accepted != published && ItemStack.matches(accepted, published), "The client copy holds its own equal value");
        helper.assertTrue(BackpackEquipment.setFromInventory(owner, bag) && BackpackEquipment.isCurrent(owner, bag),
                "Persisting the open equipped inventory (which resynchronizes the owner) keeps it current");
        helper.succeed();
    }
}
