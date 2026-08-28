package com.kadamitas.fabricatedbackpacks.recipe;

import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;

public final class BackpackRecipes {
    public static final RecipeSerializer<BackpackUpgradeRecipe> UPGRADE = Registry.register(BuiltInRegistries.RECIPE_SERIALIZER,
            BackpackRegistry.id("backpack_upgrade"), serializer(BackpackUpgradeRecipe.CODEC, BackpackUpgradeRecipe.STREAM_CODEC));
    // Vanilla's 1.21.1 transform recipe already preserves every base component, including custom inventory components.
    public static final RecipeSerializer<SmithingTransformRecipe> SMITHING = Registry.register(BuiltInRegistries.RECIPE_SERIALIZER,
            BackpackRegistry.id("backpack_smithing"), new SmithingTransformRecipe.Serializer());
    private BackpackRecipes() {}
    public static final RecipeSerializer<BackpackDyeRecipe> DYE = Registry.register(BuiltInRegistries.RECIPE_SERIALIZER,
            BackpackRegistry.id("dye_backpack"), serializer(BackpackDyeRecipe.CODEC, BackpackDyeRecipe.STREAM_CODEC));
    private static <T extends net.minecraft.world.item.crafting.Recipe<?>> RecipeSerializer<T> serializer(
            com.mojang.serialization.MapCodec<T> codec,
            net.minecraft.network.codec.StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, T> stream) {
        return new RecipeSerializer<>() {
            @Override public com.mojang.serialization.MapCodec<T> codec() { return codec; }
            @Override public net.minecraft.network.codec.StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, T> streamCodec() { return stream; }
        };
    }
    public static void initialize() {}
}
