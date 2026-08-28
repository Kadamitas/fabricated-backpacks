package com.kadamitas.fabricatedbackpacks.block;

import com.kadamitas.fabricatedbackpacks.compat.NbtAccess;

import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.resource.PlacedEnergyTransfer;
import com.kadamitas.fabricatedbackpacks.upgrade.UpgradeEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import com.kadamitas.fabricatedbackpacks.item.BackpackColors;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import com.kadamitas.fabricatedbackpacks.compat.ItemStackTemplate;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import team.reborn.energy.api.EnergyStorage;

public final class BackpackBlockEntity extends BlockEntity implements WorldlyContainer {
    private ItemStack stack;
    private BagInventory inventory;
    private int viewers;
    private final BackpackLidAnimation lidAnimation = new BackpackLidAnimation();
    private ItemStack lastSentVisual = ItemStack.EMPTY;
    private final PlacedEnergyTransfer energyTransfer = new PlacedEnergyTransfer(this);
    private int clientEnergySupport;

    public BackpackBlockEntity(BlockPos pos, BlockState state) {
        super(BackpackRegistry.BLOCK_ENTITY, pos, state);
        stack = new ItemStack(state.getBlock());
    }
    public ItemStack stack() { return stack; }
    public EnergyStorage energyStorage(Direction side) { return energyTransfer.storage(side); }
    public int clientEnergySupport() { return clientEnergySupport; }
    public BagInventory inventory() {
        if (inventory == null) {
            inventory = BagInventory.of(stack);
            inventory.onChange(this::setChanged);
            if (level instanceof ServerLevel serverLevel) com.kadamitas.fabricatedbackpacks.world.MobLoot.materialize(inventory, serverLevel, worldPosition, null);
        }
        return inventory;
    }
    public void setStack(ItemStack newStack) {
        if (!BackpackRegistry.isBackpack(newStack)) throw new IllegalArgumentException("Not a backpack");
        stack = newStack.copyWithCount(1);
        inventory = null;
        energyTransfer.contentsReplaced();
        setChanged();
        synchronize();
    }
    public int viewers() { return viewers; }
    public float lidOpenness(float partialTick) { return lidAnimation.openness(partialTick); }
    public void open() { viewers++; updateOpen(); }
    public void close() { viewers = Math.max(0, viewers - 1); updateOpen(); }
    private void updateOpen() {
        if (level != null && !level.isClientSide && !isRemoved()) level.setBlock(worldPosition, getBlockState().setValue(BackpackBlock.OPEN, viewers > 0), 3);
    }
    public void synchronize() {
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
    @Override protected void saveAdditional(CompoundTag output, HolderLookup.Provider registries) {
        super.saveAdditional(output, registries);
        output.put("backpack", ItemStackTemplate.CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE),
                ItemStackTemplate.fromNonEmptyStack(stack)).getOrThrow());
    }
    @Override protected void loadAdditional(CompoundTag input, HolderLookup.Provider registries) {
        super.loadAdditional(input, registries);
        ItemStack previous = stack;
        int previousEnergySupport = clientEnergySupport;
        stack = input.contains("backpack") ? ItemStackTemplate.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), input.get("backpack"))
                .result().map(ItemStackTemplate::create).orElseGet(() -> new ItemStack(getBlockState().getBlock()))
                : new ItemStack(getBlockState().getBlock());
        inventory = null;
        energyTransfer.contentsReplaced();
        viewers = 0;
        clientEnergySupport = NbtAccess.getIntOr(input, "energy_ports", 0) & 0x3fff;
        if (level != null && level.isClientSide && !isRemoved()
                && (meshTint(previous, 0) != meshTint(stack, 0) || meshTint(previous, 1) != meshTint(stack, 1)
                    || previousEnergySupport != clientEnergySupport)) {
            // BE data updates the live lid immediately, but the tinted body lives in the chunk mesh.
            // An equal block state does not otherwise invalidate that mesh after a dye update.
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
        }
    }
    private static int meshTint(ItemStack value, int index) {
        return BackpackColors.color(value, index, index == 0 ? BackpackColors.DEFAULT_BODY : BackpackColors.DEFAULT_TRIM);
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        // Public connection flags are enough for cable rendering; contents and quantities stay private.
        tag.putInt("energy_ports", energyTransfer.supportMask());
        tag.put("backpack", ItemStackTemplate.CODEC.encodeStart(net.minecraft.resources.RegistryOps.create(net.minecraft.nbt.NbtOps.INSTANCE, registries),
                ItemStackTemplate.fromNonEmptyStack(visualStack())).getOrThrow());
        return tag;
    }
    private ItemStack visualStack() {
        return com.kadamitas.fabricatedbackpacks.item.BackpackVisuals.snapshot(stack);
    }
    @Override public void saveToItem(ItemStack picked, HolderLookup.Provider registries) {
        ItemStack copy = com.kadamitas.fabricatedbackpacks.storage.BackpackCopies.fork(stack);
        copy.remove(com.kadamitas.fabricatedbackpacks.storage.BagComponents.IDENTITY);
        picked.applyComponents(copy.getComponentsPatch());
    }
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
    public static void clientTick(Level level, BlockPos pos, BlockState state, BackpackBlockEntity entity) {
        entity.lidAnimation.tick(state.getValue(BackpackBlock.OPEN));
    }
    public static void tick(Level level, BlockPos pos, BlockState state, BackpackBlockEntity entity) {
        if (level instanceof ServerLevel serverLevel) {
            if (state.getValue(BackpackBlock.OPEN) != (entity.viewers > 0)) entity.updateOpen();
            com.kadamitas.fabricatedbackpacks.gameplay.BackpackTraversal.tick(entity.inventory(), serverLevel, pos, null);
            entity.energyTransfer.tick(serverLevel, pos);
            if (serverLevel.getServer().getTickCount() % 20 == 0)
                com.kadamitas.fabricatedbackpacks.gameplay.BackpackRuntime.archiveTree(entity.inventory(), serverLevel, null);
            if (level.getGameTime() % 10 == 0) {
                ItemStack visual = entity.visualStack();
                if (!ItemStack.matches(visual, entity.lastSentVisual)) { entity.lastSentVisual = visual; entity.synchronize(); }
            }
        }
    }
    @Override public int getContainerSize() { return inventory().getContainerSize(); }
    @Override public boolean isEmpty() { return inventory().isEmpty(); }
    @Override public ItemStack getItem(int slot) { return inventory().getItem(slot); }
    @Override public ItemStack removeItem(int slot, int amount) { ItemStack result = inventory().removeItem(slot, amount); setChanged(); return result; }
    @Override public ItemStack removeItemNoUpdate(int slot) { return inventory().removeItemNoUpdate(slot); }
    @Override public void setItem(int slot, ItemStack item) { inventory().setItem(slot, item); setChanged(); }
    @Override public boolean stillValid(Player player) { return !isRemoved() && player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(worldPosition)) <= 64; }
    @Override public void clearContent() { inventory().clearContent(); setChanged(); }
    @Override public int getMaxStackSize() { return Integer.MAX_VALUE; }
    @Override public int getMaxStackSize(ItemStack item) { return inventory().capacity(item); }
    @Override public boolean canPlaceItem(int slot, ItemStack item) { return inventory().canPlaceItem(slot, item); }
    @Override public boolean canTakeItem(Container target, int slot, ItemStack item) { return inventory().canTakeItem(target, slot, item); }
    private boolean connectionAllowed(Direction side) {
        return !isRemoved() && com.kadamitas.fabricatedbackpacks.resource.ResourceRuntime.connectionAllowed(level, worldPosition, side);
    }
    @Override public int[] getSlotsForFace(Direction side) {
        return connectionAllowed(side) ? java.util.stream.IntStream.range(0, getContainerSize()).toArray() : new int[0];
    }
    @Override public boolean canPlaceItemThroughFace(int slot, ItemStack item, Direction side) {
        return connectionAllowed(side) && inventory().canPlaceItem(slot, item);
    }
    @Override public boolean canTakeItemThroughFace(int slot, ItemStack item, Direction side) {
        return connectionAllowed(side) && inventory().canTakeItem(null, slot, item);
    }
}
