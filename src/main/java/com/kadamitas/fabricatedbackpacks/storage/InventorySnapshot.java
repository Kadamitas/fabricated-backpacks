package com.kadamitas.fabricatedbackpacks.storage;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Sparse, immutable inventory data. Counts are separate so enhanced stacks survive a save. */
public record InventorySnapshot(int size, List<Entry> entries) {
    public static final int MAX_SLOTS = 256;
    public static final InventorySnapshot EMPTY = new InventorySnapshot(0, List.of());
    public static final Codec<InventorySnapshot> CODEC = RecordCodecBuilder.<InventorySnapshot>create(instance ->
            instance.group(Codec.intRange(0, MAX_SLOTS).fieldOf("size").forGetter(InventorySnapshot::size),
                    Entry.CODEC.listOf(0, MAX_SLOTS).fieldOf("entries").forGetter(InventorySnapshot::entries))
                    .apply(instance, InventorySnapshot::new)).validate(InventorySnapshot::validate);

    public InventorySnapshot { entries = List.copyOf(entries); }

    private static DataResult<InventorySnapshot> validate(InventorySnapshot snapshot) {
        var occupied = new HashSet<Integer>();
        for (Entry entry : snapshot.entries) {
            if (entry.slot >= snapshot.size || !occupied.add(entry.slot)) {
                return DataResult.error(() -> "Inventory contains a duplicate or out-of-bounds slot");
            }
        }
        return DataResult.success(snapshot);
    }

    public static InventorySnapshot capture(Container container) {
        List<Entry> entries = new ArrayList<>();
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty()) entries.add(new Entry(slot,
                    ItemStackTemplate.fromNonEmptyStack(stack.copyWithCount(1)), stack.getCount()));
        }
        return new InventorySnapshot(container.getContainerSize(), entries);
    }

    public List<ItemStack> items() { return entries.stream().map(Entry::create).toList(); }

    public record Entry(int slot, ItemStackTemplate item, int count) {
        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.intRange(0, MAX_SLOTS - 1).fieldOf("slot").forGetter(Entry::slot),
                ItemStackTemplate.CODEC.fieldOf("item").forGetter(Entry::item),
                Codec.intRange(1, Integer.MAX_VALUE).fieldOf("count").forGetter(Entry::count))
                .apply(instance, Entry::new));
        public ItemStack create() {
            // Templates validate vanilla stack limits; the bag owns the enlarged count.
            ItemStack stack = item.withCount(1).create();
            stack.setCount(count);
            return stack;
        }
    }
}
