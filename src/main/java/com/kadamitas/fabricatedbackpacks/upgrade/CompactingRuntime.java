package com.kadamitas.fabricatedbackpacks.upgrade;

import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.config.BackpackConfig;
import com.kadamitas.fabricatedbackpacks.config.UpgradeConfig;
import com.kadamitas.fabricatedbackpacks.storage.InstalledUpgrade;
import com.kadamitas.fabricatedbackpacks.gameplay.BackpackTraversal;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Real-recipe compaction, planned against a copy before any live ingredient is removed. */
public final class CompactingRuntime {
    public record Shape(int width, int height, String pattern) {
        public Shape {
            if (width < 1 || width > 3 || height < 1 || height > 3 || pattern.length() != width * height
                    || !pattern.matches("[01]+") || pattern.chars().filter(c -> c == '1').count() < 2) {
                throw new IllegalArgumentException("Compacting shapes must contain 2+ cells within a 3 by 3 grid");
            }
        }
        public int ingredients() { return (int) pattern.chars().filter(c -> c == '1').count(); }
        public CraftingInput input(ItemStack item) {
            List<ItemStack> cells = new ArrayList<>(pattern.length());
            for (int cell = 0; cell < pattern.length(); cell++) cells.add(pattern.charAt(cell) == '1' ? item.copyWithCount(1) : ItemStack.EMPTY);
            return CraftingInput.of(width, height, cells);
        }
    }

    private static final Shape TWO = new Shape(2, 2, "1111");
    private static final Shape THREE = new Shape(3, 3, "111111111");
    private static List<Shape> extraShapes = List.of(new Shape(3, 3, "111101111"));
    private static Map<Identifier, List<Shape>> overrides = Map.of();
    private static UpgradeConfig.Compacting configured;
    private CompactingRuntime() { }

    /** Configuration is validated before publication; each recipe still needs its reverse unless explicitly allowed. */
    private static void configureShapes(UpgradeConfig.Compacting rules) {
        if (rules == configured) return;
        extraShapes = rules.extraShapes().stream().map(shape -> new Shape(shape.width(), shape.height(), shape.pattern())).toList();
        overrides = rules.itemOverrides().entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                entry -> Identifier.parse(entry.getKey()), entry -> entry.getValue().stream()
                        .map(shape -> new Shape(shape.width(), shape.height(), shape.pattern())).toList()));
        configured = rules;
    }

    public static int compact(BagInventory bag, InstalledUpgrade upgrade, ServerLevel level, int operationLimit) {
        var rules = BackpackConfig.get().upgrades().compacting();
        configureShapes(rules);
        int performed = 0;
        Container storage = BackpackTraversal.processingInventory(bag);
        int safeLimit = Math.clamp(operationLimit, 0, rules.maximumOperations());
        while (performed < safeLimit) {
            boolean changed = false;
            for (int slot = 0; slot < storage.getContainerSize(); slot++) {
                ItemStack candidate = storage.getItem(slot);
                if (candidate.isEmpty() || !storage.canTakeItem(null, slot, candidate) || !UpgradeFilters.matches(bag, upgrade, candidate)) continue;
                for (Shape shape : shapes(candidate, upgrade.kind().advanced())) {
                    if (tryCompact(bag, storage, upgrade, level, candidate, shape)) {
                        changed = true;
                        performed++;
                        break;
                    }
                }
                if (changed) break;
            }
            if (!changed) break;
        }
        return performed;
    }

    private static List<Shape> shapes(ItemStack item, boolean advanced) {
        List<Shape> result = new ArrayList<>();
        List<Shape> custom = overrides.get(BuiltInRegistries.ITEM.getKey(item.getItem()));
        if (custom != null) result.addAll(custom);
        if (advanced) result.add(THREE);
        result.add(TWO);
        result.addAll(extraShapes);
        int max = advanced ? 3 : 2;
        return result.stream().filter(shape -> shape.width() <= max && shape.height() <= max).distinct().toList();
    }

    private static boolean tryCompact(BagInventory bag, Container storage, InstalledUpgrade upgrade, ServerLevel level, ItemStack candidate, Shape shape) {
        if (InventoryMoves.count(storage, candidate) < shape.ingredients()) return false;
        CraftingInput input = shape.input(candidate);
        Optional<RecipeHolder<CraftingRecipe>> selected = level.recipeAccess().getRecipeFor(RecipeType.CRAFTING, input, level);
        if (selected.isEmpty()) return false;
        CraftingRecipe recipe = selected.get().value();
        ItemStack result = recipe.assemble(input);
        if (result.isEmpty() || ItemStack.isSameItem(result, candidate)) return false;
        if (!bag.settings(upgrade).getBooleanOr("compact_anything", false) && !reversible(level, candidate, result, shape.ingredients())) return false;
        List<ItemStack> plan = InventoryMoves.snapshot(storage);
        if (!InventoryMoves.removeExact(storage, plan, candidate, shape.ingredients())) return false;
        if (!InventoryMoves.insertIntoPlan(storage, plan, result, false).isEmpty()) return false;
        for (ItemStack remainder : recipe.getRemainingItems(input)) {
            if (!InventoryMoves.insertIntoPlan(storage, plan, remainder, false).isEmpty()) return false;
        }
        InventoryMoves.commit(storage, plan);
        return true;
    }

    private static boolean reversible(ServerLevel level, ItemStack ingredient, ItemStack compressed, int inputCount) {
        CraftingInput reverseInput = CraftingInput.of(1, 1, List.of(compressed.copyWithCount(1)));
        Optional<RecipeHolder<CraftingRecipe>> reverse = level.recipeAccess().getRecipeFor(RecipeType.CRAFTING, reverseInput, level);
        if (reverse.isEmpty()) return false;
        ItemStack restored = reverse.get().value().assemble(reverseInput);
        if (!ItemStack.isSameItemSameComponents(restored, ingredient) || (long) restored.getCount() * compressed.getCount() != inputCount) return false;
        return reverse.get().value().getRemainingItems(reverseInput).stream().allMatch(ItemStack::isEmpty);
    }
}
