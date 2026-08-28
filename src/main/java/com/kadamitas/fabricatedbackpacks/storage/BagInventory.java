package com.kadamitas.fabricatedbackpacks.storage;

import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.domain.StackCapacity;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.config.BackpackConfig;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.upgrade.UpgradeEngine;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.lang.ref.WeakReference;
import java.util.function.Consumer;

public final class BagInventory extends ComponentInventory {
    // ItemStack retains Object identity equality. Weak values are essential: each handle owns its key.
    private static final Map<ItemStack, WeakReference<BagInventory>> HANDLES = new WeakHashMap<>();
    private final BackpackTier tier;
    private final ComponentInventory upgrades;
    private final Map<Integer, ComponentInventory> auxiliary = new HashMap<>();
    private Runnable changeListener;
    private boolean clientMirror;

    private BagInventory(ItemStack stack, boolean clientMirror) {
        super(stack, BagComponents.CONTENTS, resolvedSlots(stack, false, clientMirror), null);
        this.clientMirror = clientMirror;
        tier = BackpackRegistry.tier(stack).orElseThrow();
        int savedSlots = stack.getOrDefault(BagComponents.CONTENTS, InventorySnapshot.EMPTY).size();
        if (!clientMirror && savedSlots > 0 && savedSlots <= 81 && getContainerSize() > 81) widenSavedLayout(savedSlots);
        if (!stack.has(BagComponents.IDENTITY)) stack.set(BagComponents.IDENTITY, UUID.randomUUID().toString());
        upgrades = new ComponentInventory(stack, BagComponents.UPGRADES, resolvedSlots(stack, true, clientMirror), this::setChanged) {
            @Override public boolean canPlaceItem(int slot, ItemStack item) { return canInstall(slot, item); }
            @Override public boolean canTakeItem(Container target, int slot, ItemStack item) { return canRemoveUpgrade(slot); }
            @Override public int getMaxStackSize() { return 1; }
            @Override public int getMaxStackSize(ItemStack item) { return 1; }
        };
        // Empty dimensions must travel too: a client cannot infer a server's configured capacity.
        save();
    }

    private static int resolvedSlots(ItemStack stack, boolean upgrades, boolean client) {
        BackpackTier tier = BackpackRegistry.tier(stack).orElseThrow();
        var component = upgrades ? BagComponents.UPGRADES : BagComponents.CONTENTS;
        int saved = stack.getOrDefault(component, InventorySnapshot.EMPTY).size();
        if (client && stack.has(component)) return upgrades ? saved : Math.max(1, saved);
        int configured = upgrades ? BackpackConfig.get().capacity(tier).upgrades() : BackpackConfig.get().capacity(tier).slots();
        if (client) configured = upgrades ? tier.upgradeSlots() : tier.slots();
        int required = !upgrades && saved > 0 && saved <= 81 && configured > 81 ? Math.ceilDiv(saved, 9) * 12 : saved;
        return Math.max(required, configured);
    }

    /** Preserve row/column locations when a tier/config upgrade widens the grid from nine to twelve. */
    private void widenSavedLayout(int previousSlots) {
        List<ItemStack> previous = new ArrayList<>(getItems());
        getItems().replaceAll(ignored -> ItemStack.EMPTY);
        for (int slot = 0; slot < previousSlots; slot++) getItems().set(widenedSlot(slot), previous.get(slot));
        InventorySnapshot memory = owner.getOrDefault(BagComponents.MEMORY, InventorySnapshot.EMPTY);
        if (!memory.entries().isEmpty()) owner.set(BagComponents.MEMORY, new InventorySnapshot(getContainerSize(),
                memory.entries().stream().map(entry -> new InventorySnapshot.Entry(widenedSlot(entry.slot()), entry.item(), entry.count())).toList()));
        CustomData.update(BagComponents.SETTINGS, owner, tag -> {
            int displayed = tag.getIntOr("display_slot", -1);
            if (displayed >= 0) tag.putInt("display_slot", widenedSlot(displayed));
            for (String key : List.of("no_sort", "captured_slots")) tag.getIntArray(key)
                    .ifPresent(values -> tag.putIntArray(key, Arrays.stream(values).map(BagInventory::widenedSlot).toArray()));
            var captures = tag.getListOrEmpty("captured_entities");
            for (int index = 0; index < captures.size(); index++) {
                CompoundTag capture = captures.getCompoundOrEmpty(index);
                capture.getIntArray("slots").ifPresent(values -> capture.putIntArray("slots", Arrays.stream(values).map(BagInventory::widenedSlot).toArray()));
            }
            if (!captures.isEmpty()) tag.put("captured_entities", captures);
        });
    }
    private static int widenedSlot(int slot) { return slot / 9 * 12 + slot % 9; }

    /**
     * One live inventory per physical item object, shared by menus, automation and item interactions.
     * Copies intentionally get independent handles, even when their saved backpack UUID is equal:
     * transfer simulations and item-codec copies must not mutate the original inventory.
     */
    public static synchronized BagInventory of(ItemStack stack) {
        WeakReference<BagInventory> reference = HANDLES.get(stack);
        BagInventory existing = reference == null ? null : reference.get();
        if (existing != null) return existing;
        BagInventory created = new BagInventory(stack, false);
        HANDLES.put(stack, new WeakReference<>(created));
        return created;
    }
    /** Construct the menu's isolated client mirror from the server's exact serialized geometry. */
    public static BagInventory clientOf(ItemStack stack) { return new BagInventory(stack, true); }
    public void onChange(Runnable listener) { changeListener = listener; }
    /** Client menu copies must accept authoritative server corrections, including rejected seed predictions. */
    public void markClientMirror() { clientMirror = true; }
    @Override public void setChanged() { super.setChanged(); if (changeListener != null) changeListener.run(); }
    public ItemStack stack() { return owner; }
    public BackpackTier tier() { return tier; }
    public int columns() { return getContainerSize() <= 81 ? 9 : 12; }
    public int rows() { return Math.ceilDiv(getContainerSize(), columns()); }
    public String identity() { return owner.getOrDefault(BagComponents.IDENTITY, ""); }
    public Container upgrades() { return upgrades; }
    public boolean has(UpgradeKind kind) { return installedUpgrades().stream().anyMatch(u -> u.kind() == kind); }

    public UpgradeKind infinityKind() {
        return installedUpgrades().stream().map(InstalledUpgrade::kind)
                .filter(kind -> kind.family().equals("infinity")).findFirst().orElse(null);
    }

    public boolean isInfiniteSlot(int slot) {
        return slot >= 0 && slot < getContainerSize() && infinityKind() != null && !super.getItem(slot).isEmpty();
    }

    private static boolean gameMaster(Player player) {
        // Clients may predict a placement; only the authoritative server grants this permission.
        return player != null && (player.level().isClientSide() || player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER));
    }

    @Override public ItemStack getItem(int slot) {
        ItemStack stored = super.getItem(slot);
        // Vanilla menu/hopper code sometimes shrinks getItem() directly. Never expose a mutable seed.
        return !stored.isEmpty() && infinityKind() != null ? stored.copy() : stored;
    }

    @Override public void setItem(int slot, ItemStack item) {
        if (!clientMirror && isInfiniteSlot(slot)) return;
        super.setItem(slot, item);
    }

    @Override public ItemStack removeItem(int slot, int amount) {
        if (amount <= 0) return ItemStack.EMPTY;
        if (isInfiniteSlot(slot)) return super.getItem(slot).copyWithCount(amount);
        return super.removeItem(slot, amount);
    }

    @Override public ItemStack removeItemNoUpdate(int slot) {
        return isInfiniteSlot(slot) ? super.getItem(slot).copy() : super.removeItemNoUpdate(slot);
    }

    @Override public void clearContent() {
        if (clientMirror || infinityKind() == null) super.clearContent();
    }

    /** Trusted transaction rollback only; ordinary mutation must use the seed-protecting setters. */
    public void restoreStorageSlot(int slot, ItemStack previous) { super.setItem(slot, previous.copy()); }

    public List<InstalledUpgrade> installedUpgrades() {
        if (upgrades == null) return List.of();
        List<InstalledUpgrade> result = new ArrayList<>();
        for (int slot = 0; slot < upgrades.getContainerSize(); slot++) {
            ItemStack item = upgrades.getItem(slot);
            UpgradeKind kind = BackpackRegistry.kind(item).orElse(null);
            if (kind != null) result.add(new InstalledUpgrade(slot, kind, item));
        }
        return List.copyOf(result);
    }

    public Container upgradeInventory(InstalledUpgrade upgrade) {
        int size = inventorySlots(upgrade);
        ComponentInventory existing = auxiliary.get(upgrade.slot());
        if (existing != null && existing.owner == upgrade.stack() && existing.getContainerSize() == size) return existing;
        var inventory = new ComponentInventory(upgrade.stack(), BagComponents.CONTENTS,
                size, upgrades::setChanged) {
            @Override public boolean canPlaceItem(int slot, ItemStack item) { return !BackpackRegistry.isBackpack(item); }
            @Override public int getMaxStackSize(ItemStack item) { return upgrade.kind() == UpgradeKind.BATTERY ? 1 : item.getMaxStackSize(); }
        };
        auxiliary.put(upgrade.slot(), inventory);
        return inventory;
    }

    /** A smaller server default never discards physical records or persisted workstation inputs. */
    public int inventorySlots(InstalledUpgrade upgrade) {
        return Math.max(BackpackConfig.get().upgrades().inventorySlots(upgrade.kind()),
                upgrade.stack().getOrDefault(BagComponents.CONTENTS, InventorySnapshot.EMPTY).size());
    }
    public int inventoryColumns(InstalledUpgrade upgrade) {
        return upgrade.kind().family().equals("jukebox") ? BackpackConfig.get().upgrades().jukebox().rowWidth() : 4;
    }
    public int filterColumns(InstalledUpgrade upgrade) { return BackpackConfig.get().upgrades().filterColumns(upgrade.kind()); }
    public int filterSlots(InstalledUpgrade upgrade) {
        if (automaticCooking(upgrade)) return cookingInputFilters(upgrade) + cookingFuelFilters(upgrade);
        return Math.min(com.kadamitas.fabricatedbackpacks.config.UpgradeConfig.MAX_FILTERS,
                Math.max(BackpackConfig.get().upgrades().filterSlots(upgrade.kind()),
                        upgrade.stack().getOrDefault(BagComponents.FILTERS, InventorySnapshot.EMPTY).size()));
    }
    private static boolean automaticCooking(InstalledUpgrade upgrade) {
        return upgrade.kind().family().equals("cooking") && upgrade.kind().filterSlots() > 0;
    }
    private int savedCookingInputFilters(InstalledUpgrade upgrade) {
        if (upgrade.stack().getOrDefault(BagComponents.FILTERS, InventorySnapshot.EMPTY).size() == 0) {
            return BackpackConfig.get().upgrades().cooking().inputFilters();
        }
        return Math.clamp(settings(upgrade).getIntOr("cooking_input_filter_slots", 8), 1, 32);
    }
    private int savedCookingFuelFilters(InstalledUpgrade upgrade) {
        if (upgrade.stack().getOrDefault(BagComponents.FILTERS, InventorySnapshot.EMPTY).size() == 0) {
            return BackpackConfig.get().upgrades().cooking().fuelFilters();
        }
        return Math.clamp(settings(upgrade).getIntOr("cooking_fuel_filter_slots", 4), 1, 32);
    }
    public int cookingInputFilters(InstalledUpgrade upgrade) {
        return Math.max(BackpackConfig.get().upgrades().cooking().inputFilters(), savedCookingInputFilters(upgrade));
    }
    public int cookingFuelFilters(InstalledUpgrade upgrade) {
        return Math.max(BackpackConfig.get().upgrades().cooking().fuelFilters(), savedCookingFuelFilters(upgrade));
    }

    public CompoundTag settings() { return settingsOf(owner); }
    public CompoundTag settings(InstalledUpgrade upgrade) { return settingsOf(upgrade.stack()); }
    private static CompoundTag settingsOf(ItemStack stack) {
        return stack.getOrDefault(BagComponents.SETTINGS, CustomData.EMPTY).copyTag();
    }
    public void updateSettings(Consumer<CompoundTag> change) {
        CustomData.update(BagComponents.SETTINGS, owner, change);
        setChanged();
    }
    public void updateSettings(InstalledUpgrade upgrade, Consumer<CompoundTag> change) {
        CustomData.update(BagComponents.SETTINGS, upgrade.stack(), change);
        upgrades.setChanged();
    }
    public List<ItemStack> filterItems(InstalledUpgrade upgrade) {
        return upgrade.stack().getOrDefault(BagComponents.FILTERS, InventorySnapshot.EMPTY).items();
    }
    public List<ItemStack> memoryItems() {
        return owner.getOrDefault(BagComponents.MEMORY, InventorySnapshot.EMPTY).items();
    }
    public ItemStack ghost(InstalledUpgrade upgrade, int slot) {
        if (slot < 0 || slot >= filterSlots(upgrade)) return ItemStack.EMPTY;
        int stored = slot;
        if (automaticCooking(upgrade)) {
            int inputs = cookingInputFilters(upgrade), savedInputs = savedCookingInputFilters(upgrade);
            if (slot >= inputs) stored = slot - inputs + savedInputs;
            else if (slot >= savedInputs) return ItemStack.EMPTY;
        }
        final int savedSlot = stored;
        return upgrade.stack().getOrDefault(BagComponents.FILTERS, InventorySnapshot.EMPTY).entries().stream()
                .filter(entry -> entry.slot() == savedSlot).findFirst().map(InventorySnapshot.Entry::create).orElse(ItemStack.EMPTY);
    }
    public void setFilter(InstalledUpgrade upgrade, int slot, ItemStack exemplar) {
        if (slot < 0 || slot >= filterSlots(upgrade)) return;
        var old = upgrade.stack().getOrDefault(BagComponents.FILTERS, InventorySnapshot.EMPTY);
        List<InventorySnapshot.Entry> entries = new ArrayList<>(old.entries());
        int inputs = cookingInputFilters(upgrade), fuel = cookingFuelFilters(upgrade);
        if (automaticCooking(upgrade)) {
            int savedInputs = savedCookingInputFilters(upgrade);
            entries.replaceAll(entry -> entry.slot() < savedInputs ? entry
                    : new InventorySnapshot.Entry(entry.slot() - savedInputs + inputs, entry.item(), entry.count()));
        }
        if (!exemplar.isEmpty() && entries.stream().anyMatch(entry -> entry.slot() != slot
                && ItemStack.isSameItemSameComponents(entry.create(), exemplar))) return;
        entries.removeIf(entry -> entry.slot() == slot);
        if (!exemplar.isEmpty()) entries.add(new InventorySnapshot.Entry(slot,
                ItemStackTemplate.fromNonEmptyStack(exemplar.copyWithCount(1)), 1));
        upgrade.stack().set(BagComponents.FILTERS, new InventorySnapshot(Math.max(old.size(), filterSlots(upgrade)), entries));
        if (automaticCooking(upgrade)) {
            updateSettings(upgrade, state -> {
                state.putInt("cooking_input_filter_slots", inputs);
                state.putInt("cooking_fuel_filter_slots", fuel);
            });
        }
        if (upgrade.kind().family().equals("alchemy")) {
            if (exemplar.isEmpty()) {
                updateSettings(upgrade, state -> { state.remove("alchemy_condition_" + slot); state.remove("alchemy_health_" + slot); });
            } else if (old.entries().stream().noneMatch(entry -> entry.slot() == slot)) {
                updateSettings(upgrade, state -> state.putString("alchemy_condition_" + slot,
                        com.kadamitas.fabricatedbackpacks.upgrade.AlchemyRuntime.defaultCondition(exemplar).name()));
            }
        }
        upgrades.setChanged();
    }

    public double multiplier() { return BackpackConfig.get().upgrades().stack().multiplier(installedUpgrades().stream().map(InstalledUpgrade::kind).toList()); }
    private int itemCapacity(ItemStack item, double multiplier) {
        return BackpackRegistry.isBackpack(item) ? 1 : StackCapacity.itemLimit(item.getMaxStackSize(), multiplier,
                !com.kadamitas.fabricatedbackpacks.config.RuleMatchers.item(item, BackpackConfig.get().upgrades().stack().excludedItems()));
    }
    public int capacity(ItemStack item) { return itemCapacity(item, multiplier()); }
    @Override public int getMaxStackSize(ItemStack item) { return capacity(item); }
    @Override public boolean stillValid(Player player) { return !owner.isEmpty(); }

    public int reservedColumns() {
        return (int) installedUpgrades().stream().filter(upgrade -> upgrade.kind() == UpgradeKind.TANK
                || upgrade.kind() == UpgradeKind.BATTERY).count() * 2;
    }
    public boolean blocked(int slot) {
        int column = slot % columns();
        if (column >= columns() - reservedColumns()) return true;
        return Arrays.stream(settings().getIntArray("captured_slots").orElseGet(() -> new int[0])).anyMatch(index -> index == slot);
    }

    @Override public boolean canPlaceItem(int slot, ItemStack item) { return canPlaceItem(slot, item, null); }

    public boolean canPlaceItem(int slot, ItemStack item, Player actor) {
        if (item.isEmpty() || blocked(slot)) return false;
        var storageRules = BackpackConfig.get().storage();
        if (!clientMirror && com.kadamitas.fabricatedbackpacks.config.RuleMatchers.item(item, storageRules.disallowedItems())) return false;
        if (!clientMirror && storageRules.disallowContainerItems() && (item.has(DataComponents.CONTAINER) || item.has(DataComponents.BUNDLE_CONTENTS))) return false;
        if (isInfiniteSlot(slot) || infinityKind() == UpgradeKind.INFINITY && !gameMaster(actor)) return false;
        if (BackpackRegistry.isBackpack(item)) {
            if (!has(UpgradeKind.INCEPTION)) return false;
            if (identity().equals(item.getOrDefault(BagComponents.IDENTITY, ""))) return false;
            var children = item.getOrDefault(BagComponents.CONTENTS, InventorySnapshot.EMPTY).items();
            if (children.stream().anyMatch(BackpackRegistry::isBackpack)) return false;
            if (item.getOrDefault(BagComponents.UPGRADES, InventorySnapshot.EMPTY).items().stream()
                    .anyMatch(upgrade -> BackpackRegistry.kind(upgrade).orElse(null) == UpgradeKind.INCEPTION)) return false;
        }
        var remembered = owner.getOrDefault(BagComponents.MEMORY, InventorySnapshot.EMPTY).entries().stream()
                .filter(entry -> entry.slot() == slot).findFirst();
        if (remembered.isPresent()) {
            ItemStack memory = remembered.get().create();
            CompoundTag preferences = actor instanceof net.minecraft.server.level.ServerPlayer serverPlayer
                    ? com.kadamitas.fabricatedbackpacks.settings.SettingsRuntime.effective(this, serverPlayer) : settings();
            if (preferences.getBooleanOr("memory_components", false)
                    ? !ItemStack.isSameItemSameComponents(memory, item) : !ItemStack.isSameItem(memory, item)) return false;
        }
        return UpgradeEngine.acceptsInput(this, item);
    }

    @Override public boolean canTakeItem(Container target, int slot, ItemStack item) {
        return !blocked(slot) && UpgradeEngine.acceptsOutput(this, item);
    }

    public ItemStack insert(ItemStack supplied, boolean simulate) {
        return insert(supplied, simulate, null);
    }

    public ItemStack insert(ItemStack supplied, boolean simulate, Player actor) {
        if (supplied.isEmpty()) return ItemStack.EMPTY;
        ItemStack remainder = supplied.copy();
        for (int pass = 0; pass < 2 && !remainder.isEmpty(); pass++) {
            for (int slot = 0; slot < getContainerSize() && !remainder.isEmpty(); slot++) {
                ItemStack present = getItem(slot);
                if (present.isEmpty() != (pass == 1) || !canPlaceItem(slot, remainder, actor)) continue;
                if (!present.isEmpty() && !ItemStack.isSameItemSameComponents(present, remainder)) continue;
                int room = Math.max(0, capacity(remainder) - present.getCount());
                int moved = Math.min(room, remainder.getCount());
                if (moved == 0) continue;
                if (!simulate) setItem(slot, remainder.copyWithCount(present.getCount() + moved));
                remainder.shrink(moved);
            }
        }
        return remainder;
    }

    public boolean canInstall(int slot, ItemStack candidate) { return canInstall(slot, candidate, null); }

    public boolean canInstall(int slot, ItemStack candidate, Player actor) {
        UpgradeKind kind = BackpackRegistry.kind(candidate).orElse(null);
        if (kind == null || slot < 0 || slot >= upgrades.getContainerSize() || candidate.getCount() != 1) return false;
        UpgradeKind replaced = BackpackRegistry.kind(upgrades.getItem(slot)).orElse(null);
        if ((kind == UpgradeKind.INFINITY || replaced == UpgradeKind.INFINITY) && !gameMaster(actor)) return false;
        List<InstalledUpgrade> after = new ArrayList<>(installedUpgrades().stream().filter(u -> u.slot() != slot).toList());
        if (kind.family().equals("infinity") ? !after.isEmpty()
                : after.stream().anyMatch(upgrade -> upgrade.kind().family().equals("infinity"))) return false;
        long familyCount = after.stream().filter(u -> u.kind().family().equals(kind.family())).count();
        long itemCount = after.stream().filter(u -> u.kind() == kind).count();
        var limits = BackpackConfig.get().upgrades();
        if (familyCount >= limits.groupLimit(kind) || itemCount >= limits.itemLimit(kind)) return false;
        after.add(new InstalledUpgrade(slot, kind, candidate));
        return fits(after);
    }

    public boolean canRemoveUpgrade(int slot) { return canRemoveUpgrade(slot, null); }

    public boolean canRemoveUpgrade(int slot, Player actor) {
        UpgradeKind removed = BackpackRegistry.kind(upgrades.getItem(slot)).orElse(null);
        if (removed == null) return true;
        if (removed == UpgradeKind.INFINITY && !gameMaster(actor)) return false;
        if (removed == UpgradeKind.INCEPTION && getItems().stream().anyMatch(BackpackRegistry::isBackpack)) return false;
        if (removed.family().equals("mob_catcher") && settings().contains("captured_entities")) return false;
        return fits(installedUpgrades().stream().filter(u -> u.slot() != slot).toList());
    }

    private boolean fits(List<InstalledUpgrade> after) {
        double nextMultiplier = BackpackConfig.get().upgrades().stack().multiplier(after.stream().map(InstalledUpgrade::kind).toList());
        int columns = (int) after.stream().filter(u -> u.kind() == UpgradeKind.TANK || u.kind() == UpgradeKind.BATTERY).count() * 2;
        for (int slot = 0; slot < getContainerSize(); slot++) {
            ItemStack item = getItem(slot);
            if (!item.isEmpty() && (slot % columns() >= columns() - columns
                    || item.getCount() > itemCapacity(item, nextMultiplier))) return false;
        }
        for (InstalledUpgrade upgrade : after) {
            long amount = settings(upgrade).getLongOr("amount", 0);
            long limit = upgrade.kind() == UpgradeKind.TANK ? BackpackConfig.get().upgrades().tank().capacity(rows(), nextMultiplier)
                    : upgrade.kind() == UpgradeKind.BATTERY ? BackpackConfig.get().upgrades().battery().capacity(rows(), nextMultiplier) : Long.MAX_VALUE;
            if (amount > limit || amount == limit && settings(upgrade).getLongOr("amount_droplets", 0) > 0) return false;
        }
        return true;
    }

    public void remember(int slot, ItemStack item) {
        if (slot < 0 || slot >= getContainerSize()) return;
        List<InventorySnapshot.Entry> entries = new ArrayList<>(owner.getOrDefault(BagComponents.MEMORY, InventorySnapshot.EMPTY).entries());
        entries.removeIf(entry -> entry.slot() == slot);
        if (!item.isEmpty()) entries.add(new InventorySnapshot.Entry(slot,
                ItemStackTemplate.fromNonEmptyStack(item.copyWithCount(1)), 1));
        owner.set(BagComponents.MEMORY, new InventorySnapshot(getContainerSize(), entries));
        save();
    }

    public void toggleNoSort(int slot) {
        if (slot < 0 || slot >= getContainerSize()) return;
        updateSettings(tag -> {
            Set<Integer> locked = new HashSet<>();
            for (int old : tag.getIntArray("no_sort").orElseGet(() -> new int[0])) locked.add(old);
            if (!locked.add(slot)) locked.remove(slot);
            tag.putIntArray("no_sort", locked.stream().sorted().mapToInt(Integer::intValue).toArray());
        });
    }

    public void sort(String order) { sort(order, null); }

    public void sort(String order, Player actor) {
        if (infinityKind() != null) return;
        Set<Integer> excluded = new HashSet<>();
        for (int slot : settings().getIntArray("no_sort").orElseGet(() -> new int[0])) excluded.add(slot);
        Map<Integer, ItemStack> memories = new HashMap<>();
        for (var entry : owner.getOrDefault(BagComponents.MEMORY, InventorySnapshot.EMPTY).entries()) {
            if (entry.slot() >= 0 && entry.slot() < getContainerSize()) memories.putIfAbsent(entry.slot(), entry.create());
        }
        boolean components = (actor instanceof net.minecraft.server.level.ServerPlayer player
                ? com.kadamitas.fabricatedbackpacks.settings.SettingsRuntime.effective(this, player) : settings())
                .getBooleanOr("memory_components", false);
        List<Integer> memorySlots = new ArrayList<>();
        List<Integer> ordinarySlots = new ArrayList<>();
        List<Integer> mutableSlots = new ArrayList<>();
        List<ItemStack> before = getItems().stream().map(ItemStack::copy).toList();
        List<ItemStack> planned = new ArrayList<>(before);
        List<ItemStack> collected = new ArrayList<>();
        for (int slot = 0; slot < getContainerSize(); slot++) {
            if (excluded.contains(slot) || blocked(slot)) continue;
            mutableSlots.add(slot);
            if (memories.containsKey(slot)) memorySlots.add(slot); else ordinarySlots.add(slot);
            planned.set(slot, ItemStack.EMPTY);
            if (!getItem(slot).isEmpty()) collected.add(getItem(slot).copy());
        }
        Comparator<ItemStack> comparator = switch (order) {
            case "count" -> Comparator.<ItemStack>comparingInt(ItemStack::getCount).reversed();
            case "mod" -> Comparator.comparing(item -> net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item.getItem()).toString());
            case "tags" -> Comparator.comparing(item -> item.typeHolder().tags().map(tag -> tag.location().toString()).sorted().findFirst().orElse(""));
            default -> Comparator.comparing(item -> item.getHoverName().getString(), String.CASE_INSENSITIVE_ORDER);
        };
        comparator = comparator.thenComparing(item -> net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item.getItem()).toString());
        collected.sort(comparator);
        // Reservations are destinations, not exclusions. Existing variants have first choice
        // when item-only memory permits several different component sets.
        for (int slot : memorySlots) {
            ItemStack template = sortMemoryTemplate(memories.get(slot), before.get(slot), collected, components);
            if (template.isEmpty()) continue;
            int limit = capacity(template);
            int amount = 0;
            for (ItemStack source : collected) {
                if (source.isEmpty() || !ItemStack.isSameItemSameComponents(source, template)) continue;
                int moved = Math.min(source.getCount(), limit - amount);
                source.shrink(moved);
                amount += moved;
                if (amount == limit) break;
            }
            if (amount > 0) planned.set(slot, template.copyWithCount(amount));
        }
        List<ItemStack> packed = new ArrayList<>();
        for (ItemStack source : collected) {
            if (source.isEmpty()) continue;
            for (ItemStack destination : packed) {
                if (!ItemStack.isSameItemSameComponents(source, destination)) continue;
                int moved = Math.min(source.getCount(), Math.max(0, capacity(source) - destination.getCount()));
                destination.grow(moved);
                source.shrink(moved);
                if (source.isEmpty()) break;
            }
            while (!source.isEmpty()) {
                // A newly changed reservation or server capacity can make sorting impossible.
                // Nothing is written until every physical item has a legal destination.
                if (packed.size() >= ordinarySlots.size()) return;
                int moved = Math.min(source.getCount(), capacity(source));
                packed.add(source.copyWithCount(moved));
                source.shrink(moved);
            }
        }
        packed.sort(comparator);
        for (int index = 0; index < packed.size(); index++) planned.set(ordinarySlots.get(index), packed.get(index));
        boolean changed = false;
        for (int slot : mutableSlots) if (!ItemStack.matches(getItem(slot), planned.get(slot))) {
            getItems().set(slot, planned.get(slot));
            changed = true;
        }
        if (changed) save();
    }

    private static ItemStack sortMemoryTemplate(ItemStack remembered, ItemStack previous, List<ItemStack> available, boolean components) {
        if (!previous.isEmpty() && sortMemoryMatches(remembered, previous, components)) {
            for (ItemStack candidate : available) if (!candidate.isEmpty() && ItemStack.isSameItemSameComponents(previous, candidate))
                return candidate.copyWithCount(1);
        }
        for (ItemStack candidate : available) if (!candidate.isEmpty() && sortMemoryMatches(remembered, candidate, components))
            return candidate.copyWithCount(1);
        return ItemStack.EMPTY;
    }

    private static boolean sortMemoryMatches(ItemStack remembered, ItemStack candidate, boolean components) {
        return components ? ItemStack.isSameItemSameComponents(remembered, candidate) : ItemStack.isSameItem(remembered, candidate);
    }

    public void dye(int body, int trim) {
        CustomModelData old = owner.getOrDefault(DataComponents.CUSTOM_MODEL_DATA, CustomModelData.EMPTY);
        owner.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(old.floats(), old.flags(), old.strings(), List.of(body & 0xffffff, trim & 0xffffff)));
        save();
    }
    public void save() { setChanged(); upgrades.setChanged(); }
}
