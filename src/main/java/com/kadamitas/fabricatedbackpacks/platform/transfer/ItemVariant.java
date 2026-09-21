package com.kadamitas.fabricatedbackpacks.platform.transfer;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;

public record ItemVariant(Item getItem, DataComponentPatch getComponentsPatch) implements TransferVariant<Item> {
    public static final Codec<ItemVariant> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            BuiltInRegistries.ITEM.byNameCodec().fieldOf("item").forGetter(ItemVariant::getItem),
            DataComponentPatch.CODEC.optionalFieldOf("components", DataComponentPatch.EMPTY).forGetter(ItemVariant::getComponentsPatch)
    ).apply(instance, ItemVariant::of));
    public static ItemVariant blank() { return new ItemVariant(Items.AIR, DataComponentPatch.EMPTY); }
    public static ItemVariant of(ItemStack item) { return item.isEmpty() ? blank() : of(item.getItem(), item.getComponentsPatch()); }
    public static ItemVariant of(ItemLike item) { return of(new ItemStack(item)); }
    public static ItemVariant of(Item item, DataComponentPatch patch) { return new ItemVariant(item, patch); }
    public ItemStack toStack() { return toStack(1); }
    public ItemStack toStack(int amount) { if (isBlank() || amount == 0) return ItemStack.EMPTY; var stack = new ItemStack(getItem, amount); stack.applyComponents(getComponentsPatch); return stack; }
    public boolean matches(ItemStack stack) { return equals(of(stack)); }
    @Override public Item getObject() { return getItem; }
    @Override public DataComponentMap getComponents() { return toStack().getComponents(); }
    @Override public boolean isBlank() { return getItem == Items.AIR; }
}
