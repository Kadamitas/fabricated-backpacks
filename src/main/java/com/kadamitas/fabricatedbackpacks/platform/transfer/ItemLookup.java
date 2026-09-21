package com.kadamitas.fabricatedbackpacks.platform.transfer;

import java.util.function.BiFunction;
import java.util.function.Function;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.ItemCapability;
import net.neoforged.neoforge.transfer.access.ItemAccess;

public final class ItemLookup<T, N> {
    private final ItemCapability<N, ItemAccess> capability;
    private final Function<N, T> wrap;
    private final Function<T, N> unwrap;
    public ItemLookup(ItemCapability<N, ItemAccess> capability, Function<N, T> wrap, Function<T, N> unwrap) { this.capability = capability; this.wrap = wrap; this.unwrap = unwrap; }
    public T find(ItemStack stack, ContainerItemContext context) {
        N result = capability.getCapability(stack, context.nativeAccess());
        return result == null ? null : wrap.apply(result);
    }
    public void registerForItems(BiFunction<ItemStack, ContainerItemContext, T> provider, Item... items) {
        NativeCapabilities.add(event -> event.registerItem(capability, (stack, access) -> {
            T result = provider.apply(stack, ContainerItemContext.fromNative(access));
            return result == null ? null : unwrap.apply(result);
        }, items));
    }
}
