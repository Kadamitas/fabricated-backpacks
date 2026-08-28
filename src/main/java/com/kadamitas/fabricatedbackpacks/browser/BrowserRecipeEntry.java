package com.kadamitas.fabricatedbackpacks.browser;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Bounded server-supplied presentation. Transfers always resolve the live server recipe. */
public record BrowserRecipeEntry(ResourceLocation recipe, ResourceLocation category, Layout layout,
                                 int columns, int rows, List<List<ItemStack>> ingredients,
                                 List<ItemStack> fuel, List<ItemStack> results, List<ItemStack> stations,
                                 int duration, float experience, boolean unlocked) {
    public enum Layout { CRAFTING, FURNACE, STONECUTTING, SMITHING, GENERIC }
    private static final int MAX_GROUPS = 81;
    static final int MAX_OPTIONS = 256;
    static final int MAX_FUEL_OPTIONS = 1_024;

    public BrowserRecipeEntry {
        Objects.requireNonNull(recipe);
        Objects.requireNonNull(category);
        Objects.requireNonNull(layout);
        if (columns < 1 || columns > 9 || rows < 1 || rows > 9
                || ingredients.size() > MAX_GROUPS || duration < 0 || !Float.isFinite(experience)) {
            throw new IllegalArgumentException("Invalid recipe presentation bounds");
        }
        ingredients = ingredients.stream().map(BrowserRecipeEntry::copyOptions).toList();
        fuel = copyOptions(fuel, MAX_FUEL_OPTIONS);
        results = copyOptions(results);
        stations = copyOptions(stations);
    }

    private static List<ItemStack> copyOptions(List<ItemStack> stacks) {
        return copyOptions(stacks, MAX_OPTIONS);
    }

    private static List<ItemStack> copyOptions(List<ItemStack> stacks, int limit) {
        if (stacks.size() > limit) throw new IllegalArgumentException("Too many recipe alternatives");
        return stacks.stream().map(ItemStack::copy).toList();
    }

    public BrowserRecipeEntry withUnlocked(boolean value) {
        return new BrowserRecipeEntry(recipe, category, layout, columns, rows, ingredients,
                fuel, results, stations, duration, experience, value);
    }

    static BrowserRecipeEntry read(RegistryFriendlyByteBuf buffer) {
        ResourceLocation recipe = buffer.readResourceLocation();
        ResourceLocation category = buffer.readResourceLocation();
        Layout layout = buffer.readEnum(Layout.class);
        int columns = buffer.readVarInt();
        int rows = buffer.readVarInt();
        int count = boundedCount(buffer, MAX_GROUPS);
        List<List<ItemStack>> ingredients = new ArrayList<>(count);
        for (int index = 0; index < count; index++) ingredients.add(readOptions(buffer));
        return new BrowserRecipeEntry(recipe, category, layout, columns, rows, ingredients,
                readOptions(buffer, MAX_FUEL_OPTIONS), readOptions(buffer), readOptions(buffer),
                buffer.readVarInt(), buffer.readFloat(), buffer.readBoolean());
    }

    private static List<ItemStack> readOptions(RegistryFriendlyByteBuf buffer) {
        return readOptions(buffer, MAX_OPTIONS);
    }

    private static List<ItemStack> readOptions(RegistryFriendlyByteBuf buffer, int limit) {
        int count = boundedCount(buffer, limit);
        List<ItemStack> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) result.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer));
        return result;
    }

    private static int boundedCount(RegistryFriendlyByteBuf buffer, int limit) {
        int count = buffer.readVarInt();
        if (count < 0 || count > limit) throw new IllegalArgumentException("Invalid recipe ingredient count");
        return count;
    }

    void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeResourceLocation(recipe);
        buffer.writeResourceLocation(category);
        buffer.writeEnum(layout);
        buffer.writeVarInt(columns);
        buffer.writeVarInt(rows);
        buffer.writeVarInt(ingredients.size());
        for (List<ItemStack> alternatives : ingredients) writeOptions(buffer, alternatives);
        writeOptions(buffer, fuel, MAX_FUEL_OPTIONS);
        writeOptions(buffer, results);
        writeOptions(buffer, stations);
        buffer.writeVarInt(duration);
        buffer.writeFloat(experience);
        buffer.writeBoolean(unlocked);
    }

    private static void writeOptions(RegistryFriendlyByteBuf buffer, List<ItemStack> stacks) {
        writeOptions(buffer, stacks, MAX_OPTIONS);
    }

    private static void writeOptions(RegistryFriendlyByteBuf buffer, List<ItemStack> stacks, int limit) {
        if (stacks.size() > limit) throw new IllegalArgumentException("Too many recipe alternatives");
        buffer.writeVarInt(stacks.size());
        for (ItemStack stack : stacks) ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, stack);
    }
}
