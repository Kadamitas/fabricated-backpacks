package com.kadamitas.fabricatedbackpacks.client.render;

import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import net.minecraft.world.item.ItemStack;

/** An immutable render snapshot; never retains the equipped mutable ItemStack. */
public record BackpackVisualState(boolean present, BackpackTier tier, int bodyColor, int trimColor) {
    public static final int DEFAULT_BODY_COLOR = 0xB97843;
    public static final int DEFAULT_TRIM_COLOR = 0x503B36;
    public static final BackpackVisualState EMPTY = new BackpackVisualState(false, BackpackTier.LEATHER,
            (0xFF000000 | DEFAULT_BODY_COLOR), (0xFF000000 | DEFAULT_TRIM_COLOR));

    public BackpackVisualState {
        if (tier == null) throw new IllegalArgumentException("A backpack visual tier is required");
        bodyColor = (0xFF000000 | bodyColor);
        trimColor = (0xFF000000 | trimColor);
    }

    public static BackpackVisualState from(ItemStack stack) {
        if (stack.isEmpty()) return EMPTY;
        return BackpackRegistry.tier(stack)
                .map(tier -> new BackpackVisualState(true, tier, color(stack, 0), color(stack, 1)))
                .orElse(EMPTY);
    }

    public static int color(ItemStack stack, int index) {
        if (index < 0 || index > 1) throw new IllegalArgumentException("Backpack tint index must be 0 or 1");
        return 0xFF000000 | com.kadamitas.fabricatedbackpacks.item.BackpackColors.color(stack, index,
                index == 0 ? DEFAULT_BODY_COLOR : DEFAULT_TRIM_COLOR);
    }
}
