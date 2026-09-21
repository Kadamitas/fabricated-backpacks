package com.kadamitas.fabricatedbackpacks.platform.transfer;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.Capability;

public final class ItemLookup<T, N> {
    final Capability<N> capability;
    private final Function<N, T> wrap;
    final BiFunction<T, ContainerItemContext, N> unwrap;
    private BiFunction<ItemStack, ContainerItemContext, T> fallback;
    private final Map<Item, BiFunction<ItemStack, ContainerItemContext, T>> providers = new IdentityHashMap<>();
    public ItemLookup(Capability<N> capability, Function<N, T> wrap, Function<T, N> unwrap) {
        this(capability, wrap, (value, context) -> unwrap.apply(value));
    }
    public ItemLookup(Capability<N> capability, Function<N, T> wrap, BiFunction<T, ContainerItemContext, N> unwrap) {
        this.capability = capability; this.wrap = wrap; this.unwrap = unwrap; NativeCapabilities.ITEMS.add(this);
    }
    public T find(ItemStack stack, ContainerItemContext context) {
        T local = local(stack, context);
        if (local != null) return local;
        if (fallback != null) return fallback.apply(stack, context);
        return stack.getCapability(capability).map(value -> wrap.apply(value)).orElse(null);
    }
    T local(ItemStack stack, ContainerItemContext context) { var provider = providers.get(stack.getItem()); return provider == null ? null : provider.apply(stack, context); }
    N exported(ItemStack stack, ContainerItemContext context) { T value = local(stack, context); return value == null ? null : unwrap.apply(value, context); }
    public void setFallback(BiFunction<ItemStack, ContainerItemContext, T> fallback) { this.fallback = fallback; }
    public void registerForItems(BiFunction<ItemStack, ContainerItemContext, T> provider, Item... items) {
        for (Item item : items) {
            var previous = providers.putIfAbsent(item, provider);
            if (previous != null) providers.put(item, (stack, context) -> { T result = previous.apply(stack, context); return result != null ? result : provider.apply(stack, context); });
        }
    }
}
