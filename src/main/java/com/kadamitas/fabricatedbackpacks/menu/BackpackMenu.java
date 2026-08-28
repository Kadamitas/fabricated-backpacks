package com.kadamitas.fabricatedbackpacks.menu;

import com.kadamitas.fabricatedbackpacks.block.BackpackBlockEntity;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.equipment.BackpackEquipment;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.storage.BagComponents;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.storage.InstalledUpgrade;
import com.kadamitas.fabricatedbackpacks.upgrade.JukeboxRuntime;
import com.kadamitas.fabricatedbackpacks.upgrade.UpgradeEngine;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

public final class BackpackMenu extends AbstractContainerMenu implements BackpackSessionMenu {
    public static final int VISIBLE_ROWS = 6;
    private final BagInventory bag;
    private final BagOpeningData source;
    private final Player owner;
    private final BackpackBlockEntity placed;
    private final BagLease lease;
    private boolean leaseClosed;
    private boolean removed;
    private int retainedViews;
    private final int[] state = {0, -1, 0}; // storage page, selected upgrade, memory/no-sort editing
    private final int upgradeStart;
    private final int auxiliaryStart;
    private final int auxiliaryCount;
    private int visibleAuxiliaryFirst;
    private int visibleAuxiliaryCount = Integer.MAX_VALUE;
    private int[] storageRanks;
    private int filteredSize;
    private final int playerStart;
    private final Container auxiliary;

    public BackpackMenu(int id, Inventory inventory, BagOpeningData data) {
        this(id, inventory, data, BagInventory.clientOf(data.stack()), null);
    }
    public BackpackMenu(int id, Inventory inventory, BagOpeningData data, BagInventory bag, BackpackBlockEntity placed) {
        this(id, inventory, data, bag, placed, null);
    }
    public BackpackMenu(int id, Inventory inventory, BagOpeningData data, BagInventory bag, BackpackBlockEntity placed, BagLease lease) {
        super(BackpackMenus.BACKPACK, id);
        this.bag = bag;
        this.source = data;
        this.owner = inventory.player;
        if (owner.level().isClientSide()) bag.markClientMirror();
        this.placed = placed;
        this.lease = lease;
        if (preferences().getBooleanOr("keep_tab", true)) {
            int saved = bag.settings().getIntOr("last_tab", -1);
            if (saved >= 0 && saved < bag.upgrades().getContainerSize()) state[1] = saved;
        }
        int columns = bag.columns();
        for (int index = 0; index < bag.getContainerSize(); index++) {
            final int slotIndex = index;
            addSlot(new Slot(bag, index, 8 + index % columns * 18, 32 + index / columns % VISIBLE_ROWS * 18) {
                @Override public boolean mayPlace(ItemStack stack) { return bag.canPlaceItem(slotIndex, stack, owner); }
                @Override public boolean mayPickup(Player player) { return bag.canTakeItem(player.getInventory(), slotIndex, getItem()); }
                @Override public int getMaxStackSize(ItemStack stack) { return bag.capacity(stack); }
                @Override public int getMaxStackSize() { return Integer.MAX_VALUE; }
                @Override public boolean isActive() { return storageRank(slotIndex) >= 0
                        && storageRank(slotIndex) / (columns * VISIBLE_ROWS) == state[0] && !bag.blocked(slotIndex); }
            });
        }
        upgradeStart = slots.size();
        for (int index = 0; index < bag.upgrades().getContainerSize(); index++) {
            final int upgradeSlot = index;
            addSlot(new Slot(bag.upgrades(), index, storageWidth() + 9, 32 + index * 18) {
                @Override public boolean mayPlace(ItemStack stack) {
                    UpgradeKind kind = BackpackRegistry.kind(stack).orElse(null);
                    if (kind == UpgradeKind.INCEPTION && nestedDepth() > 0) return false;
                    if (kind == UpgradeKind.STACK_UPGRADE_OMEGA_TIER) {
                        if (!owner.getAbilities().instabuild) return false;
                    }
                    return bag.canInstall(upgradeSlot, stack, owner);
                }
                @Override public boolean mayPickup(Player player) { return bag.canRemoveUpgrade(upgradeSlot, player); }
                @Override public int getMaxStackSize() { return 1; }
            });
        }
        auxiliaryCount = Math.max(16, bag.installedUpgrades().stream().mapToInt(bag::inventorySlots).max().orElse(0));
        auxiliary = new SelectedUpgradeInventory();
        auxiliaryStart = slots.size();
        for (int index = 0; index < auxiliaryCount; index++) {
            final int auxiliarySlot = index;
            addSlot(new Slot(auxiliary, index, storageWidth() + 51 + index % 4 * 18, 54 + index / 4 * 18) {
                @Override public boolean mayPlace(ItemStack stack) { return validAuxiliary(auxiliarySlot, stack); }
                @Override public boolean isActive() {
                    return selected().filter(upgrade -> !isWorkstation(upgrade.kind()) && auxiliarySlot < bag.inventorySlots(upgrade)
                            && (!owner.level().isClientSide() || auxiliarySlot >= visibleAuxiliaryFirst
                            && auxiliarySlot - visibleAuxiliaryFirst < visibleAuxiliaryCount)).isPresent();
                }
                @Override public void onTake(Player player, ItemStack item) {
                    super.onTake(player, item);
                    if (player instanceof ServerPlayer serverPlayer && auxiliarySlot == 2) UpgradeEngine.outputTaken(bag, state[1], serverPlayer);
                }
            });
        }
        playerStart = slots.size();
        addStandardInventorySlots(inventory, 8, inventoryY());
        for (int index = 0; index < state.length; index++) addDataSlot(DataSlot.shared(state, index));
        if (placed != null) placed.open();
    }

    public BagInventory bag() { return bag; }
    public int auxiliaryStart() { return auxiliaryStart; }
    public int auxiliaryCount() { return auxiliaryCount; }
    public void setAuxiliaryWindow(int first, int count) {
        if (!owner.level().isClientSide()) return;
        visibleAuxiliaryFirst = Math.clamp(first, 0, auxiliaryCount);
        visibleAuxiliaryCount = Math.clamp(count, 0, auxiliaryCount);
    }
    public int nestedDepth() { return lease == null ? 0 : lease.nestedDepth(); }
    public boolean locks(ItemStack stack) { return stack == bag.stack() || lease != null && lease.locks(stack); }
    public net.minecraft.nbt.CompoundTag preferences() {
        return owner instanceof ServerPlayer serverPlayer
                ? com.kadamitas.fabricatedbackpacks.settings.SettingsRuntime.effective(bag, serverPlayer) : bag.settings();
    }
    @Override public BagInventory backpack() { return bag; }
    public void retainView() { retainedViews++; if (placed != null) placed.open(); }
    public void releaseView() { retainedViews = Math.max(0, retainedViews - 1); if (placed != null) placed.close(); releaseLease(); }
    private void releaseLease() {
        if (!owner.level().isClientSide() && removed && retainedViews == 0 && lease != null && !leaseClosed) {
            leaseClosed = true;
            lease.close();
        }
    }
    public BagOpeningData source() { return source; }
    public int page() { return state[0]; }
    public int pages() { return storageRanks == null ? Math.ceilDiv(bag.rows(), VISIBLE_ROWS)
            : Math.max(1, Math.ceilDiv(filteredSize, bag.columns() * VISIBLE_ROWS)); }
    public boolean filtering() { return storageRanks != null; }
    public int filteredSize() { return storageRanks == null ? bag.getContainerSize() : filteredSize; }
    public int storageRank(int slot) { return storageRanks == null ? slot : storageRanks[slot]; }
    /** A search mask changes presentation of cells this menu already owns; it never supplies item data. */
    public boolean storageView(String mask) {
        if (!mask.isEmpty() && (mask.length() != bag.getContainerSize() || mask.chars().anyMatch(value -> value != '0' && value != '1'))) return false;
        int[] next = mask.isEmpty() ? null : new int[bag.getContainerSize()];
        int size = 0;
        if (next != null) for (int slot = 0; slot < next.length; slot++)
            next[slot] = mask.charAt(slot) == '1' && !bag.blocked(slot) ? size++ : -1;
        if (!java.util.Arrays.equals(storageRanks, next)) { storageRanks = next; filteredSize = size; state[0] = 0; }
        return true;
    }
    public int editMode() { return state[2]; }
    public int selectedSlot() { return state[1]; }
    public int storageWidth() { return bag.columns() * 18 + 16; }
    public int inventoryY() { return Math.min(VISIBLE_ROWS, bag.rows()) * 18 + 49; }
    public int imageWidth() {
        int columns = bag.installedUpgrades().stream().mapToInt(upgrade -> Math.max(bag.filterColumns(upgrade), bag.inventoryColumns(upgrade))).max().orElse(4);
        return storageWidth() + Math.max(144, 67 + columns * 18);
    }
    public int imageHeight() { return 238; }
    public Optional<InstalledUpgrade> selected() { return bag.installedUpgrades().stream().filter(upgrade -> upgrade.slot() == state[1]).findFirst(); }
    public static boolean isWorkstation(UpgradeKind kind) {
        return kind == UpgradeKind.CRAFTING || kind == UpgradeKind.ANVIL || kind == UpgradeKind.SMITHING || kind == UpgradeKind.STONECUTTER;
    }

    private boolean validAuxiliary(int index, ItemStack stack) {
        if (BackpackRegistry.isBackpack(stack)) return false;
        InstalledUpgrade upgrade = selected().orElse(null);
        if (upgrade == null || index >= bag.inventorySlots(upgrade) || isWorkstation(upgrade.kind())) return false;
        if (owner.level() instanceof net.minecraft.server.level.ServerLevel serverLevel)
            return UpgradeEngine.isValidAuxiliary(bag, upgrade, index, stack, serverLevel);
        if (upgrade.kind().family().equals("jukebox")) return JukeboxRuntime.isDisc(stack);
        if (upgrade.kind().family().equals("cooking") && index == 2) return false;
        return !BackpackRegistry.isBackpack(stack);
    }

    @Override public boolean stillValid(Player player) {
        if (owner != player || !player.isAlive()) return false;
        if (player.level().isClientSide()) return true;
        if (source.source() == BagOpeningData.PLACED) return placed != null && placed.stillValid(player);
        if (source.source() == BagOpeningData.LEASED) return lease != null && !leaseClosed && lease.valid();
        if (source.source() == BagOpeningData.EQUIPPED) return BackpackEquipment.isCurrent(player, bag);
        ItemStack current = source.source() == BagOpeningData.EQUIPPED ? BackpackEquipment.get(player)
                : player.getInventory().getItem(source.inventorySlot());
        return BackpackRegistry.isBackpack(current) && bag.identity().equals(current.getOrDefault(BagComponents.IDENTITY, ""))
                && (source.source() == BagOpeningData.EQUIPPED || current == bag.stack());
    }

    @Override public void clicked(int index, int button, ContainerInput input, Player player) {
        if (!stillValid(player)) return;
        if (index >= slots.size() || index < -999) return;
        if (index >= 0 && !slots.get(index).isActive()) return;
        if (index >= 0 && lease != null && lease.locks(slots.get(index).getItem())) return;
        if (input == ContainerInput.SWAP && button >= 0 && button < player.getInventory().getContainerSize()
                && lease != null && lease.locks(player.getInventory().getItem(button))) return;
        if (source.source() == BagOpeningData.INVENTORY && index >= playerStart && index < slots.size()
                && slots.get(index).getContainerSlot() == source.inventorySlot()) return;
        // Number-key swaps can target the bag itself even when another slot is clicked.
        if (input == ContainerInput.SWAP && source.source() == BagOpeningData.INVENTORY && button == source.inventorySlot()) return;
        if (index >= 0 && index < bag.getContainerSize() && state[2] != 0) {
            if (input != ContainerInput.PICKUP || button < 0 || button > 1) return;
            if (state[2] == 1) bag.remember(index, button == 1 ? ItemStack.EMPTY : getCarried().isEmpty() ? bag.getItem(index) : getCarried());
            else bag.toggleNoSort(index);
            persist();
            if (player instanceof ServerPlayer serverPlayer) com.kadamitas.fabricatedbackpacks.network.BackpackNetworking.sendSettings(serverPlayer, this);
            return;
        }
        if (index >= 0 && index < bag.getContainerSize() && bag.isInfiniteSlot(index)) {
            infiniteClick(index, button, input, player);
            persist();
            broadcastChanges();
            return;
        }
        var before = java.util.stream.IntStream.range(0, bag.getContainerSize()).mapToObj(i -> bag.getItem(i).copy()).toList();
        var oldUpgrades = bag.installedUpgrades();
        super.clicked(index, button, input, player);
        for (int slot = 0; slot < bag.getContainerSize(); slot++) {
            if (!ItemStack.matches(before.get(slot), bag.getItem(slot))) UpgradeEngine.onManualSlotChanged(bag, slot);
        }
        if (player instanceof ServerPlayer serverPlayer) for (var old : oldUpgrades) {
            if (bag.upgrades().getItem(old.slot()) != old.stack()) UpgradeEngine.stopUpgrade(bag, old.slot(), serverPlayer.level().getServer());
        }
        persist();
    }

    @Override public boolean clickMenuButton(Player player, int action) {
        if (!stillValid(player)) return false;
        if (action >= 100 && action < 100 + bag.upgrades().getContainerSize()) {
            state[1] = action - 100;
            bag.updateSettings(tag -> tag.putInt("last_tab", state[1]));
        }
        else switch (action) {
            case 0 -> bag.sort("name", player);
            case 1 -> state[0] = (state[0] + 1) % pages();
            case 2 -> state[2] = (state[2] + 1) % 3;
            case 3 -> bag.sort("count", player);
            case 4 -> bag.sort("mod", player);
            case 5 -> bag.sort("tags", player);
            case 6 -> StorageActions.memory(bag, true);
            case 7 -> StorageActions.memory(bag, false);
            case 8 -> StorageActions.noSort(bag, true);
            case 9 -> StorageActions.noSort(bag, false);
            default -> { return false; }
        }
        persist();
        broadcastChanges();
        if (player instanceof ServerPlayer serverPlayer) com.kadamitas.fabricatedbackpacks.network.BackpackNetworking.sendSettings(serverPlayer, this);
        return true;
    }

    public void persist() {
        bag.save();
        if (!owner.level().isClientSide() && source.source() == BagOpeningData.EQUIPPED) BackpackEquipment.setFromInventory(owner, bag);
        if (placed != null) placed.setChanged();
        if (lease != null) lease.persist();
        if (owner instanceof ServerPlayer player && stillValid(player))
            com.kadamitas.fabricatedbackpacks.admin.BackpackArchives.record(player.level(), bag, player);
    }
    @Override public void removed(Player player) {
        super.removed(player);
        if (!player.level().isClientSide()) persist();
        if (placed != null) placed.close();
        removed = true;
        releaseLease();
    }
    @Override public ItemStack quickMoveStack(Player player, int index) {
        if (!stillValid(player) || index < 0 || index >= slots.size() || !slots.get(index).isActive()) return ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (lease != null && lease.locks(slot.getItem())) return ItemStack.EMPTY;
        if (!slot.hasItem() || !slot.mayPickup(player)) return ItemStack.EMPTY;
        if (index < bag.getContainerSize() && bag.isInfiniteSlot(index)) return quickMoveInfinite(player, index);
        if (source.source() == BagOpeningData.INVENTORY && index >= playerStart && slot.getContainerSlot() == source.inventorySlot()) return ItemStack.EMPTY;
        ItemStack original = slot.getItem().copy();
        ItemStack moving = slot.getItem();
        if (index < playerStart) {
            if (!moveItemStackTo(moving, playerStart, slots.size(), true)) return ItemStack.EMPTY;
        } else if (preferences().getBooleanOr("shift_into_tab", false) && selected().isPresent()
                && moveItemStackTo(moving, auxiliaryStart, playerStart, false)) {
            // Upgrade inputs enforce their real validators, just like direct cursor placement.
        } else if (BackpackRegistry.kind(moving).isPresent() && moveItemStackTo(moving, upgradeStart, auxiliaryStart, false)) {
            // The slot validator enforces unique families and capacity transitions.
        } else {
            ItemStack remainder = bag.infinityKind() == null ? UpgradeEngine.insert(bag, moving, false) : bag.insert(moving, false, player);
            if (remainder.getCount() == moving.getCount()) return ItemStack.EMPTY;
            moving.setCount(remainder.getCount());
        }
        if (moving.isEmpty()) slot.setByPlayer(ItemStack.EMPTY); else slot.setChanged();
        slot.onTake(player, original.copyWithCount(original.getCount() - moving.getCount()));
        persist();
        return original;
    }

    private void infiniteClick(int index, int button, ContainerInput input, Player player) {
        if (!slots.get(index).mayPickup(player)) return;
        ItemStack seed = bag.getItem(index);
        int limit = seed.getMaxStackSize();
        switch (input) {
            case PICKUP -> {
                if (button != 0 && button != 1) return;
                ItemStack carried = getCarried();
                if (!carried.isEmpty() && !ItemStack.isSameItemSameComponents(seed, carried)) return;
                int room = Math.max(0, limit - carried.getCount());
                int generated = button == 1 ? Math.min(1, room) : room;
                if (generated == 0) return;
                setCarried(seed.copyWithCount(carried.getCount() + generated));
            }
            case QUICK_MOVE -> quickMoveInfinite(player, index);
            case THROW -> {
                if ((button == 0 || button == 1) && getCarried().isEmpty()) player.drop(seed.copyWithCount(button == 0 ? 1 : limit), true);
            }
            case SWAP -> {
                if (button < 0 || button > 8 && button != Inventory.SLOT_OFFHAND) return;
                ItemStack destination = player.getInventory().getItem(button);
                if (!destination.isEmpty() && !ItemStack.isSameItemSameComponents(seed, destination)) return;
                if (destination.getCount() >= limit) return;
                player.getInventory().setItem(button, seed.copyWithCount(limit));
            }
            case CLONE -> {
                if (player.getAbilities().instabuild && getCarried().isEmpty()) setCarried(seed.copyWithCount(limit));
            }
            default -> { /* Drag, replacement and collection cannot mutate an established seed. */ }
        }
    }

    private ItemStack quickMoveInfinite(Player player, int index) {
        ItemStack seed = bag.getItem(index);
        ItemStack generated = seed.copyWithCount(seed.getMaxStackSize());
        int offered = generated.getCount();
        if (!moveItemStackTo(generated, playerStart, slots.size(), true)) return ItemStack.EMPTY;
        int moved = offered - generated.getCount();
        player.getInventory().setChanged();
        persist();
        return seed.copyWithCount(moved);
    }

    private final class SelectedUpgradeInventory extends SimpleContainer {
        SelectedUpgradeInventory() { super(auxiliaryCount); }
        @Override public int getMaxStackSize(ItemStack stack) { return selected().map(upgrade -> upgrade.kind() == UpgradeKind.BATTERY ? 1 : stack.getMaxStackSize()).orElse(0); }
        private Container target(int slot) {
            InstalledUpgrade upgrade = selected().orElse(null);
            return upgrade == null || slot < 0 || slot >= bag.inventorySlots(upgrade) ? null : bag.upgradeInventory(upgrade);
        }
        @Override public ItemStack getItem(int slot) { Container target = target(slot); return target == null ? ItemStack.EMPTY : target.getItem(slot); }
        @Override public void setItem(int slot, ItemStack item) { Container target = target(slot); if (target != null) { target.setItem(slot, item); persist(); } }
        @Override public ItemStack removeItem(int slot, int count) { Container target = target(slot); return target == null ? ItemStack.EMPTY : target.removeItem(slot, count); }
        @Override public ItemStack removeItemNoUpdate(int slot) { Container target = target(slot); return target == null ? ItemStack.EMPTY : target.removeItemNoUpdate(slot); }
        @Override public void setChanged() { selected().ifPresent(upgrade -> bag.upgradeInventory(upgrade).setChanged()); }
    }
}
