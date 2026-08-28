package com.kadamitas.fabricatedbackpacks.admin;

import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** A command author's virtual draft. Item requests are replayed after all upgrades are installed. */
public final class DynamicBackpackBuilder {
    public static final int AUTO = -1;
    private static final int MAX_REQUESTS = 256;
    private final BagInventory base;
    private final List<Unplaced> requests = new ArrayList<>();

    public record Unplaced(int slot, ItemStack stack) {
        public Unplaced {
            if (slot < AUTO || stack.isEmpty()) throw new IllegalArgumentException("Invalid item placement request");
            stack = stack.copy();
        }
        @Override public ItemStack stack() { return stack.copy(); }
    }
    public record Result(ItemStack backpack, List<Unplaced> leftovers) {
        public Result { backpack = backpack.copy(); leftovers = List.copyOf(leftovers); }
        @Override public ItemStack backpack() { return backpack.copy(); }
        public long leftoverCount() { return leftovers.stream().mapToLong(value -> value.stack.getCount()).sum(); }
    }

    public DynamicBackpackBuilder(ItemStack base) {
        this.base = BagInventory.of(WholeBagTemplate.capture(Objects.requireNonNull(base)).instantiate());
    }
    public int slots() { return base.getContainerSize(); }
    public int requestCount() { return requests.size(); }
    public void addItem(ItemStack supplied, int slot) {
        if (supplied.isEmpty() || supplied.getCount() < 1 || slot < AUTO || slot >= slots()) throw new IllegalArgumentException("Invalid item count or target slot");
        if (requests.size() >= MAX_REQUESTS) throw new IllegalArgumentException("A dynamic draft supports at most 256 item requests");
        requests.add(new Unplaced(slot, supplied));
    }
    public int addUpgrade(ItemStack supplied, ServerPlayer author) {
        if (supplied.getCount() != 1) throw new IllegalArgumentException("Add exactly one upgrade at a time");
        for (int slot = 0; slot < base.upgrades().getContainerSize(); slot++) {
            if (!base.upgrades().getItem(slot).isEmpty() || !base.canInstall(slot, supplied, author)) continue;
            base.upgrades().setItem(slot, supplied.copy());
            return slot;
        }
        throw new IllegalArgumentException("No compatible upgrade slot; existing upgrades and capacity are preserved");
    }
    public Result build(ServerPlayer author) {
        BagInventory result = BagInventory.of(base.stack().copy());
        List<Unplaced> leftovers = new ArrayList<>();
        // Explicit positions have priority. Deferred automatic requests fill the remaining space.
        for (Unplaced request : requests) if (request.slot != AUTO) place(result, request, author, leftovers);
        for (Unplaced request : requests) if (request.slot == AUTO) place(result, request, author, leftovers);
        return new Result(result.stack(), leftovers);
    }
    private static void place(BagInventory bag, Unplaced request, ServerPlayer author, List<Unplaced> leftovers) {
        ItemStack remainder = request.stack();
        if (request.slot == AUTO) remainder = bag.insert(remainder, false, author);
        else if (bag.canPlaceItem(request.slot, remainder, author)) {
            ItemStack existing = bag.getItem(request.slot);
            if (existing.isEmpty() || ItemStack.isSameItemSameComponents(existing, remainder)) {
                int room = Math.max(0, bag.capacity(remainder) - existing.getCount());
                int moved = Math.min(room, remainder.getCount());
                if (moved > 0) {
                    bag.setItem(request.slot, remainder.copyWithCount(existing.getCount() + moved));
                    remainder.shrink(moved);
                }
            }
        }
        if (!remainder.isEmpty()) leftovers.add(new Unplaced(request.slot, remainder));
    }
}
