package com.kadamitas.fabricatedbackpacks.automation.engine;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** Native slot synchronization and signed-short-safe counters; all changes remain on the server. */
public final class SteamEngineMenu extends AbstractContainerMenu {
    public static final int WIDTH = 176, HEIGHT = 176;
    public static final int ENABLED_DATA = 24, ACTIVE_DATA = 25, DATA_COUNT = 26;
    private final Player owner;
    private final SteamEngineBlockEntity engine;
    private final Container inventory;
    private final ContainerData data;
    private final BlockPos position;

    /** Fabric's client constructor uses a native synchronized inventory, never a private world snapshot. */
    public SteamEngineMenu(int id, Inventory playerInventory, BlockPos position) {
        this(id, playerInventory, null, new SimpleContainer(SteamEngineBlockEntity.SLOT_COUNT) {
                    @Override public int getMaxStackSize() { return 64; }
                },
                new SimpleContainerData(DATA_COUNT), position);
    }

    public SteamEngineMenu(int id, Inventory playerInventory, SteamEngineBlockEntity engine) {
        this(id, playerInventory, engine, engine, engine.data(), engine.getBlockPos());
    }

    private SteamEngineMenu(int id, Inventory playerInventory, SteamEngineBlockEntity engine,
                           Container inventory, ContainerData data, BlockPos position) {
        super(SteamEngineMenus.STEAM_ENGINE, id);
        this.owner = playerInventory.player;
        this.engine = engine;
        this.inventory = inventory;
        this.data = data;
        this.position = position.immutable();
        checkContainerSize(inventory, SteamEngineBlockEntity.SLOT_COUNT);
        checkContainerDataCount(data, DATA_COUNT);
        addMachineSlot(SteamEngineBlockEntity.FUEL, 44, 55);
        addMachineSlot(SteamEngineBlockEntity.WATER_INPUT, 44, 23);
        addMachineSlot(SteamEngineBlockEntity.FUEL_REMAINDER, 116, 55);
        addMachineSlot(SteamEngineBlockEntity.WATER_REMAINDER, 116, 23);
        com.kadamitas.fabricatedbackpacks.menu.MenuSlots.addInventory(this::addSlot, playerInventory, 8, 94);
        addDataSlots(data);
    }

    private void addMachineSlot(int index, int x, int y) {
        addSlot(new Slot(inventory, index, x, y) {
            @Override public boolean mayPlace(ItemStack stack) {
                if (stack.isEmpty()) return false;
                return index == SteamEngineBlockEntity.FUEL ? net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity.isFuel(stack)
                        : index == SteamEngineBlockEntity.WATER_INPUT && SteamEngineStorage.containsWater(stack);
            }
            @Override public boolean mayPickup(Player player) { return validInteraction(player); }
        });
    }

    public BlockPos position() { return position; }
    public long waterDroplets() { return counter(0); }
    public long waterCapacityDroplets() { return counter(1); }
    public long energy() { return counter(2); }
    public long storedEnergy() { return energy(); }
    public long energyCapacity() { return counter(3); }
    public int burnRemaining() { return (int) Math.min(Integer.MAX_VALUE, counter(4)); }
    public int burnDuration() { return (int) Math.min(Integer.MAX_VALUE, counter(5)); }
    public boolean enabled() { return data.get(ENABLED_DATA) != 0; }
    public boolean active() { return data.get(ACTIVE_DATA) != 0; }
    private long counter(int index) {
        int start = index * 4;
        return Math.max(0, SteamEngineWords.join(data.get(start), data.get(start + 1), data.get(start + 2), data.get(start + 3)));
    }

    @Override public boolean stillValid(Player player) {
        return player == owner && player.isAlive() && !player.isSpectator()
                && (player.level().isClientSide || engine != null && engine.stillValid(player));
    }

    private boolean validInteraction(Player player) {
        return stillValid(player) && (player.level().isClientSide || player.containerMenu == this);
    }

    @Override public boolean clickMenuButton(Player player, int action) {
        if (action != 0 || engine == null || !validInteraction(player)) return false;
        engine.setEnabled(!engine.enabled());
        broadcastChanges();
        return true;
    }

    @Override public void clicked(int slot, int button, ClickType input, Player player) {
        if (slot != -999 && (slot < 0 || slot >= slots.size())) return;
        if (validInteraction(player)) super.clicked(slot, button, input, player);
    }

    @Override public ItemStack quickMoveStack(Player player, int index) {
        if (!validInteraction(player) || index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (!slot.hasItem() || !slot.isActive() || !slot.mayPickup(player)) return ItemStack.EMPTY;
        ItemStack original = slot.getItem().copy();
        if (index < SteamEngineBlockEntity.SLOT_COUNT) {
            if (!moveItemStackTo(slot.getItem(), SteamEngineBlockEntity.SLOT_COUNT, slots.size(), true)) return ItemStack.EMPTY;
        } else {
            int destination = net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity.isFuel(original) ? SteamEngineBlockEntity.FUEL
                    : SteamEngineStorage.containsWater(original) ? SteamEngineBlockEntity.WATER_INPUT : -1;
            if (destination < 0 || !moveItemStackTo(slot.getItem(), destination, destination + 1, false)) return ItemStack.EMPTY;
        }
        if (slot.getItem().isEmpty()) slot.setByPlayer(ItemStack.EMPTY); else slot.setChanged();
        slot.onTake(player, slot.getItem());
        return original;
    }
}
