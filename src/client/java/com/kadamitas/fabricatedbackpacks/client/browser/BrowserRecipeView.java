package com.kadamitas.fabricatedbackpacks.client.browser;

import com.kadamitas.fabricatedbackpacks.browser.BrowserRecipeEntry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Immutable lists of client-owned display stacks; no stack is sent back as authority. */
record BrowserRecipeView(BrowserRecipeEntry source, Layout layout, int columns, int rows,
                         List<List<ItemStack>> ingredients, List<ItemStack> fuel,
                         List<ItemStack> results, List<ItemStack> stations, int duration, float experience) {
    BrowserRecipeView {
        ingredients = ingredients.stream().map(List::copyOf).toList();
        fuel = List.copyOf(fuel);
        results = List.copyOf(results);
        stations = List.copyOf(stations);
    }

    Set<Identifier> resultIds() { return ids(List.of(results)); }
    Set<Identifier> ingredientIds() {
        Set<Identifier> ids = ids(ingredients);
        ids.addAll(ids(List.of(fuel)));
        return ids;
    }
    private static Set<Identifier> ids(List<List<ItemStack>> groups) {
        Set<Identifier> ids = new HashSet<>();
        for (List<ItemStack> group : groups) for (ItemStack stack : group) if (!stack.isEmpty()) ids.add(BuiltInRegistries.ITEM.getKey(stack.getItem()));
        return ids;
    }

    enum Layout { CRAFTING, FURNACE, STONECUTTING, SMITHING, GENERIC }
}
