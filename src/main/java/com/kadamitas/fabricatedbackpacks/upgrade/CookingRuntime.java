package com.kadamitas.fabricatedbackpacks.upgrade;

import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.config.BackpackConfig;
import com.kadamitas.fabricatedbackpacks.config.UpgradeConfig;
import com.kadamitas.fabricatedbackpacks.gameplay.BackpackTraversal;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.storage.InstalledUpgrade;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Six independent vanilla-recipe cooking variants. Progress and unclaimed XP travel with the upgrade. */
public final class CookingRuntime {
    public static final int INPUT = 0;
    public static final int FUEL = 1;
    public static final int OUTPUT = 2;
    private CookingRuntime() { }

    public static boolean automatic(UpgradeKind kind) { return kind.id().startsWith("auto_"); }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Optional<RecipeHolder<AbstractCookingRecipe>> recipe(ServerLevel level, UpgradeKind kind, ItemStack input) {
        if (input.isEmpty()) return Optional.empty();
        RecipeType type = switch (kind) {
            case SMOKING, AUTO_SMOKING -> RecipeType.SMOKING;
            case BLASTING, AUTO_BLASTING -> RecipeType.BLASTING;
            default -> RecipeType.SMELTING;
        };
        return (Optional) level.recipeAccess().getRecipeFor(type, new SingleRecipeInput(input), level);
    }

    public static void tick(BagInventory bag, InstalledUpgrade upgrade, ServerLevel level) {
        Container inventory = bag.upgradeInventory(upgrade);
        if (inventory.getContainerSize() < 3) return;
        CompoundTag state = bag.settings(upgrade).copy();
        var rules = BackpackConfig.get().upgrades().cooking();
        if (automatic(upgrade.kind())) automaticTransfers(bag, upgrade, inventory, level, state, rules);
        int remaining = Math.max(0, state.getIntOr("burn_remaining", 0));
        int progress = Math.max(0, state.getIntOr("cook_progress", 0));
        ItemStack input = inventory.getItem(INPUT);
        Optional<RecipeHolder<AbstractCookingRecipe>> selected = recipe(level, upgrade.kind(), input);
        if (selected.isEmpty()) {
            if (remaining > 0) remaining--;
            state.putInt("cook_progress", 0);
            state.putInt("burn_remaining", remaining);
            state.putBoolean("burning", remaining > 0);
            save(bag, upgrade, state);
            return;
        }
        RecipeHolder<AbstractCookingRecipe> holder = selected.get();
        String key = holder.id().identifier().toString();
        int fingerprint = ItemStack.hashItemAndComponents(input);
        if (!state.getStringOr("cooking_recipe", "").equals(key)
                || state.getIntOr("cooking_input", 0) != fingerprint) progress = 0;
        int duration = Math.max(1, (int) Math.ceil(holder.value().cookingTime() / rules.speed()));
        ItemStack result = holder.value().assemble(new SingleRecipeInput(input));
        boolean canFinish = !result.isEmpty() && outputFits(inventory, result) && canReturnInputRemainder(bag, inventory);
        int newlyFueled = canFinish && remaining == 0 ? consumeFuel(bag, inventory, level) : 0;
        if (newlyFueled > 0) {
            remaining = newlyFueled;
            boolean quick = upgrade.kind() == UpgradeKind.SMOKING || upgrade.kind() == UpgradeKind.AUTO_SMOKING
                    || upgrade.kind() == UpgradeKind.BLASTING || upgrade.kind() == UpgradeKind.AUTO_BLASTING;
            remaining = Math.max(1, (int) Math.floor(remaining * rules.fuelEfficiency() * (quick ? 0.5 : 1)));
            state.putInt("burn_total", remaining);
        }
        boolean burning = remaining > 0;
        if (burning) remaining--;
        if (canFinish && burning) {
            progress++;
            if (progress >= duration && finish(bag, inventory, result)) {
                progress = 0;
                double previous = state.getDoubleOr("experience", 0);
                if (!Double.isFinite(previous)) previous = 0;
                state.putDouble("experience", Math.min(Integer.MAX_VALUE, Math.max(0, previous) + holder.value().experience()));
                CompoundTag used = state.getCompoundOrEmpty("recipes_used").copy();
                used.putInt(key, Math.min(Integer.MAX_VALUE - 1, Math.max(0, used.getIntOr(key, 0))) + 1);
                state.put("recipes_used", used);
            }
        } else if (!burning) progress = Math.max(0, progress - 2);
        state.putString("cooking_recipe", key);
        state.putInt("cooking_input", fingerprint);
        state.putInt("cook_total", duration);
        state.putInt("cook_progress", progress);
        state.putInt("burn_remaining", remaining);
        state.putBoolean("burning", burning);
        save(bag, upgrade, state);
    }

    private static void automaticTransfers(BagInventory bag, InstalledUpgrade upgrade, Container inventory,
                                           ServerLevel level, CompoundTag state, UpgradeConfig.Cooking rules) {
        long now = level.getGameTime();
        boolean idle = inventory.getItem(INPUT).isEmpty() && state.getIntOr("burn_remaining", 0) <= 0;
        if (idle && !due(state, "auto_idle_next", now, rules.idleTicks())) return;
        if (due(state, "auto_output_next", now, rules.retryMaximum())) {
            boolean changed = push(bag, inventory, OUTPUT);
            if (!inventory.getItem(FUEL).isEmpty() && !level.fuelValues().isFuel(inventory.getItem(FUEL))
                    && !(inventory.getItem(FUEL).is(Items.BUCKET) && inventory.getItem(INPUT).is(Items.WET_SPONGE))) changed |= push(bag, inventory, FUEL);
            if (!inventory.getItem(INPUT).isEmpty() && recipe(level, upgrade.kind(), inventory.getItem(INPUT)).isEmpty()) changed |= push(bag, inventory, INPUT);
            retry(state, "auto_output", changed, now, rules);
        }
        if (due(state, "auto_input_next", now, rules.retryMaximum())) {
            retry(state, "auto_input", pull(bag, upgrade, inventory, level, INPUT), now, rules);
        }
        if (due(state, "auto_fuel_next", now, rules.retryMaximum())) {
            retry(state, "auto_fuel", pull(bag, upgrade, inventory, level, FUEL), now, rules);
        }
        state.putLong("auto_idle_next", inventory.getItem(INPUT).isEmpty() && state.getIntOr("burn_remaining", 0) <= 0
                ? now + rules.idleTicks() : 0);
    }

    private static boolean due(CompoundTag state, String key, long now, int maximumDelay) {
        long next = state.getLongOr(key, 0);
        // An old world clock or malformed item cannot leave the cooker asleep indefinitely.
        return next <= now || next - now > maximumDelay;
    }

    private static void retry(CompoundTag state, String prefix, boolean changed, long now, UpgradeConfig.Cooking rules) {
        int previous = Math.clamp(state.getIntOr(prefix + "_delay", 0), 0, rules.retryMaximum());
        int delay = changed ? rules.retryMinimum() : Math.min(rules.retryMaximum(), previous + rules.retryMinimum());
        state.putInt(prefix + "_delay", delay);
        state.putLong(prefix + "_next", now + delay);
    }

    private static void save(BagInventory bag, InstalledUpgrade upgrade, CompoundTag state) {
        if (!state.equals(bag.settings(upgrade))) bag.updateSettings(upgrade, tag -> tag.merge(state));
    }

    private static boolean outputFits(Container inventory, ItemStack result) {
        ItemStack present = inventory.getItem(OUTPUT);
        return (present.isEmpty() || ItemStack.isSameItemSameComponents(present, result))
                && (long) present.getCount() + result.getCount() <= inventory.getMaxStackSize(result);
    }

    private static ItemStack craftingRemainder(ItemStack stack) {
        ItemStackTemplate template = stack.getItem().getCraftingRemainder();
        return template == null ? ItemStack.EMPTY : template.create();
    }

    private static boolean canReturnInputRemainder(BagInventory bag, Container inventory) {
        ItemStack input = inventory.getItem(INPUT);
        ItemStack remainder = craftingRemainder(input);
        return remainder.isEmpty() || input.getCount() == 1 || BackpackTraversal.insert(bag, remainder, true, null).isEmpty();
    }

    private static int consumeFuel(BagInventory bag, Container inventory, ServerLevel level) {
        ItemStack fuel = inventory.getItem(FUEL);
        int duration = level.fuelValues().burnDuration(fuel);
        if (duration <= 0) return 0;
        ItemStack rest = craftingRemainder(fuel);
        Container storage = BackpackTraversal.processingInventory(bag);
        List<ItemStack> plan = InventoryMoves.snapshot(storage);
        if (fuel.getCount() > 1 && !InventoryMoves.insertIntoPlan(storage, plan, rest, false).isEmpty()) return 0;
        inventory.setItem(FUEL, fuel.getCount() == 1 ? rest : fuel.copyWithCount(fuel.getCount() - 1));
        InventoryMoves.commit(storage, plan);
        return duration;
    }

    private static boolean finish(BagInventory bag, Container inventory, ItemStack result) {
        ItemStack input = inventory.getItem(INPUT);
        ItemStack remainder = craftingRemainder(input);
        Container storage = BackpackTraversal.processingInventory(bag);
        List<ItemStack> plan = InventoryMoves.snapshot(storage);
        if (input.getCount() > 1 && !InventoryMoves.insertIntoPlan(storage, plan, remainder, false).isEmpty()) return false;
        ItemStack output = inventory.getItem(OUTPUT);
        inventory.setItem(OUTPUT, result.copyWithCount(result.getCount() + output.getCount()));
        inventory.setItem(INPUT, input.getCount() == 1 ? remainder : input.copyWithCount(input.getCount() - 1));
        if (input.is(Items.WET_SPONGE) && inventory.getItem(FUEL).is(Items.BUCKET)) inventory.setItem(FUEL, new ItemStack(Items.WATER_BUCKET));
        InventoryMoves.commit(storage, plan);
        inventory.setChanged();
        return true;
    }

    private static boolean push(BagInventory bag, Container inventory, int slot) {
        ItemStack stack = inventory.getItem(slot);
        if (stack.isEmpty()) return false;
        ItemStack remainder = BackpackTraversal.insert(bag, stack, false, null);
        if (remainder.getCount() == stack.getCount()) return false;
        inventory.setItem(slot, remainder);
        return true;
    }

    private static boolean pull(BagInventory bag, InstalledUpgrade upgrade, Container inventory, ServerLevel level, int slot) {
        ItemStack present = inventory.getItem(slot);
        Container storage = BackpackTraversal.processingInventory(bag);
        for (int source = 0; source < storage.getContainerSize(); source++) {
            ItemStack candidate = storage.getItem(source);
            if (!candidate.isEmpty() && !storage.canTakeItem(inventory, source, candidate)) continue;
            if (candidate.isEmpty() || (!present.isEmpty() && !ItemStack.isSameItemSameComponents(present, candidate))) continue;
            boolean fuel = slot == FUEL;
            if (fuel ? !level.fuelValues().isFuel(candidate) : recipe(level, upgrade.kind(), candidate).isEmpty()) continue;
            if (!UpgradeFilters.matches(bag, upgrade, candidate, fuel ? "fuel_" : "input_",
                    fuel ? bag.cookingInputFilters(upgrade) : 0,
                    fuel ? bag.cookingFuelFilters(upgrade) : bag.cookingInputFilters(upgrade), fuel, null)) continue;
            int room = Math.max(0, inventory.getMaxStackSize(candidate) - present.getCount());
            int moved = Math.min(room, candidate.getCount());
            if (moved == 0) return false;
            inventory.setItem(slot, candidate.copyWithCount(present.getCount() + moved));
            storage.setItem(source, candidate.copyWithCount(candidate.getCount() - moved));
            return true;
        }
        return false;
    }

    /** Fractional XP stays on the upgrade, preventing repeated small output takes from deleting it. */
    public static void claimExperience(BagInventory bag, InstalledUpgrade upgrade, ServerPlayer player) {
        CompoundTag state = bag.settings(upgrade);
        double stored = Math.max(0, state.getDoubleOr("experience", 0));
        int points = (int) Math.min(Integer.MAX_VALUE, Math.floor(stored));
        if (points > 0) player.giveExperiencePoints(points);
        List<ResourceKey<Recipe<?>>> used = new ArrayList<>();
        for (String key : state.getCompoundOrEmpty("recipes_used").keySet()) {
            Identifier id = Identifier.tryParse(key);
            if (id != null) used.add(ResourceKey.create(Registries.RECIPE, id));
        }
        if (!used.isEmpty()) player.awardRecipesByKey(used);
        bag.updateSettings(upgrade, tag -> { tag.putDouble("experience", stored - points); tag.remove("recipes_used"); });
    }
}
