package com.kadamitas.fabricatedbackpacks.recipe;

import com.kadamitas.fabricatedbackpacks.compat.ItemStackTemplate;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapedRecipePattern;
import net.minecraft.world.level.Level;

/** Native shaped matching and recipe-book geometry, preserving the single source bag's components. */
public final class BackpackUpgradeRecipe extends ShapedRecipe {
    public static final MapCodec<BackpackUpgradeRecipe> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.STRING.optionalFieldOf("group", "").forGetter(BackpackUpgradeRecipe::getGroup),
            CraftingBookCategory.CODEC.optionalFieldOf("category", CraftingBookCategory.MISC).forGetter(BackpackUpgradeRecipe::category),
            ShapedRecipePattern.MAP_CODEC.forGetter(recipe -> recipe.sourcePattern),
            ItemStackTemplate.CODEC.fieldOf("result").forGetter(recipe -> recipe.resultTemplate),
            Codec.STRING.fieldOf("source").forGetter(recipe -> recipe.source),
            Codec.BOOL.optionalFieldOf("show_notification", true).forGetter(BackpackUpgradeRecipe::showNotification))
            .apply(instance, BackpackUpgradeRecipe::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, BackpackUpgradeRecipe> STREAM_CODEC = ByteBufCodecs.fromCodecWithRegistries(CODEC.codec());
    private final ShapedRecipePattern sourcePattern;
    private final ItemStackTemplate resultTemplate;
    private final String source;

    public BackpackUpgradeRecipe(String group, CraftingBookCategory category, ShapedRecipePattern pattern,
                                 ItemStackTemplate result, String source, boolean notification) {
        super(group, category, pattern, result.create(), notification);
        this.sourcePattern = pattern;
        this.resultTemplate = result;
        this.source = source;
    }
    @Override public boolean matches(CraftingInput input, Level level) {
        return super.matches(input, level) && input.items().stream().filter(this::isSource).count() == 1;
    }
    private boolean isSource(ItemStack stack) { return !stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(source); }
    @Override public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        return input.items().stream().filter(this::isSource).findFirst()
                .map(stack -> stack.transmuteCopy(resultTemplate.create().getItem(), 1)).orElse(ItemStack.EMPTY);
    }
    @Override public ItemStack getResultItem(HolderLookup.Provider registries) { return resultTemplate.create(); }
    @Override public RecipeSerializer<BackpackUpgradeRecipe> getSerializer() { return BackpackRecipes.UPGRADE; }
}
