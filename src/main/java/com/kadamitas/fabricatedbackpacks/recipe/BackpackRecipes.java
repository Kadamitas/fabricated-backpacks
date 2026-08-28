package com.kadamitas.fabricatedbackpacks.recipe;

import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;

public final class BackpackRecipes {
    public static final RecipeSerializer<BackpackUpgradeRecipe> UPGRADE = Registry.register(BuiltInRegistries.RECIPE_SERIALIZER,
            BackpackRegistry.id("backpack_upgrade"), new RecipeSerializer<>(BackpackUpgradeRecipe.CODEC, BackpackUpgradeRecipe.STREAM_CODEC));
    // Vanilla's 26.2 transform recipe already preserves every base component, including custom inventory components.
    public static final RecipeSerializer<SmithingTransformRecipe> SMITHING = Registry.register(BuiltInRegistries.RECIPE_SERIALIZER,
            BackpackRegistry.id("backpack_smithing"), new RecipeSerializer<>(SmithingTransformRecipe.MAP_CODEC, SmithingTransformRecipe.STREAM_CODEC));
    private BackpackRecipes() {}
    public static final RecipeSerializer<BackpackDyeRecipe> DYE = Registry.register(BuiltInRegistries.RECIPE_SERIALIZER,
            BackpackRegistry.id("dye_backpack"), new RecipeSerializer<>(BackpackDyeRecipe.CODEC, BackpackDyeRecipe.STREAM_CODEC));
    public static void initialize() {}
}
