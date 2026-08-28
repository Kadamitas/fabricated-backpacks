package com.kadamitas.fabricatedbackpacks.browser;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Server-supplied display only. It never authorizes a result or item count. */
public record BrowserRecipeEntry(Identifier recipe, Identifier category, RecipeDisplay display,
                                 List<SlotDisplay> fallbackInputs, boolean unlocked) {
    public BrowserRecipeEntry {
        Objects.requireNonNull(recipe);
        Objects.requireNonNull(category);
        Objects.requireNonNull(display);
        fallbackInputs = List.copyOf(fallbackInputs);
        if (fallbackInputs.size() > 81) throw new IllegalArgumentException("Too many browser fallback inputs");
    }

    static BrowserRecipeEntry read(RegistryFriendlyByteBuf buffer) {
        Identifier recipe = buffer.readIdentifier();
        Identifier category = buffer.readIdentifier();
        RecipeDisplay display = RecipeDisplay.STREAM_CODEC.decode(buffer);
        int count = buffer.readVarInt();
        if (count < 0 || count > 81) throw new IllegalArgumentException("Invalid browser ingredient count");
        List<SlotDisplay> ingredients = new ArrayList<>(count);
        for (int index = 0; index < count; index++) ingredients.add(SlotDisplay.STREAM_CODEC.decode(buffer));
        return new BrowserRecipeEntry(recipe, category, display, ingredients, buffer.readBoolean());
    }

    void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeIdentifier(recipe);
        buffer.writeIdentifier(category);
        RecipeDisplay.STREAM_CODEC.encode(buffer, display);
        buffer.writeVarInt(fallbackInputs.size());
        for (SlotDisplay slot : fallbackInputs) SlotDisplay.STREAM_CODEC.encode(buffer, slot);
        buffer.writeBoolean(unlocked);
    }
}
