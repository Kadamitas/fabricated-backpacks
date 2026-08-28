package com.kadamitas.fabricatedbackpacks.item;

import com.kadamitas.fabricatedbackpacks.storage.BagComponents;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.item.ItemStack;

/** Two independent RGB dyes; vanilla's integer custom-model-data remains unrelated. */
public record BackpackColors(int body, int trim) {
    public static final int DEFAULT_BODY = 0xB97843;
    public static final int DEFAULT_TRIM = 0x503B36;
    public static final Codec<BackpackColors> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.intRange(0, 0xFFFFFF).fieldOf("body").forGetter(BackpackColors::body),
            Codec.intRange(0, 0xFFFFFF).fieldOf("trim").forGetter(BackpackColors::trim)).apply(instance, BackpackColors::new));

    public BackpackColors {
        if (body < 0 || body > 0xFFFFFF || trim < 0 || trim > 0xFFFFFF) throw new IllegalArgumentException("Backpack dyes must be 24-bit RGB colors");
    }
    public static int color(ItemStack stack, int layer, int fallback) {
        BackpackColors colors = stack.get(BagComponents.COLORS);
        return colors == null ? fallback : layer == 0 ? colors.body : layer == 1 ? colors.trim : fallback;
    }
    public static void set(ItemStack stack, int body, int trim) { stack.set(BagComponents.COLORS, new BackpackColors(body, trim)); }
}
