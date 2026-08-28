package com.kadamitas.fabricatedbackpacks.recipe;

import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.PlacementInfo;
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
            else if (stack.has(DataComponents.DYE)) dyes++;
            else return false;
        }
        return bags == 1 && dyes > 0;
    }
    @Override public ItemStack assemble(CraftingInput input) {
        int bagSlot = -1;
        for (int slot = 0; slot < input.size(); slot++) if (BackpackRegistry.isBackpack(input.getItem(slot))) bagSlot = slot;
        if (bagSlot < 0) return ItemStack.EMPTY;
        ItemStack result = input.getItem(bagSlot).copyWithCount(1);
        List<DyeColor> body = new ArrayList<>(), trim = new ArrayList<>();
        for (int slot = 0; slot < input.size(); slot++) {
            DyeColor dye = input.getItem(slot).get(DataComponents.DYE);
            if (dye == null) continue;
            int column = slot % input.width();
            int bagColumn = bagSlot % input.width();
            if (column <= bagColumn) body.add(dye);
            if (column >= bagColumn) trim.add(dye);
        }
        CustomModelData previous = result.getOrDefault(DataComponents.CUSTOM_MODEL_DATA, CustomModelData.EMPTY);
        int bodyColor = previous.getColor(0) == null ? 0xB97843 : previous.getColor(0);
        int trimColor = previous.getColor(1) == null ? 0x503B36 : previous.getColor(1);
        if (!body.isEmpty()) bodyColor = DyedItemColor.applyDyes((DyedItemColor) null, body).rgb();
        if (!trim.isEmpty()) trimColor = DyedItemColor.applyDyes((DyedItemColor) null, trim).rgb();
        result.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(previous.floats(), previous.flags(), previous.strings(), List.of(bodyColor, trimColor)));
        return result;
    }
    @Override public boolean isSpecial() { return true; }
    @Override public boolean showNotification() { return true; }
    @Override public String group() { return "fabricated_backpacks"; }
    @Override public CraftingBookCategory category() { return CraftingBookCategory.MISC; }
    @Override public PlacementInfo placementInfo() { return PlacementInfo.NOT_PLACEABLE; }
    @Override public RecipeSerializer<BackpackDyeRecipe> getSerializer() { return BackpackRecipes.DYE; }
}
