package com.kadamitas.fabricatedbackpacks.menu;

import com.kadamitas.fabricatedbackpacks.compat.NbtAccess;

import com.kadamitas.fabricatedbackpacks.storage.BagComponents;
import com.kadamitas.fabricatedbackpacks.gameplay.BackpackTraversal;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.storage.InstalledUpgrade;
import com.kadamitas.fabricatedbackpacks.upgrade.InventoryMoves;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.ResultContainer;
import com.kadamitas.fabricatedbackpacks.mixin.CraftingMenuAccess;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.StonecutterRecipe;

import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;
import java.util.function.BiConsumer;

/** Reuses vanilla workstation rules, costs and recipe outputs with persistent backpack input grids. */
public final class WorkstationMenus {
    public static final int DESTINATION_BUTTON = 10_000;
    public static final int REFILL_BUTTON = 10_001;
    public static final int PREVIOUS_RECIPE_BUTTON = 10_002;
    public static final int NEXT_RECIPE_BUTTON = 10_003;
    public static final int RECENT_RECIPE_BUTTON = 10_010;
    public static final int CHOICE_RECIPE_BUTTON = 11_000;
    public static final int MAX_CHOICES = 1_024;
    private static final ResourceLocation STONECUTTING = ResourceLocation.withDefaultNamespace("stonecutting");
    private static BiConsumer<ServerPlayer, CompoundTag> stateListener = (player, state) -> {};
    private WorkstationMenus() {}
    public static void setStateListener(BiConsumer<ServerPlayer, CompoundTag> listener) { stateListener = java.util.Objects.requireNonNull(listener); }
    public static void open(ServerPlayer player, BackpackMenu origin) {
        InstalledUpgrade upgrade = origin.selected().orElse(null);
        if (upgrade == null || !origin.stillValid(player) || !BackpackMenu.isWorkstation(upgrade.kind())) return;
        Session session = new Session(origin, upgrade);
        session.retain();
        boolean opened = false;
        try {
            opened = player.openMenu(new SimpleMenuProvider((id, inventory, viewer) -> switch (upgrade.kind()) {
                case CRAFTING -> new PortableCrafting(id, inventory, session);
                case STONECUTTER -> new PortableStonecutter(id, inventory, session);
                case ANVIL -> new PortableAnvil(id, inventory, session);
                case SMITHING -> new PortableSmithing(id, inventory, session);
                default -> throw new IllegalStateException("Not a workstation");
            }, upgrade.stack().getHoverName())).isPresent();
        } finally {
            if (!opened) session.release();
        }
        publish(player);
    }

    /** The parent lease must be retained before replacing an open portable workstation. */
    public static BackpackMenu origin(AbstractContainerMenu menu) {
        Session session = session(menu);
        return session == null ? null : session.origin;
    }

    /** Resolve the requested identity against the current input and registry, never a stale client index. */
    public static boolean selectRecipe(ServerPlayer player, ResourceLocation recipeId) {
        Session session = session(player.containerMenu);
        if (recipeId == null || session == null || !session.valid(player)) return false;
        if (player.containerMenu instanceof PortableCrafting crafting) {
            crafting.refreshResult();
            if (crafting.matches.stream().noneMatch(recipe -> recipe.id().equals(recipeId))) return false;
            session.bag().updateSettings(session.upgrade, state -> state.putString("selected_recipe_id", recipeId.toString()));
            crafting.refreshResult();
            session.persist(crafting.grid());
            return true;
        }
        if (player.containerMenu instanceof PortableStonecutter stonecutter) {
            if (stonecutter.choices().stream().noneMatch(recipe -> recipe.id().equals(recipeId))) return false;
            for (int index = 0; index < stonecutter.getNumRecipes(); index++) {
                if (stonecutter.getRecipes().get(index).id().equals(recipeId)) {
                    // Vanilla reports no change for a repeated button; the validated identity is still selected.
                    return stonecutter.getSelectedRecipeIndex() == index || stonecutter.clickMenuButton(player, index);
                }
            }
        }
        return false;
    }

    /** Persist a still-valid leased input grid after a nested backpack mutates its physical slot. */
    public static boolean persistInputs(AbstractContainerMenu menu) {
        Session session = session(menu);
        if (session == null || session.inputs == null) return false;
        session.persist(session.inputs);
        return true;
    }

    private static Session session(AbstractContainerMenu menu) {
        return switch (menu) {
            case PortableCrafting crafting -> crafting.session;
            case PortableStonecutter stonecutter -> stonecutter.session;
            case PortableAnvil anvil -> anvil.session;
            case PortableSmithing smithing -> smithing.session;
            default -> null;
        };
    }

    /** Only the currently valid native workstation may publish state to its actual viewer. */
    public static CompoundTag view(ServerPlayer player) {
        Session session = session(player.containerMenu);
        CompoundTag state = new CompoundTag();
        if (session == null || !session.valid(player)) return state;
        CompoundTag settings = session.bag().settings(session.upgrade);
        state.putString("family", session.upgrade.kind().family());
        state.putString("result_destination", NbtAccess.getStringOr(settings, "result_destination", "STORAGE"));
        state.putBoolean("grid_refill", NbtAccess.getBooleanOr(settings, "grid_refill", false));
        state.putString("selected_recipe_id", NbtAccess.getStringOr(settings, "selected_recipe_id", ""));
        List<ItemStack> results = new ArrayList<>();
        if (player.containerMenu instanceof PortableCrafting crafting) {
            var choices = crafting.matches.stream().limit(MAX_CHOICES).toList();
            state.putString("choices", String.join(",", choices.stream().map(recipe -> recipe.id().toString()).toList()));
            CraftingInput input = crafting.currentInput();
            choices.forEach(recipe -> results.add(recipe.value().assemble(input, player.registryAccess())));
        } else if (player.containerMenu instanceof PortableStonecutter stonecutter) {
            var choices = stonecutter.choices();
            state.putString("choices", String.join(",", choices.stream().map(recipe -> recipe.id().toString()).toList()));
            SingleRecipeInput input = new SingleRecipeInput(session.inputs.getItem(0));
            choices.forEach(recipe -> results.add(recipe.value().assemble(input, player.registryAccess())));
            state.putString("recent_recipes", String.join(",", stonecutter.recents(player).stream().map(ResourceLocation::toString).toList()));
        }
        ListTag encoded = new ListTag();
        var ops = RegistryOps.create(NbtOps.INSTANCE, player.level().registryAccess());
        results.forEach(result -> encoded.add(ItemStack.OPTIONAL_CODEC.encodeStart(ops, result).getOrThrow()));
        state.put("choice_results", encoded);
        return state;
    }
    private static void publish(Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            CompoundTag state = view(serverPlayer);
            if (!state.isEmpty()) stateListener.accept(serverPlayer, state);
        }
    }

    private static final class Session {
        final BackpackMenu origin;
        final InstalledUpgrade upgrade;
        Container inputs;
        boolean loading;
        boolean retained;
        Session(BackpackMenu origin, InstalledUpgrade upgrade) { this.origin = origin; this.upgrade = upgrade; }
        void retain() { if (!retained) { origin.retainView(); retained = true; } }
        void release() { if (retained) { retained = false; origin.releaseView(); } }
        BagInventory bag() { return origin.bag(); }
        boolean valid(Player player) {
            return origin.stillValid(player) && bag().upgrades().getItem(upgrade.slot()) == upgrade.stack();
        }
        void load(Container inputs) {
            this.inputs = inputs;
            loading = true;
            Container saved = bag().upgradeInventory(upgrade);
            List<ItemStack> snapshot = snapshot(saved);
            for (int slot = 0; slot < inputs.getContainerSize(); slot++) inputs.setItem(slot, snapshot.get(slot).copy());
            loading = false;
        }
        void persist(Container inputs) {
            if (loading) return;
            Container saved = bag().upgradeInventory(upgrade);
            for (int slot = 0; slot < saved.getContainerSize(); slot++) saved.setItem(slot, inputs.getItem(slot).copy());
            origin.persist();
        }
        boolean mayClick(AbstractContainerMenu menu, int slot, int button, ClickType input, Player player) {
            if (!valid(player) || slot >= menu.slots.size() || slot < -999) return false;
            if (slot >= 0 && slot < menu.slots.size() && isOpenBag(menu.slots.get(slot).getItem())) return false;
            return input != ClickType.SWAP || button < 0 || button >= player.getInventory().getContainerSize()
                    || !isOpenBag(player.getInventory().getItem(button));
        }
        boolean isOpenBag(ItemStack stack) { return bag().identity().equals(stack.getOrDefault(BagComponents.IDENTITY, "")); }
        void close(Container inputs) { persist(inputs); release(); }
        boolean button(Player player, int button) {
            if (!valid(player)) return false;
            if (button == DESTINATION_BUTTON) {
                boolean storage = NbtAccess.getStringOr(bag().settings(upgrade), "result_destination", "STORAGE").equals("STORAGE");
                bag().updateSettings(upgrade, state -> state.putString("result_destination", storage ? "PLAYER" : "STORAGE"));
            } else if (button == REFILL_BUTTON && (upgrade.kind().family().equals("crafting") || upgrade.kind().family().equals("stonecutter"))) {
                boolean refill = NbtAccess.getBooleanOr(bag().settings(upgrade), "grid_refill", false);
                bag().updateSettings(upgrade, state -> state.putBoolean("grid_refill", !refill));
            } else return false;
            origin.persist();
            publish(player);
            return true;
        }

        ItemStack shiftResult(AbstractContainerMenu menu, int resultIndex, Player player) {
            if (!valid(player)) return ItemStack.EMPTY;
            Slot resultSlot = menu.slots.get(resultIndex);
            if (!resultSlot.hasItem() || !resultSlot.mayPickup(player)) return ItemStack.EMPTY;
            ItemStack output = resultSlot.getItem().copy();
            Container destination = NbtAccess.getStringOr(bag().settings(upgrade), "result_destination", "STORAGE").equals("PLAYER")
                    ? player.getInventory() : BackpackTraversal.processingInventory(bag(), player);
            if (!InventoryMoves.insert(destination, output, true).isEmpty()) return ItemStack.EMPTY;
            ItemStack taken = resultSlot.remove(output.getCount());
            if (taken.isEmpty()) return ItemStack.EMPTY;
            ItemStack remainder = InventoryMoves.insert(destination, taken, false);
            // A result callback may alter inventories, but every item still has an explicit owner.
            if (!remainder.isEmpty()) player.getInventory().placeItemBackInInventory(remainder);
            resultSlot.onTake(player, taken);
            menu.broadcastChanges();
            publish(player);
            return output;
        }

        void shiftBatches(AbstractContainerMenu menu, int resultIndex, Player player) {
            ItemStack first = menu.getSlot(resultIndex).getItem().copy();
            for (int batch = 0; batch < 64 && !first.isEmpty(); batch++) {
                if (!ItemStack.isSameItemSameComponents(first, menu.getSlot(resultIndex).getItem())
                        || menu.quickMoveStack(player, resultIndex).isEmpty()) break;
            }
        }

        /** Exact component refill; an obstructing remainder is stashed only after a full plan succeeds. */
        void refill(Container inputs, List<ItemStack> before, Player player) {
            if (!NbtAccess.getBooleanOr(bag().settings(upgrade), "grid_refill", false)) return;
            Container processing = BackpackTraversal.processingInventory(bag(), player);
            List<ItemStack> storage = snapshot(processing);
            List<ItemStack> inventory = snapshot(player.getInventory());
            List<ItemStack> grid = snapshot(inputs);
            boolean changed = false;
            for (int slot = 0; slot < grid.size(); slot++) {
                ItemStack wanted = before.get(slot);
                ItemStack present = grid.get(slot);
                if (wanted.isEmpty() || isOpenBag(wanted)) continue;
                boolean matching = ItemStack.isSameItemSameComponents(wanted, present);
                int count = matching ? present.getCount() : 0;
                int missing = Math.min(wanted.getCount(), wanted.getMaxStackSize()) - count;
                if (missing <= 0) continue;
                List<ItemStack> nextStorage = copy(storage), nextInventory = copy(inventory);
                int moved = takeMatching(nextStorage, wanted, missing, player, processing);
                moved += takeMatching(nextInventory, wanted, missing - moved, player, player.getInventory());
                if (moved == 0) continue;
                if (!matching && !present.isEmpty()) {
                    ItemStack remainder = InventoryMoves.insertIntoPlan(processing, nextStorage, present, false);
                    remainder = insertCopy(nextInventory, remainder, (index, item) -> index < Inventory.INVENTORY_SIZE, ItemStack::getMaxStackSize);
                    if (!remainder.isEmpty()) continue;
                }
                storage = nextStorage;
                inventory = nextInventory;
                grid.set(slot, wanted.copyWithCount(count + moved));
                changed = true;
            }
            if (changed) {
                // Player snapshots include the physical bag. Commit them before changing that
                // bag's components so an unchanged owner cannot be replaced by an older copy.
                InventoryMoves.commit(player.getInventory(), inventory);
                InventoryMoves.commit(processing, storage);
                InventoryMoves.commit(inputs, grid);
            }
        }
        private int takeMatching(List<ItemStack> source, ItemStack wanted, int needed, Player player, Container inventory) {
            int left = needed;
            for (int slot = 0; slot < source.size() && left > 0; slot++) {
                if (inventory instanceof Inventory && slot >= Inventory.INVENTORY_SIZE) break;
                ItemStack candidate = source.get(slot);
                if (isOpenBag(candidate) || !ItemStack.isSameItemSameComponents(wanted, candidate)) continue;
                if (!inventory.canTakeItem(player.getInventory(), slot, candidate)) continue;
                int taken = Math.min(left, candidate.getCount());
                candidate.shrink(taken);
                left -= taken;
            }
            return needed - left;
        }
    }

    public static final class PortableCrafting extends CraftingMenu implements BackpackSessionMenu {
        private CraftingContainer craftSlots() { return ((CraftingMenuAccess) (Object) this).fabricated$craftSlots(); }
        private ResultContainer resultSlots() { return ((CraftingMenuAccess) (Object) this).fabricated$resultSlots(); }
        private final Session session;
        private final Player owner;
        private boolean bulk;
        private List<RecipeHolder<CraftingRecipe>> matches = List.of();
        PortableCrafting(int id, Inventory inventory, Session session) {
            super(id, inventory, ContainerLevelAccess.NULL);
            this.session = session;
            owner = inventory.player;
            session.load(craftSlots());
            slotsChanged(craftSlots());
        }
        @Override public BagInventory backpack() { return session.bag(); }
        public Container grid() { return craftSlots(); }
        private CraftingInput currentInput() { return craftSlots().asCraftInput(); }
        @Override public boolean stillValid(Player player) { return session.valid(player); }
        @Override public void slotsChanged(Container container) {
            if (session == null || session.loading || bulk) return;
            refreshResult();
            session.persist(craftSlots());
        }
        private void refreshResult() {
            if (!(owner instanceof ServerPlayer player)) return;
            CraftingInput input = craftSlots().asCraftInput();
            List<RecipeHolder<CraftingRecipe>> available = new ArrayList<>();
            for (RecipeHolder<?> entry : player.level().getRecipeManager().getRecipes()) {
                if (entry.value() instanceof CraftingRecipe recipe && recipe.matches(input, player.level())
                        && recipe.assemble(input, player.registryAccess()).isItemEnabled(player.level().enabledFeatures())
                        && (recipe.isSpecial() || !player.level().getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_LIMITED_CRAFTING)
                        || player.getRecipeBook().contains(entry.id()))) {
                    @SuppressWarnings("unchecked") RecipeHolder<CraftingRecipe> typed = (RecipeHolder<CraftingRecipe>) (RecipeHolder<?>) entry;
                    available.add(typed);
                }
            }
            available.sort(Comparator.comparing(recipe -> recipe.id().toString()));
            matches = List.copyOf(available);
            String preferred = NbtAccess.getStringOr(session.bag().settings(session.upgrade), "selected_recipe_id", "");
            RecipeHolder<CraftingRecipe> selected = matches.stream().filter(recipe -> recipe.id().toString().equals(preferred))
                    .findFirst().orElse(matches.isEmpty() ? null : matches.getFirst());
            if (selected != null && !selected.id().toString().equals(preferred)) {
                session.bag().updateSettings(session.upgrade, state -> state.putString("selected_recipe_id", selected.id().toString()));
            }
            slotChangedCraftingGrid(this, player.level(), player, craftSlots(), resultSlots(), selected);
            publish(player);
        }
        private RecipeHolder<CraftingRecipe> chosenRecipe() {
            if (!(owner instanceof ServerPlayer player)) return null;
            ResourceLocation id = ResourceLocation.tryParse(NbtAccess.getStringOr(session.bag().settings(session.upgrade), "selected_recipe_id", ""));
            if (id == null) return null;
            RecipeHolder<?> entry = player.level().getRecipeManager().byKey(id).orElse(null);
            if (entry == null || !(entry.value() instanceof CraftingRecipe recipe) || !recipe.matches(craftSlots().asCraftInput(), player.level())
                    || !recipe.isSpecial() && player.level().getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_LIMITED_CRAFTING)
                    && !player.getRecipeBook().contains(entry.id())) return null;
            @SuppressWarnings("unchecked") RecipeHolder<CraftingRecipe> typed = (RecipeHolder<CraftingRecipe>) (RecipeHolder<?>) entry;
            return typed;
        }
        @Override protected Slot addSlot(Slot original) {
            if (!slots.isEmpty() || !(original instanceof ResultSlot)) return super.addSlot(original);
            Player player = ((CraftingMenuAccess) (Object) this).fabricated$player();
            return super.addSlot(new ResultSlot(player, craftSlots(), resultSlots(), 0, original.x, original.y) {
                @Override public boolean mayPickup(Player actor) {
                    RecipeHolder<CraftingRecipe> recipe = session == null ? null : chosenRecipe();
                    return session != null && session.valid(actor) && recipe != null
                            && ItemStack.matches(getItem(), recipe.value().assemble(craftSlots().asCraftInput(), actor.registryAccess()));
                }
                @Override public void onTake(Player actor, ItemStack carried) {
                    RecipeHolder<CraftingRecipe> recipe = chosenRecipe();
                    if (recipe == null) throw new IllegalStateException("A crafting result lost its validated recipe during a synchronous take");
                    List<ItemStack> before = snapshot(craftSlots());
                    var positioned = craftSlots().asPositionedCraftInput();
                    CraftingInput input = positioned.input();
                    NonNullList<ItemStack> remainders = recipe.value().getRemainingItems(input);
                    List<ItemStack> grid = copy(before);
                    List<ItemStack> inventory = snapshot(actor.getInventory());
                    List<ItemStack> drops = new ArrayList<>();
                    for (int index = 0; index < input.size(); index++) {
                        int slot = positioned.left() + index % input.width() + (positioned.top() + index / input.width()) * 3;
                        ItemStack left = grid.get(slot);
                        if (!left.isEmpty()) left.shrink(1);
                        ItemStack remainder = remainders.get(index).copy();
                        if (!remainder.isEmpty() && (left.isEmpty() || ItemStack.isSameItemSameComponents(left, remainder))) {
                            int moved = Math.min(remainder.getCount(), Math.max(0, remainder.getMaxStackSize() - left.getCount()));
                            grid.set(slot, remainder.copyWithCount(left.getCount() + moved));
                            remainder.shrink(moved);
                        }
                        remainder = insertCopy(inventory, remainder, (cell, item) -> cell < Inventory.INVENTORY_SIZE, ItemStack::getMaxStackSize);
                        if (!remainder.isEmpty()) drops.add(remainder);
                    }
                    checkTakeAchievements(carried);
                    bulk = true;
                    try {
                        InventoryMoves.commit(actor.getInventory(), inventory);
                        InventoryMoves.commit(craftSlots(), grid);
                        session.refill(craftSlots(), before, actor);
                    } finally { bulk = false; }
                    slotsChanged(craftSlots());
                    for (ItemStack drop : drops) actor.drop(drop, false);
                    publish(actor);
                }
            });
        }
        @Override public void beginPlacingRecipe() { bulk = true; super.beginPlacingRecipe(); }
        @Override public void finishPlacingRecipe(RecipeHolder<CraftingRecipe> recipe) {
            bulk = false;
            super.finishPlacingRecipe(recipe);
            session.bag().updateSettings(session.upgrade, state -> state.putString("selected_recipe_id", recipe.id().toString()));
            slotsChanged(craftSlots());
        }
        @Override public boolean clickMenuButton(Player player, int button) {
            if (!session.valid(player)) return false;
            if (session.button(player, button)) return true;
            if (button >= CHOICE_RECIPE_BUTTON && button < CHOICE_RECIPE_BUTTON + MAX_CHOICES) {
                refreshResult();
                int index = button - CHOICE_RECIPE_BUTTON;
                if (index >= matches.size()) return false;
                String selected = matches.get(index).id().toString();
                session.bag().updateSettings(session.upgrade, state -> state.putString("selected_recipe_id", selected));
                refreshResult();
                session.persist(craftSlots());
                return true;
            }
            if (button != PREVIOUS_RECIPE_BUTTON && button != NEXT_RECIPE_BUTTON) return false;
            refreshResult();
            if (matches.size() < 2) return false;
            String selected = NbtAccess.getStringOr(session.bag().settings(session.upgrade), "selected_recipe_id", "");
            int current = 0;
            for (int index = 0; index < matches.size(); index++) if (matches.get(index).id().toString().equals(selected)) current = index;
            int next = Math.floorMod(current + (button == NEXT_RECIPE_BUTTON ? 1 : -1), matches.size());
            session.bag().updateSettings(session.upgrade, state -> state.putString("selected_recipe_id", matches.get(next).id().toString()));
            refreshResult();
            session.persist(craftSlots());
            return true;
        }
        @Override public void clicked(int slot, int button, ClickType input, Player player) {
            if (!session.mayClick(this, slot, button, input, player)) return;
            if (slot == RESULT_SLOT && input == ClickType.QUICK_MOVE) session.shiftBatches(this, RESULT_SLOT, player);
            else super.clicked(slot, button, input, player);
            session.persist(craftSlots());
        }
        @Override public ItemStack quickMoveStack(Player player, int slot) {
            if (!session.valid(player) || slot < 0 || slot >= slots.size() || session.isOpenBag(slots.get(slot).getItem())) return ItemStack.EMPTY;
            ItemStack result = slot == RESULT_SLOT ? session.shiftResult(this, RESULT_SLOT, player) : super.quickMoveStack(player, slot);
            session.persist(craftSlots());
            return result;
        }
        @Override public void removed(Player player) { session.close(craftSlots()); super.removed(player); }
    }

    private static final class PortableStonecutter extends StonecutterMenu implements BackpackSessionMenu {
        private final Session session;
        private final Player owner;
        private boolean refilling;
        PortableStonecutter(int id, Inventory inventory, Session session) {
            super(id, inventory, ContainerLevelAccess.NULL);
            this.session = session;
            owner = inventory.player;
            session.load(container);
            if (!restoreSelection()) {
                int selected = NbtAccess.getIntOr(session.bag().settings(session.upgrade), "selected_recipe", -1);
                if (NbtAccess.getStringOr(session.bag().settings(session.upgrade), "selected_recipe_id", "").isEmpty() && selected >= 0) clickMenuButton(inventory.player, selected);
            }
        }
        @Override public BagInventory backpack() { return session.bag(); }
        @Override public boolean stillValid(Player player) { return session.valid(player); }
        @Override protected Slot addSlot(Slot original) {
            if (slots.size() != RESULT_SLOT) return super.addSlot(original);
            return super.addSlot(new Slot(original.container, original.getContainerSlot(), original.x, original.y) {
                @Override public boolean mayPlace(ItemStack stack) { return false; }
                @Override public boolean mayPickup(Player player) { return session != null && session.valid(player) && original.mayPickup(player); }
                @Override public void onTake(Player player, ItemStack output) {
                    List<ItemStack> before = snapshot(PortableStonecutter.this.container);
                    ResourceLocation selected = selectedId();
                    original.onTake(player, output);
                    refilling = true;
                    try { session.refill(PortableStonecutter.this.container, before, player); }
                    finally { refilling = false; }
                    restoreSelection();
                    session.persist(PortableStonecutter.this.container);
                    if (selected != null && player instanceof ServerPlayer serverPlayer) {
                        WorkstationHistory.get(serverPlayer).remember(serverPlayer, STONECUTTING, before.getFirst(), selected, output);
                    }
                    publish(player);
                }
            });
        }
        @Override public void slotsChanged(Container changed) {
            super.slotsChanged(changed);
            if (session != null && !session.loading && !refilling) {
                restoreSelection();
                session.persist(container);
                publish(owner);
            }
        }
        private ResourceLocation selectedId() {
            int index = getSelectedRecipeIndex();
            if (index < 0 || index >= getNumRecipes()) return null;
            return getRecipes().get(index).id();
        }
        private List<ResourceLocation> recents(ServerPlayer player) {
            return WorkstationHistory.get(player).recipes(player, STONECUTTING, container.getItem(0)).stream()
                    .filter(id -> getRecipes().stream().anyMatch(entry -> entry.id().equals(id)))
                    .toList();
        }
        private List<RecipeHolder<StonecutterRecipe>> choices() {
            if (!(owner instanceof ServerPlayer player)) return List.of();
            SingleRecipeInput input = new SingleRecipeInput(container.getItem(0));
            return getRecipes().stream()
                    .filter(recipe -> player.level().getRecipeManager().byKey(recipe.id()).map(current -> current.value() == recipe.value()).orElse(false))
                    .filter(recipe -> recipe.value().matches(input, player.level()) && recipe.value().assemble(input, player.registryAccess()).isItemEnabled(player.level().enabledFeatures()))
                    .limit(MAX_CHOICES).toList();
        }
        private boolean restoreSelection() {
            String selected = NbtAccess.getStringOr(session.bag().settings(session.upgrade), "selected_recipe_id", "");
            for (int index = 0; index < getNumRecipes(); index++) {
                var recipe = getRecipes().get(index);
                if (recipe.id().toString().equals(selected)) {
                    if (getSelectedRecipeIndex() != index) super.clickMenuButton(owner, index);
                    return true;
                }
            }
            return false;
        }
        @Override public boolean clickMenuButton(Player player, int button) {
            if (session != null && !session.valid(player)) return false;
            if (session != null && session.button(player, button)) return true;
            if (button >= CHOICE_RECIPE_BUTTON && button < CHOICE_RECIPE_BUTTON + MAX_CHOICES) {
                List<RecipeHolder<StonecutterRecipe>> choices = choices();
                int chosen = button - CHOICE_RECIPE_BUTTON;
                if (chosen >= choices.size()) return false;
                ResourceLocation wanted = choices.get(chosen).id();
                for (int index = 0; index < getNumRecipes(); index++) {
                    if (getRecipes().get(index).id().equals(wanted)) {
                        return clickMenuButton(player, index);
                    }
                }
                return false;
            }
            if (button >= RECENT_RECIPE_BUTTON && button < RECENT_RECIPE_BUTTON + 4 && player instanceof ServerPlayer serverPlayer) {
                List<ResourceLocation> recent = recents(serverPlayer);
                int chosen = button - RECENT_RECIPE_BUTTON;
                if (chosen >= recent.size()) return false;
                ResourceLocation wanted = recent.get(chosen);
                for (int index = 0; index < getNumRecipes(); index++) {
                    if (getRecipes().get(index).id().equals(wanted)) {
                        return clickMenuButton(player, index);
                    }
                }
                return false;
            }
            if (button < 0 || button >= getNumRecipes()) return false;
            boolean result = super.clickMenuButton(player, button);
            ResourceLocation selected = selectedId();
            if (session != null && selected != null) session.bag().updateSettings(session.upgrade, tag -> {
                tag.putInt("selected_recipe", getSelectedRecipeIndex());
                tag.putString("selected_recipe_id", selected.toString());
            });
            publish(player);
            return result;
        }
        @Override public void clicked(int slot, int button, ClickType input, Player player) {
            if (!session.mayClick(this, slot, button, input, player)) return;
            if (slot == RESULT_SLOT && input == ClickType.QUICK_MOVE) session.shiftBatches(this, RESULT_SLOT, player);
            else super.clicked(slot, button, input, player);
            session.persist(container);
        }
        @Override public ItemStack quickMoveStack(Player player, int slot) {
            if (!session.valid(player) || slot < 0 || slot >= slots.size() || session.isOpenBag(slots.get(slot).getItem())) return ItemStack.EMPTY;
            ItemStack result = slot == RESULT_SLOT ? session.shiftResult(this, RESULT_SLOT, player) : super.quickMoveStack(player, slot);
            session.persist(container); return result;
        }
        @Override public void removed(Player player) { session.close(container); super.removed(player); }
    }

    private static final class PortableAnvil extends AnvilMenu implements BackpackSessionMenu {
        private final Session session;
        PortableAnvil(int id, Inventory inventory, Session session) {
            super(id, inventory, ContainerLevelAccess.NULL); this.session = session; session.load(inputSlots); createResult();
        }
        @Override public BagInventory backpack() { return session.bag(); }
        @Override public boolean stillValid(Player player) { return session.valid(player); }
        @Override public boolean clickMenuButton(Player player, int button) { return session.button(player, button); }
        @Override public void slotsChanged(Container changed) { super.slotsChanged(changed); if (session != null) session.persist(inputSlots); }
        @Override public void clicked(int slot, int button, ClickType input, Player player) {
            if (!session.mayClick(this, slot, button, input, player)) return;
            if (slot == RESULT_SLOT && input == ClickType.QUICK_MOVE) session.shiftBatches(this, RESULT_SLOT, player);
            else super.clicked(slot, button, input, player);
            session.persist(inputSlots);
        }
        @Override public ItemStack quickMoveStack(Player player, int slot) {
            if (!session.valid(player) || slot < 0 || slot >= slots.size() || session.isOpenBag(slots.get(slot).getItem())) return ItemStack.EMPTY;
            ItemStack result = slot == RESULT_SLOT ? session.shiftResult(this, RESULT_SLOT, player) : super.quickMoveStack(player, slot);
            session.persist(inputSlots); return result;
        }
        @Override public void removed(Player player) { session.close(inputSlots); super.removed(player); }
    }

    private static final class PortableSmithing extends SmithingMenu implements BackpackSessionMenu {
        private final Session session;
        PortableSmithing(int id, Inventory inventory, Session session) {
            super(id, inventory, ContainerLevelAccess.NULL); this.session = session; session.load(inputSlots); createResult();
        }
        @Override public BagInventory backpack() { return session.bag(); }
        @Override public boolean stillValid(Player player) { return session.valid(player); }
        @Override public boolean clickMenuButton(Player player, int button) { return session.button(player, button); }
        @Override public void slotsChanged(Container changed) { super.slotsChanged(changed); if (session != null) session.persist(inputSlots); }
        @Override public void clicked(int slot, int button, ClickType input, Player player) {
            if (!session.mayClick(this, slot, button, input, player)) return;
            if (slot == RESULT_SLOT && input == ClickType.QUICK_MOVE) session.shiftBatches(this, RESULT_SLOT, player);
            else super.clicked(slot, button, input, player);
            session.persist(inputSlots);
        }
        @Override public ItemStack quickMoveStack(Player player, int slot) {
            if (!session.valid(player) || slot < 0 || slot >= slots.size() || session.isOpenBag(slots.get(slot).getItem())) return ItemStack.EMPTY;
            ItemStack result = slot == RESULT_SLOT ? session.shiftResult(this, RESULT_SLOT, player) : super.quickMoveStack(player, slot);
            session.persist(inputSlots); return result;
        }
        @Override public void removed(Player player) { session.close(inputSlots); super.removed(player); }
    }

    public static com.kadamitas.fabricatedbackpacks.browser.BrowserWorkstation transferContext(ServerPlayer player) {
        return WorkstationTransfer.context(player);
    }

    /** Plans every source and input before mutation; one set is the default for older callers. */
    public static boolean transfer(ServerPlayer player, ResourceLocation recipeId) { return transfer(player, recipeId, false); }
    public static boolean transfer(ServerPlayer player, ResourceLocation recipeId, boolean maximum) {
        return WorkstationTransfer.transfer(player, recipeId, maximum);
    }

    @SuppressWarnings("unchecked")
    static void finishTransfer(ServerPlayer player, RecipeHolder<?> recipe) {
        AbstractContainerMenu menu = player.containerMenu;
        if (menu instanceof CraftingMenu crafting) {
            crafting.finishPlacingRecipe((RecipeHolder<CraftingRecipe>) recipe);
        } else if (menu instanceof StonecutterMenu stonecutter) {
            stonecutter.slotsChanged(stonecutter.getSlot(0).container);
            for (int index = 0; index < stonecutter.getNumRecipes(); index++) {
                if (stonecutter.getRecipes().get(index).id().equals(recipe.id())) {
                    stonecutter.clickMenuButton(player, index);
                    break;
                }
            }
        } else if (menu instanceof SmithingMenu smithing) smithing.createResult();
        Session session = session(menu);
        if (session != null) { session.persist(session.inputs); publish(player); }
        if (menu instanceof BackpackMenu backpack) backpack.persist();
    }

    private static List<ItemStack> snapshot(Container container) {
        List<ItemStack> result = new ArrayList<>();
        for (int slot = 0; slot < container.getContainerSize(); slot++) result.add(container.getItem(slot).copy());
        return result;
    }
    private static List<ItemStack> copy(List<ItemStack> items) { return new ArrayList<>(items.stream().map(ItemStack::copy).toList()); }
    private static ItemStack insertCopy(List<ItemStack> destination, ItemStack source, java.util.function.BiPredicate<Integer, ItemStack> accepts,
                                       java.util.function.ToIntFunction<ItemStack> limit) {
        ItemStack remaining = source.copy();
        for (int pass = 0; pass < 2; pass++) for (int slot = 0; slot < destination.size() && !remaining.isEmpty(); slot++) {
            ItemStack current = destination.get(slot);
            if (current.isEmpty() != (pass == 1) || !accepts.test(slot, remaining)) continue;
            if (!current.isEmpty() && !ItemStack.isSameItemSameComponents(current, remaining)) continue;
            int moved = Math.min(remaining.getCount(), Math.max(0, limit.applyAsInt(remaining) - current.getCount()));
            if (moved == 0) continue;
            destination.set(slot, remaining.copyWithCount(current.getCount() + moved));
            remaining.shrink(moved);
        }
        return remaining;
    }
}
