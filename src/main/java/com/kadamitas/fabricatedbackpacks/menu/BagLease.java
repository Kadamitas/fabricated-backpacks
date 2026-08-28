package com.kadamitas.fabricatedbackpacks.menu;

import net.minecraft.world.item.ItemStack;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** A view retains its physical source; a UUID alone never grants access to a replacement item. */
public record BagLease(BooleanSupplier validity, Runnable save, Runnable release,
                       Predicate<ItemStack> locked, int nestedDepth) {
    public boolean valid() { return validity.getAsBoolean(); }
    public void persist() { if (valid()) save.run(); }
    public void close() { release.run(); }
    public boolean locks(ItemStack stack) { return !stack.isEmpty() && locked.test(stack); }
}
