package com.kadamitas.fabricatedbackpacks.recipe;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.NormalCraftingRecipe;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapedRecipePattern;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.level.Level;

import java.util.List;

/** A normal shaped recipe whose single source backpack is transmuted without losing its components. */
public final class BackpackUpgradeRecipe extends NormalCraftingRecipe {
    public static final MapCodec<BackpackUpgradeRecipe> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Recipe.CommonInfo.MAP_CODEC.forGetter(recipe -> recipe.commonInfo),
            CraftingRecipe.CraftingBookInfo.MAP_CODEC.forGetter(recipe -> recipe.bookInfo),
            ShapedRecipePattern.MAP_CODEC.forGetter(recipe -> recipe.pattern),
            ItemStackTemplate.CODEC.fieldOf("result").forGetter(recipe -> recipe.result),
            Codec.STRING.fieldOf("source").forGetter(recipe -> recipe.source)).apply(instance, BackpackUpgradeRecipe::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, BackpackUpgradeRecipe> STREAM_CODEC = ByteBufCodecs.fromCodecWithRegistries(CODEC.codec());
    private final ShapedRecipePattern pattern;
    private final ItemStackTemplate result;
    private final String source;
    private final ShapedRecipe shape;

    public BackpackUpgradeRecipe(Recipe.CommonInfo commonInfo, CraftingBookInfo bookInfo, ShapedRecipePattern pattern,
                                 ItemStackTemplate result, String source) {
        super(commonInfo, bookInfo);
        this.pattern = pattern;
        this.result = result;
        this.source = source;
        shape = new ShapedRecipe(commonInfo, bookInfo, pattern, result);
    }
    @Override public boolean matches(CraftingInput input, Level level) {
        return shape.matches(input, level) && input.items().stream().filter(this::isSource).count() == 1;
    }
    private boolean isSource(ItemStack stack) { return !stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(source); }
    @Override public ItemStack assemble(CraftingInput input) {
        return input.items().stream().filter(this::isSource).findFirst()
                .map(stack -> stack.transmuteCopy(result.create().getItem(), 1)).orElse(ItemStack.EMPTY);
    }
    @Override protected PlacementInfo createPlacementInfo() { return shape.placementInfo(); }
    @Override public List<RecipeDisplay> display() { return shape.display(); }
    @Override public RecipeSerializer<BackpackUpgradeRecipe> getSerializer() { return BackpackRecipes.UPGRADE; }
}
