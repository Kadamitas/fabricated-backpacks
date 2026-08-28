package com.kadamitas.fabricatedbackpacks.menu;

import com.kadamitas.fabricatedbackpacks.browser.BrowserWorkstation;
import com.kadamitas.fabricatedbackpacks.browser.RecipeIngredientAssignment;
import com.kadamitas.fabricatedbackpacks.gameplay.BackpackTraversal;
import com.kadamitas.fabricatedbackpacks.storage.BagComponents;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.upgrade.InventoryMoves;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.BlastFurnaceMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.inventory.SmokerMenu;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.level.gamerules.GameRules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Copy-first transfer adapters. Results, fuel, armor and the cursor are never ingredient sources. */
final class WorkstationTransfer {
    private WorkstationTransfer() {}

    static BrowserWorkstation context(ServerPlayer player) {
        Destination destination = destination(player);
        return destination == null ? BrowserWorkstation.NONE : destination.kind;
    }

    static boolean transfer(ServerPlayer player, Identifier recipeId, boolean maximum) {
        Destination destination = destination(player);
        if (destination == null || recipeId == null) return false;
        RecipeHolder<?> holder = player.level().recipeAccess().byKey(ResourceKey.create(Registries.RECIPE, recipeId)).orElse(null);
        if (holder == null || !destination.kind.accepts(BuiltInRegistries.RECIPE_TYPE.getKey(holder.value().getType()))
                || !holder.value().isSpecial() && player.level().getGameRules().get(GameRules.LIMITED_CRAFTING)
                && !player.getRecipeBook().contains(holder.id())) return false;
        List<Requirement> requirements = requirements(destination, holder);
        if (requirements.isEmpty()) return false;
        for (Slot slot : destination.inputs) {
            if (!slot.getItem().isEmpty() && (!slot.mayPickup(player) || destination.locked(slot.getItem()))) return false;
        }
        Container processing = destination.bag == null ? null : BackpackTraversal.processingInventory(destination.bag, player);
        List<ItemStack> oldInputs = destination.inputs.stream().map(slot -> slot.getItem().copy()).toList();
        List<ItemStack> oldStorage = processing == null ? List.of() : InventoryMoves.snapshot(processing);
        List<ItemStack> oldInventory = InventoryMoves.snapshot(player.getInventory());
        List<Variant> variants = variants(player, destination, requirements, processing, oldInputs, oldStorage, oldInventory);
        if (variants == null || variants.isEmpty()) return false;
        long[] quantities = variants.stream().mapToLong(variant -> variant.quantity).toArray();
        CompletedPlan complete = null;
        // Return space is not monotonic: a smaller set may reuse a current component variant without displacing it.
        // Test every bounded quantity in descending order, including the complete return plan, before any mutation.
        for (int sets = maximum ? RecipeIngredientAssignment.MAX_SETS : 1; sets >= 1; sets--) {
            Optional<int[]> assigned = RecipeIngredientAssignment.assign(candidates(requirements, variants, sets), quantities, sets);
            if (assigned.isEmpty()) continue;
            Plan candidate = new Plan(new ArrayList<>(oldInputs), new ArrayList<>(oldStorage), new ArrayList<>(oldInventory));
            List<ItemStack> nextInputs = new ArrayList<>();
            for (int slot = 0; slot < oldInputs.size(); slot++) nextInputs.add(ItemStack.EMPTY);
            for (int ingredient = 0; ingredient < requirements.size(); ingredient++) {
                Variant variant = variants.get(assigned.get()[ingredient]);
                if (!take(candidate, variant, sets)) throw new IllegalStateException("A validated ingredient plan lost its source");
                nextInputs.set(requirements.get(ingredient).inputIndex, variant.stack.copyWithCount(sets));
            }
            if (!validInput(player, holder, nextInputs)) continue;
            boolean fits = true;
            for (ItemStack item : candidate.inputs) {
                ItemStack left = InventoryMoves.insertIntoPlan(player.getInventory(), candidate.inventory, item, false);
                if (processing != null) left = InventoryMoves.insertIntoPlan(processing, candidate.storage, left, false);
                if (!left.isEmpty()) { fits = false; break; }
            }
            if (fits) { complete = new CompletedPlan(candidate, nextInputs); break; }
        }
        if (complete == null) return false;
        Plan plan = complete.sources;
        List<ItemStack> nextInputs = complete.inputs;
        if (destination.menu != player.containerMenu || !destination.menu.stillValid(player)
                || processing instanceof BackpackTraversal.ProcessingInventory view && !view.attached()) return false;
        if (destination.menu instanceof CraftingMenu crafting) crafting.beginPlacingRecipe();
        // Publish changed player cells before child/storage mutations, preserving physical parent bags and leases.
        InventoryMoves.commit(player.getInventory(), plan.inventory);
        if (processing != null) InventoryMoves.commit(processing, plan.storage);
        if (destination.menu instanceof StonecutterMenu stonecutter) {
            // Vanilla refreshes its cached holders only when the item type changes. Invalidate them even
            // for a same-item/count transfer after reload, then populate from the current recipe access.
            Slot input = destination.inputs.getFirst();
            input.setByPlayer(ItemStack.EMPTY);
            stonecutter.slotsChanged(input.container);
        }
        for (int slot = 0; slot < nextInputs.size(); slot++) {
            Slot target = destination.inputs.get(slot);
            if (!ItemStack.matches(target.getItem(), nextInputs.get(slot))) target.setByPlayer(nextInputs.get(slot));
        }
        WorkstationMenus.finishTransfer(player, holder);
        destination.menu.broadcastChanges();
        return true;
    }

    private static Destination destination(ServerPlayer player) {
        AbstractContainerMenu menu = player.containerMenu;
        if (!player.isAlive() || player.isSpectator() || !menu.stillValid(player) || !menu.getCarried().isEmpty()) return null;
        BackpackMenu origin = WorkstationMenus.origin(menu);
        BagInventory bag = origin == null ? null : origin.bag();
        if (menu instanceof CraftingMenu crafting) return new Destination(menu, BrowserWorkstation.CRAFTING, crafting.getInputGridSlots(), bag, origin);
        if (menu instanceof StonecutterMenu) return new Destination(menu, BrowserWorkstation.STONECUTTER, List.of(menu.getSlot(0)), bag, origin);
        if (menu instanceof SmithingMenu) return new Destination(menu, BrowserWorkstation.SMITHING, menu.slots.subList(0, 3), bag, origin);
        if (menu instanceof FurnaceMenu) return new Destination(menu, BrowserWorkstation.SMELTING, List.of(menu.getSlot(0)), null, null);
        if (menu instanceof SmokerMenu) return new Destination(menu, BrowserWorkstation.SMOKING, List.of(menu.getSlot(0)), null, null);
        if (menu instanceof BlastFurnaceMenu) return new Destination(menu, BrowserWorkstation.BLASTING, List.of(menu.getSlot(0)), null, null);
        if (menu instanceof BackpackMenu backpack) {
            var selected = backpack.selected().orElse(null);
            if (selected == null) return null;
            BrowserWorkstation kind = switch (selected.kind()) {
                case SMELTING, AUTO_SMELTING -> BrowserWorkstation.SMELTING;
                case SMOKING, AUTO_SMOKING -> BrowserWorkstation.SMOKING;
                case BLASTING, AUTO_BLASTING -> BrowserWorkstation.BLASTING;
                default -> BrowserWorkstation.NONE;
            };
            if (kind != BrowserWorkstation.NONE) return new Destination(menu, kind,
                    List.of(menu.getSlot(backpack.auxiliaryStart())), backpack.bag(), backpack);
        }
        return null;
    }

    private static List<Requirement> requirements(Destination destination, RecipeHolder<?> holder) {
        List<Requirement> result = new ArrayList<>();
        if (holder.value() instanceof CraftingRecipe recipe && destination.kind == BrowserWorkstation.CRAFTING) {
            var placement = recipe.placementInfo();
            if (placement.isImpossibleToPlace() || placement.slotsToIngredientIndex().size() > 9) return List.of();
            int width = recipe instanceof ShapedRecipe shaped ? shaped.getWidth() : recipe.display().stream()
                    .filter(ShapedCraftingRecipeDisplay.class::isInstance).map(ShapedCraftingRecipeDisplay.class::cast)
                    .mapToInt(ShapedCraftingRecipeDisplay::width).findFirst().orElse(3);
            if (width < 1 || width > 3) return List.of();
            for (int index = 0; index < placement.slotsToIngredientIndex().size(); index++) {
                int ingredient = placement.slotsToIngredientIndex().getInt(index);
                int cell = index / width * 3 + index % width;
                if (cell >= 9 || ingredient >= placement.ingredients().size()) return List.of();
                if (ingredient >= 0) result.add(new Requirement(cell, destination.inputs.get(cell), placement.ingredients().get(ingredient)));
            }
        } else if (holder.value() instanceof SmithingRecipe recipe && destination.kind == BrowserWorkstation.SMITHING) {
            recipe.templateIngredient().ifPresent(ingredient -> result.add(new Requirement(0, destination.inputs.get(0), ingredient)));
            result.add(new Requirement(1, destination.inputs.get(1), recipe.baseIngredient()));
            recipe.additionIngredient().ifPresent(ingredient -> result.add(new Requirement(2, destination.inputs.get(2), ingredient)));
        } else if (holder.value() instanceof AbstractCookingRecipe recipe && destination.inputs.size() == 1) {
            result.add(new Requirement(0, destination.inputs.getFirst(), recipe.input()));
        } else if (holder.value() instanceof StonecutterRecipe recipe && destination.kind == BrowserWorkstation.STONECUTTER) {
            result.add(new Requirement(0, destination.inputs.getFirst(), recipe.input()));
        }
        return result;
    }

    private static List<Variant> variants(ServerPlayer player, Destination destination, List<Requirement> requirements,
                                          Container processing, List<ItemStack> inputs, List<ItemStack> storage, List<ItemStack> inventory) {
        List<Variant> result = new ArrayList<>();
        Map<Integer, List<Variant>> hashes = new HashMap<>();
        for (Area area : Area.values()) {
            List<ItemStack> items = switch (area) { case INPUT -> inputs; case STORAGE -> storage; case INVENTORY -> inventory; };
            int size = area == Area.INVENTORY ? Math.min(Inventory.INVENTORY_SIZE, items.size()) : items.size();
            for (int slot = 0; slot < size; slot++) {
                ItemStack stack = items.get(slot);
                if (stack.isEmpty()) continue;
                ItemStack live = switch (area) {
                    case INPUT -> destination.inputs.get(slot).getItem();
                    case STORAGE -> processing.getItem(slot);
                    case INVENTORY -> player.getInventory().getItem(slot);
                };
                if (destination.locked(live) || area == Area.STORAGE && !processing.canTakeItem(player.getInventory(), slot, live)) continue;
                if (requirements.stream().noneMatch(requirement -> requirement.ingredient.test(stack) && requirement.slot.mayPlace(stack))) continue;
                int hash = ItemStack.hashItemAndComponents(stack);
                List<Variant> bucket = hashes.computeIfAbsent(hash, ignored -> new ArrayList<>());
                Variant variant = bucket.stream().filter(candidate -> ItemStack.isSameItemSameComponents(candidate.stack, stack)).findFirst().orElse(null);
                if (variant == null) {
                    if (result.size() == RecipeIngredientAssignment.MAX_VARIANTS) return null;
                    variant = new Variant(stack.copyWithCount(1));
                    result.add(variant);
                    bucket.add(variant);
                }
                boolean infinite = area == Area.STORAGE && infinite(processing, slot);
                variant.sources.add(new Source(area, slot, infinite));
                variant.quantity = Math.min((long) RecipeIngredientAssignment.MAX_SETS * RecipeIngredientAssignment.MAX_SLOTS,
                        variant.quantity + (infinite ? (long) RecipeIngredientAssignment.MAX_SETS * RecipeIngredientAssignment.MAX_SLOTS : stack.getCount()));
            }
        }
        return result;
    }

    private static boolean infinite(Container inventory, int slot) {
        if (inventory instanceof BagInventory bag) return bag.isInfiniteSlot(slot);
        if (inventory instanceof BackpackTraversal.ProcessingInventory view) {
            var node = view.node(slot);
            return node != null && node.inventory().isInfiniteSlot(view.physicalSlot(slot));
        }
        return false;
    }

    private static List<int[]> candidates(List<Requirement> requirements, List<Variant> variants, int sets) {
        List<int[]> choices = new ArrayList<>(requirements.size());
        for (Requirement requirement : requirements) {
            java.util.stream.IntStream.Builder candidates = java.util.stream.IntStream.builder();
            for (int variant = 0; variant < variants.size(); variant++) {
                ItemStack stack = variants.get(variant).stack;
                if (requirement.ingredient.test(stack) && requirement.slot.mayPlace(stack)
                        && Math.min(stack.getMaxStackSize(), requirement.slot.getMaxStackSize(stack)) >= sets) candidates.add(variant);
            }
            choices.add(candidates.build().toArray());
        }
        return choices;
    }

    private static boolean take(Plan plan, Variant variant, int count) {
        int remaining = count;
        for (Source source : variant.sources) {
            if (source.infinite) return true;
            List<ItemStack> items = switch (source.area) { case INPUT -> plan.inputs; case STORAGE -> plan.storage; case INVENTORY -> plan.inventory; };
            ItemStack current = items.get(source.slot);
            int removed = Math.min(remaining, current.getCount());
            // Plans share immutable snapshots between attempts; replace only the changed cell.
            items.set(source.slot, current.copyWithCount(current.getCount() - removed));
            remaining -= removed;
            if (remaining == 0) return true;
        }
        return false;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean validInput(ServerPlayer player, RecipeHolder<?> holder, List<ItemStack> inputs) {
        ItemStack result;
        if (holder.value() instanceof CraftingRecipe recipe) {
            CraftingInput input = CraftingInput.of(3, 3, inputs);
            if (!recipe.matches(input, player.level())) return false;
            result = recipe.assemble(input);
        } else if (holder.value() instanceof SmithingRecipe recipe) {
            SmithingRecipeInput input = new SmithingRecipeInput(inputs.get(0), inputs.get(1), inputs.get(2));
            if (!recipe.matches(input, player.level()) || player.level().recipeAccess().getRecipeFor(RecipeType.SMITHING, input, player.level())
                    .filter(selected -> selected.id().equals(holder.id())).isEmpty()) return false;
            result = recipe.assemble(input);
        } else if (holder.value() instanceof StonecutterRecipe recipe) {
            SingleRecipeInput input = new SingleRecipeInput(inputs.getFirst());
            if (!recipe.matches(input, player.level())) return false;
            result = recipe.assemble(input);
        } else if (holder.value() instanceof AbstractCookingRecipe recipe) {
            SingleRecipeInput input = new SingleRecipeInput(inputs.getFirst());
            Optional<RecipeHolder<?>> selected = (Optional) player.level().recipeAccess().getRecipeFor((RecipeType) recipe.getType(), input, player.level());
            if (!recipe.matches(input, player.level()) || selected.filter(current -> current.id().equals(holder.id())).isEmpty()) return false;
            result = recipe.assemble(input);
        } else return false;
        return !result.isEmpty() && result.isItemEnabled(player.level().enabledFeatures());
    }

    private enum Area { INPUT, STORAGE, INVENTORY }
    private record Requirement(int inputIndex, Slot slot, Ingredient ingredient) {}
    private record Source(Area area, int slot, boolean infinite) {}
    private record Plan(List<ItemStack> inputs, List<ItemStack> storage, List<ItemStack> inventory) {}
    private record CompletedPlan(Plan sources, List<ItemStack> inputs) {}
    private static final class Variant {
        final ItemStack stack;
        final List<Source> sources = new ArrayList<>();
        long quantity;
        Variant(ItemStack stack) { this.stack = stack; }
    }
    private record Destination(AbstractContainerMenu menu, BrowserWorkstation kind, List<Slot> inputs, BagInventory bag, BackpackMenu origin) {
        boolean locked(ItemStack item) {
            return origin != null && (origin.locks(item) || bag.identity().equals(item.getOrDefault(BagComponents.IDENTITY, "")));
        }
    }
}
