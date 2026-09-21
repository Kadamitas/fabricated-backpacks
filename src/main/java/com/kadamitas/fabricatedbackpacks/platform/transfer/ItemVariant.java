package com.kadamitas.fabricatedbackpacks.platform.transfer;

import com.mojang.serialization.Codec;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;
import net.neoforged.neoforge.transfer.item.ItemResource;

public record ItemVariant(ItemResource nativeResource) implements TransferVariant<Item> {
    public static final Codec<ItemVariant> CODEC = ItemResource.OPTIONAL_CODEC.xmap(ItemVariant::new, ItemVariant::nativeResource);
    public static ItemVariant blank() { return new ItemVariant(ItemResource.EMPTY); }
    public static ItemVariant of(ItemStack item) { return new ItemVariant(ItemResource.of(item)); }
    public static ItemVariant of(ItemLike item) { return of(new ItemStack(item)); }
    public static ItemVariant of(Item item, DataComponentPatch patch) { return new ItemVariant(ItemResource.of(item, patch)); }
    public ItemStack toStack() { return toStack(1); }
    public ItemStack toStack(int amount) { return nativeResource.toStack(amount); }
    public boolean matches(ItemStack stack) { return equals(of(stack)); }
    public Item getItem() { return nativeResource.getItem(); }
    @Override public Item getObject() { return getItem(); }
    @Override public DataComponentMap getComponents() { return nativeResource.getComponents(); }
    @Override public DataComponentPatch getComponentsPatch() { return nativeResource.getComponentsPatch(); }
    @Override public boolean isBlank() { return nativeResource.isEmpty(); }
}
