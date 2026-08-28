package com.kadamitas.fabricatedbackpacks.compat;

import com.mojang.serialization.Codec;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;

/** Immutable item value; callers never receive the stack stored by this template. */
public final class ItemStackTemplate {
    public static final Codec<ItemStackTemplate> CODEC = ItemStack.CODEC.xmap(ItemStackTemplate::fromNonEmptyStack, ItemStackTemplate::create);
    private final ItemStack stack;

    private ItemStackTemplate(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        if (stack.isEmpty()) throw new IllegalArgumentException("An item template cannot be empty");
        this.stack = stack.copy();
    }

    public static ItemStackTemplate fromNonEmptyStack(ItemStack stack) { return new ItemStackTemplate(stack); }
    public ItemStackTemplate withCount(int count) {
        if (count <= 0) throw new IllegalArgumentException("An item template must have a positive count");
        return count == stack.getCount() ? this : new ItemStackTemplate(stack.copyWithCount(count));
    }
    public ItemStack create() { return stack.copy(); }

    @Override public boolean equals(Object other) {
        return this == other || other instanceof ItemStackTemplate value && stack.getCount() == value.stack.getCount()
                && ItemStack.isSameItemSameComponents(stack, value.stack);
    }
    @Override public int hashCode() { return 31 * ItemStack.hashItemAndComponents(stack) + stack.getCount(); }
    @Override public String toString() { return stack.toString(); }
}
