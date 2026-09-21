package com.kadamitas.fabricatedbackpacks.recipe;

import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;

public final class BackpackRecipes {
    public static final RecipeSerializer<BackpackUpgradeRecipe> UPGRADE = com.kadamitas.fabricatedbackpacks.platform.NativeRegistries.register(BuiltInRegistries.RECIPE_SERIALIZER,
            BackpackRegistry.id("backpack_upgrade"), new RecipeSerializer<>(BackpackUpgradeRecipe.CODEC, BackpackUpgradeRecipe.STREAM_CODEC));
    // Vanilla's transform recipe preserves every base component, including custom inventory components.
    // RecipeSerializer is a record: reusing both vanilla codecs makes it equal to the vanilla
    // serializer, which Forge rejects as a duplicate registration. An identity codec mapping
    // retains the exact vanilla recipe behavior while giving our JSON type its own serializer.
    public static final RecipeSerializer<SmithingTransformRecipe> SMITHING = com.kadamitas.fabricatedbackpacks.platform.NativeRegistries.register(BuiltInRegistries.RECIPE_SERIALIZER,
            BackpackRegistry.id("backpack_smithing"), new RecipeSerializer<>(SmithingTransformRecipe.MAP_CODEC.xmap(recipe -> recipe, recipe -> recipe), SmithingTransformRecipe.STREAM_CODEC));
    private BackpackRecipes() {}
    public static final RecipeSerializer<BackpackDyeRecipe> DYE = com.kadamitas.fabricatedbackpacks.platform.NativeRegistries.register(BuiltInRegistries.RECIPE_SERIALIZER,
            BackpackRegistry.id("dye_backpack"), new RecipeSerializer<>(BackpackDyeRecipe.CODEC, BackpackDyeRecipe.STREAM_CODEC));
    public static void initialize() {}
}
