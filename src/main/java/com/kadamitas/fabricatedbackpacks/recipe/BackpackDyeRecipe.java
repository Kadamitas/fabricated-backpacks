package com.kadamitas.fabricatedbackpacks.recipe;

import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.item.BackpackColors;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;
import java.util.ArrayList;
import java.util.List;

public final class BackpackDyeRecipe implements CraftingRecipe {
    public static final BackpackDyeRecipe INSTANCE = new BackpackDyeRecipe();
    public static final MapCodec<BackpackDyeRecipe> CODEC = MapCodec.unit(INSTANCE);
    public static final StreamCodec<RegistryFriendlyByteBuf, BackpackDyeRecipe> STREAM_CODEC = StreamCodec.unit(INSTANCE);
    private BackpackDyeRecipe() {}
    @Override public boolean matches(CraftingInput input, Level level) {
        int bags = 0, dyes = 0;
        for (ItemStack stack : input.items()) {
            if (stack.isEmpty()) continue;
            if (BackpackRegistry.isBackpack(stack)) bags++;
            else if (stack.getItem() instanceof DyeItem) dyes++;
            else return false;
        }
        return bags == 1 && dyes > 0;
    }
    @Override public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        int bagSlot = -1;
        for (int slot = 0; slot < input.size(); slot++) if (BackpackRegistry.isBackpack(input.getItem(slot))) bagSlot = slot;
        if (bagSlot < 0) return ItemStack.EMPTY;
        ItemStack result = input.getItem(bagSlot).copyWithCount(1);
        List<DyeColor> body = new ArrayList<>(), trim = new ArrayList<>();
        for (int slot = 0; slot < input.size(); slot++) {
            if (!(input.getItem(slot).getItem() instanceof DyeItem item)) continue;
            DyeColor dye = item.getDyeColor();
            int column = slot % input.width();
            int bagColumn = bagSlot % input.width();
            if (column <= bagColumn) body.add(dye);
            if (column >= bagColumn) trim.add(dye);
        }
        int bodyColor = BackpackColors.color(result, 0, BackpackColors.DEFAULT_BODY);
        int trimColor = BackpackColors.color(result, 1, BackpackColors.DEFAULT_TRIM);
        if (!body.isEmpty()) bodyColor = blend(body);
        if (!trim.isEmpty()) trimColor = blend(trim);
        BackpackColors.set(result, bodyColor, trimColor);
        return result;
    }
    /** Vanilla brightness normalization, independently applied to each selected layer. */
    static int blend(List<DyeColor> dyes) {
        int red = 0, green = 0, blue = 0, brightness = 0;
        for (DyeColor dye : dyes) {
            int rgb = dye.getTextureDiffuseColor();
            int r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255;
            red += r; green += g; blue += b;
            brightness += Math.max(r, Math.max(g, b));
        }
        red /= dyes.size(); green /= dyes.size(); blue /= dyes.size();
        float average = (float) brightness / dyes.size();
        float maximum = Math.max(red, Math.max(green, blue));
        if (maximum == 0) return 0;
        return (int) (red * average / maximum) << 16 | (int) (green * average / maximum) << 8 | (int) (blue * average / maximum);
    }
    @Override public boolean canCraftInDimensions(int width, int height) { return width * height >= 2; }
    @Override public ItemStack getResultItem(HolderLookup.Provider registries) { return ItemStack.EMPTY; }
    @Override public boolean isSpecial() { return true; }
    @Override public boolean showNotification() { return true; }
    @Override public String getGroup() { return "fabricated_backpacks"; }
    @Override public CraftingBookCategory category() { return CraftingBookCategory.MISC; }
    @Override public RecipeSerializer<BackpackDyeRecipe> getSerializer() { return BackpackRecipes.DYE; }
}
