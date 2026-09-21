package com.kadamitas.fabricatedbackpacks.platform.transfer;

import com.kadamitas.fabricatedbackpacks.platform.neoforge.EnergyHandler;
import com.kadamitas.fabricatedbackpacks.platform.neoforge.ResourceHandler;
import com.kadamitas.fabricatedbackpacks.platform.transaction.SnapshotJournal;
import com.kadamitas.fabricatedbackpacks.platform.transaction.Transaction;
import com.kadamitas.fabricatedbackpacks.platform.transaction.TransactionContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;
import net.minecraftforge.items.IItemHandler;

/** Forge's simulation APIs are staged until root commit. Aborts never invoke an external mutator. */
public final class ForgeResourceAdapters {
    private static final Map<IItemHandler, ResourceHandler<ItemVariant>> ITEMS = new WeakHashMap<>();
    private static final Map<IFluidHandler, ResourceHandler<FluidVariant>> FLUIDS = new WeakHashMap<>();
    private static final Map<IEnergyStorage, EnergyHandler> ENERGY = new WeakHashMap<>();
    private ForgeResourceAdapters() {}
    public static ResourceHandler<ItemVariant> items(IItemHandler handler) { return handler instanceof ItemExport exported ? exported.storage : ITEMS.computeIfAbsent(handler, ItemImport::new); }
    public static IItemHandler items(ResourceHandler<ItemVariant> handler) { return items(handler, () -> false); }
    public static IItemHandler items(ResourceHandler<ItemVariant> handler, java.util.function.BooleanSupplier admission) { return new ItemExport(handler, admission); }
    public static ResourceHandler<FluidVariant> fluids(IFluidHandler handler) { return handler instanceof FluidExport exported ? exported.storage : FLUIDS.computeIfAbsent(handler, FluidImport::new); }
    public static IFluidHandler fluids(ResourceHandler<FluidVariant> handler) { return new FluidExport(handler); }
    public static IFluidHandlerItem fluidItem(ResourceHandler<FluidVariant> handler, ContainerItemContext context) {
        return new FluidExport(handler).implementsItem(context);
    }
    public static EnergyHandler energy(IEnergyStorage handler) { return handler instanceof EnergyHandler own ? own : ENERGY.computeIfAbsent(handler, EnergyImport::new); }
    static FluidVariant variant(FluidStack stack) {
        if (stack.isEmpty()) return FluidVariant.blank();
        if (stack.hasTag() && stack.getTag().contains("fabricated_backpacks_components"))
            return FluidVariant.of(stack.getFluid(), DataComponentPatch.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE,
                    stack.getTag().get("fabricated_backpacks_components")).getOrThrow());
        var patch = stack.hasTag() ? DataComponentPatch.builder().set(DataComponents.CUSTOM_DATA, CustomData.of(stack.getTag().copy())).build() : DataComponentPatch.EMPTY;
        return FluidVariant.of(stack.getFluid(), patch);
    }
    public static FluidStack fluid(FluidVariant variant, int amount) {
        if (variant.isBlank() || amount == 0) return FluidStack.EMPTY;
        var data = variant.getComponents().get(DataComponents.CUSTOM_DATA);
        var tag = data == null ? new net.minecraft.nbt.CompoundTag() : data.copyTag();
        // Forge fluid identity is NBT-based. Preserve every component, not just
        // custom data, so filtered/named fluids remain distinct across the boundary.
        if (!variant.getComponentsPatch().isEmpty()) tag.put("fabricated_backpacks_components",
                DataComponentPatch.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, variant.getComponentsPatch()).getOrThrow());
        return tag.isEmpty() ? new FluidStack(variant.getFluid(), amount) : new FluidStack(variant.getFluid(), amount, tag);
    }
    private static int count(long amount) { return (int)Math.min(Integer.MAX_VALUE, Math.max(0, amount)); }
    private static IllegalStateException violated(String kind) { return new IllegalStateException("External Forge " + kind + " handler violated its simulation contract during commit"); }

    private static final class ItemExport implements IItemHandler {
        final ResourceHandler<ItemVariant> storage;
        final java.util.function.BooleanSupplier admission;
        ItemExport(ResourceHandler<ItemVariant> storage, java.util.function.BooleanSupplier admission) { this.storage = storage; this.admission = admission; }
        private boolean port(int slot) { return slot == storage.size() && admission.getAsBoolean(); }
        // Physical slots keep their original indices, contents, capacities and extraction behavior.
        // The optional final slot is an explicitly empty insertion-only port for aggregate void policy.
        @Override public int getSlots() { return storage.size() + (admission.getAsBoolean() ? 1 : 0); }
        @Override public ItemStack getStackInSlot(int slot) { return port(slot) ? ItemStack.EMPTY : storage.getResource(slot).toStack(count(storage.getAmountAsLong(slot))); }
        @Override public int getSlotLimit(int slot) { return port(slot) ? Integer.MAX_VALUE : count(storage.getCapacityAsLong(slot, storage.getResource(slot))); }
        @Override public boolean isItemValid(int slot, ItemStack stack) {
            if (stack.isEmpty()) return false;
            if (!port(slot)) return storage.isValid(slot, ItemVariant.of(stack));
            if (com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry.isBackpack(stack)) return false;
            try (var probe = Transaction.open(Transaction.current())) { return storage.insert(ItemVariant.of(stack), 1, probe) > 0; }
        }
        @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (stack.isEmpty()) return ItemStack.EMPTY;
            if (port(slot) && com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry.isBackpack(stack)) return stack;
            try (var tx = Transaction.open(Transaction.current())) {
                int moved = port(slot) ? storage.insert(ItemVariant.of(stack), stack.getCount(), tx)
                        : storage.insert(slot, ItemVariant.of(stack), stack.getCount(), tx);
                if (!simulate) tx.commit();
                return stack.copyWithCount(stack.getCount() - moved);
            }
        }
        @Override public ItemStack extractItem(int slot, int maximum, boolean simulate) {
            if (port(slot)) return ItemStack.EMPTY;
            var resource = storage.getResource(slot); if (resource.isBlank() || maximum <= 0) return ItemStack.EMPTY;
            try (var tx = Transaction.open(Transaction.current())) { int moved = storage.extract(slot, resource, maximum, tx); if (!simulate) tx.commit(); return resource.toStack(moved); }
        }
    }
    private static class FluidExport implements IFluidHandler {
        final ResourceHandler<FluidVariant> storage;
        FluidExport(ResourceHandler<FluidVariant> storage) { this.storage = storage; }
        IFluidHandlerItem implementsItem(ContainerItemContext context) {
            return new IFluidHandlerItem() {
                @Override public ItemStack getContainer() { return context.getItemVariant().toStack(count(context.getMainSlot().getAmount())); }
                @Override public int getTanks() { return FluidExport.this.getTanks(); }
                @Override public FluidStack getFluidInTank(int tank) { return FluidExport.this.getFluidInTank(tank); }
                @Override public int getTankCapacity(int tank) { return FluidExport.this.getTankCapacity(tank); }
                @Override public boolean isFluidValid(int tank, FluidStack stack) { return FluidExport.this.isFluidValid(tank, stack); }
                @Override public int fill(FluidStack stack, FluidAction action) { return FluidExport.this.fill(stack, action); }
                @Override public FluidStack drain(FluidStack stack, FluidAction action) { return FluidExport.this.drain(stack, action); }
                @Override public FluidStack drain(int amount, FluidAction action) { return FluidExport.this.drain(amount, action); }
            };
        }
        @Override public int getTanks() { return storage.size(); }
        @Override public FluidStack getFluidInTank(int tank) { return fluid(storage.getResource(tank), count(storage.getAmountAsLong(tank))); }
        @Override public int getTankCapacity(int tank) { return count(storage.getCapacityAsLong(tank, storage.getResource(tank))); }
        @Override public boolean isFluidValid(int tank, FluidStack stack) { return !stack.isEmpty() && storage.isValid(tank, variant(stack)); }
        @Override public int fill(FluidStack stack, FluidAction action) {
            if (stack.isEmpty()) return 0;
            try (var tx = Transaction.open(Transaction.current())) { int moved = storage.insert(variant(stack), stack.getAmount(), tx); if (action.execute()) tx.commit(); return moved; }
        }
        @Override public FluidStack drain(FluidStack stack, FluidAction action) {
            if (stack.isEmpty()) return FluidStack.EMPTY;
            try (var tx = Transaction.open(Transaction.current())) { int moved = storage.extract(variant(stack), stack.getAmount(), tx); if (action.execute()) tx.commit(); return fluid(variant(stack), moved); }
        }
        @Override public FluidStack drain(int maximum, FluidAction action) {
            if (maximum <= 0) return FluidStack.EMPTY;
            for (int slot = 0; slot < storage.size(); slot++) { var resource = storage.getResource(slot); if (!resource.isBlank()) { var result = drain(fluid(resource, maximum), action); if (!result.isEmpty()) return result; } }
            return FluidStack.EMPTY;
        }
    }
    private static final class ItemImport extends SnapshotJournal<List<ItemStack>> implements ResourceHandler<ItemVariant> {
        final IItemHandler handler;
        List<ItemStack> original, pending;
        ItemImport(IItemHandler handler) { this.handler = handler; }
        private List<ItemStack> read() { List<ItemStack> result = new ArrayList<>(); for (int slot = 0; slot < size(); slot++) result.add(handler.getStackInSlot(slot).copy()); return result; }
        private ItemStack stack(int slot) { return pending == null ? handler.getStackInSlot(slot) : pending.get(slot); }
        private void enlist(TransactionContext tx) { updateSnapshots(tx); if (pending == null) { original = read(); pending = original.stream().map(ItemStack::copy).collect(java.util.stream.Collectors.toCollection(ArrayList::new)); } }
        @Override protected List<ItemStack> createSnapshot() { return pending == null ? null : pending.stream().map(ItemStack::copy).toList(); }
        @Override protected void revertToSnapshot(List<ItemStack> snapshot) { pending = snapshot == null ? null : new ArrayList<>(snapshot); if (pending == null) original = null; }
        @Override protected void prepareRootCommit(List<ItemStack> ignored) { for (int slot = 0; slot < size(); slot++) if (!ItemStack.matches(original.get(slot), handler.getStackInSlot(slot))) throw new IllegalStateException("Forge item endpoint changed before commit"); }
        @Override protected void onRootCommit(List<ItemStack> ignored) {
            try {
                for (int slot = 0; slot < size(); slot++) {
                    var before = original.get(slot); var after = pending.get(slot);
                    boolean same = ItemVariant.of(before).equals(ItemVariant.of(after));
                    int remove = before.getCount() - (same ? after.getCount() : 0);
                    if (remove > 0 && handler.extractItem(slot, remove, false).getCount() != remove) throw violated("item");
                    int add = after.getCount() - (same ? before.getCount() : 0);
                    if (add > 0 && !handler.insertItem(slot, after.copyWithCount(add), false).isEmpty()) throw violated("item");
                }
            } finally { pending = null; original = null; }
        }
        @Override public int size() { return handler.getSlots(); }
        @Override public ItemVariant getResource(int slot) { return ItemVariant.of(stack(slot)); }
        @Override public long getAmountAsLong(int slot) { return stack(slot).getCount(); }
        @Override public long getCapacityAsLong(int slot, ItemVariant resource) { return handler.getSlotLimit(slot); }
        @Override public boolean isValid(int slot, ItemVariant resource) { return handler.isItemValid(slot, resource.toStack()); }
        @Override public int insert(int slot, ItemVariant resource, int maximum, TransactionContext tx) {
            StoragePreconditions.notBlankNotNegative(resource, maximum); var current = stack(slot);
            if (!current.isEmpty() && !resource.matches(current) || !isValid(slot, resource)) return 0;
            int available = Math.min(handler.getSlotLimit(slot), resource.toStack().getMaxStackSize()) - current.getCount();
            int amount = Math.min(maximum, Math.max(0, available));
            // Query the external rule even when a previous staged extraction freed space.
            if (pending == null || ItemVariant.of(handler.getStackInSlot(slot)).equals(resource) || handler.getStackInSlot(slot).isEmpty()) {
                int simulated = amount - handler.insertItem(slot, resource.toStack(amount), true).getCount();
                int reserved = current.getCount() - handler.getStackInSlot(slot).getCount();
                amount = Math.min(amount, Math.max(0, simulated - reserved));
            }
            if (amount > 0) { enlist(tx); pending.set(slot, resource.toStack(current.getCount() + amount)); }
            return amount;
        }
        @Override public int extract(int slot, ItemVariant resource, int maximum, TransactionContext tx) {
            StoragePreconditions.notBlankNotNegative(resource, maximum); var current = stack(slot); if (!resource.matches(current)) return 0;
            int amount = Math.min(maximum, current.getCount());
            int already = handler.getStackInSlot(slot).getCount() - current.getCount();
            amount = Math.min(amount, Math.max(0, handler.extractItem(slot, Math.min(Integer.MAX_VALUE - Math.max(0, already), amount) + Math.max(0, already), true).getCount() - Math.max(0, already)));
            if (amount > 0) { enlist(tx); pending.set(slot, current.copyWithCount(current.getCount() - amount)); }
            return amount;
        }
    }
    private static final class FluidImport extends SnapshotJournal<List<FluidStack>> implements ResourceHandler<FluidVariant> {
        final IFluidHandler handler;
        List<FluidStack> original, pending;
        FluidImport(IFluidHandler handler) { this.handler = handler; }
        private FluidStack stack(int slot) { return pending == null ? handler.getFluidInTank(slot) : pending.get(slot); }
        private void enlist(TransactionContext tx) { updateSnapshots(tx); if (pending == null) { original = new ArrayList<>(); for (int slot = 0; slot < size(); slot++) original.add(handler.getFluidInTank(slot).copy()); pending = original.stream().map(FluidStack::copy).collect(java.util.stream.Collectors.toCollection(ArrayList::new)); } }
        @Override protected List<FluidStack> createSnapshot() { return pending == null ? null : pending.stream().map(FluidStack::copy).toList(); }
        @Override protected void revertToSnapshot(List<FluidStack> snapshot) { pending = snapshot == null ? null : new ArrayList<>(snapshot); if (pending == null) original = null; }
        @Override protected void prepareRootCommit(List<FluidStack> ignored) { for (int slot = 0; slot < size(); slot++) { var current = handler.getFluidInTank(slot); if (current.getAmount() != original.get(slot).getAmount() || !variant(current).equals(variant(original.get(slot)))) throw new IllegalStateException("Forge fluid endpoint changed before commit"); } }
        @Override protected void onRootCommit(List<FluidStack> ignored) {
            try {
                java.util.Map<FluidVariant, Long> deltas = new java.util.LinkedHashMap<>();
                for (int slot = 0; slot < size(); slot++) { var before = original.get(slot); var after = pending.get(slot); if (!before.isEmpty()) deltas.merge(variant(before), -(long)before.getAmount(), Long::sum); if (!after.isEmpty()) deltas.merge(variant(after), (long)after.getAmount(), Long::sum); }
                for (var entry : deltas.entrySet()) if (entry.getValue() < 0) drainAll(entry.getKey(), -entry.getValue());
                for (var entry : deltas.entrySet()) if (entry.getValue() > 0) fillAll(entry.getKey(), entry.getValue());
            } finally { original = null; pending = null; }
        }
        private void drainAll(FluidVariant resource, long remaining) { while (remaining > 0) { int n = count(remaining); if (handler.drain(fluid(resource, n), IFluidHandler.FluidAction.EXECUTE).getAmount() != n) throw violated("fluid"); remaining -= n; } }
        private void fillAll(FluidVariant resource, long remaining) { while (remaining > 0) { int n = count(remaining); if (handler.fill(fluid(resource, n), IFluidHandler.FluidAction.EXECUTE) != n) throw violated("fluid"); remaining -= n; } }
        @Override public int size() { return handler.getTanks(); }
        @Override public FluidVariant getResource(int slot) { return variant(stack(slot)); }
        @Override public long getAmountAsLong(int slot) { return stack(slot).getAmount(); }
        @Override public long getCapacityAsLong(int slot, FluidVariant resource) { return handler.getTankCapacity(slot); }
        @Override public boolean isValid(int slot, FluidVariant resource) { return handler.isFluidValid(slot, fluid(resource, 1)); }
        private int reserved(FluidVariant resource) { if (pending == null) return 0; long change = 0; for (int slot = 0; slot < size(); slot++) { if (variant(pending.get(slot)).equals(resource)) change += pending.get(slot).getAmount(); if (variant(original.get(slot)).equals(resource)) change -= original.get(slot).getAmount(); } return (int)Math.clamp(change, Integer.MIN_VALUE, Integer.MAX_VALUE); }
        @Override public int insert(int slot, FluidVariant resource, int maximum, TransactionContext tx) {
            StoragePreconditions.notBlankNotNegative(resource, maximum); var current = stack(slot);
            if (!current.isEmpty() && !variant(current).equals(resource) || !isValid(slot, resource)) return 0;
            int amount = Math.min(maximum, Math.max(0, handler.getTankCapacity(slot) - current.getAmount()));
            int simulated = handler.fill(fluid(resource, Integer.MAX_VALUE), IFluidHandler.FluidAction.SIMULATE);
            amount = Math.min(amount, count((long)simulated - reserved(resource)));
            if (amount > 0) { enlist(tx); pending.set(slot, fluid(resource, current.getAmount() + amount)); }
            return amount;
        }
        @Override public int extract(int slot, FluidVariant resource, int maximum, TransactionContext tx) {
            StoragePreconditions.notBlankNotNegative(resource, maximum); var current = stack(slot); if (!variant(current).equals(resource)) return 0;
            int simulated = handler.drain(fluid(resource, Integer.MAX_VALUE), IFluidHandler.FluidAction.SIMULATE).getAmount();
            int amount = Math.min(Math.min(maximum, current.getAmount()), count((long)simulated + reserved(resource)));
            if (amount > 0) { enlist(tx); pending.set(slot, fluid(resource, current.getAmount() - amount)); }
            return amount;
        }
    }
    private static final class EnergyImport extends SnapshotJournal<Long> implements EnergyHandler {
        final IEnergyStorage handler; long pending; Integer original;
        EnergyImport(IEnergyStorage handler) { this.handler = handler; }
        @Override protected Long createSnapshot() { return original == null ? null : pending; }
        @Override protected void revertToSnapshot(Long snapshot) { pending = snapshot == null ? 0 : snapshot; if (snapshot == null) original = null; }
        @Override protected void prepareRootCommit(Long ignored) { if (handler.getEnergyStored() != original) throw new IllegalStateException("Forge energy endpoint changed before commit"); }
        @Override protected void onRootCommit(Long ignored) { try { if (pending > 0 && handler.receiveEnergy(count(pending), false) != pending || pending < 0 && handler.extractEnergy(count(-pending), false) != -pending) throw violated("energy"); } finally { original = null; pending = 0; } }
        @Override public long getAmountAsLong() { return handler.getEnergyStored() + pending; }
        @Override public long getCapacityAsLong() { return handler.getMaxEnergyStored(); }
        @Override public boolean canReceive() { return handler.canReceive(); }
        @Override public boolean canExtract() { return handler.canExtract(); }
        @Override public int insert(int maximum, TransactionContext tx) { StoragePreconditions.notNegative(maximum); int amount = Math.min(maximum, count((long)handler.receiveEnergy(Integer.MAX_VALUE, true) - pending)); if (amount > 0) { updateSnapshots(tx); if (original == null) original = handler.getEnergyStored(); pending += amount; } return amount; }
        @Override public int extract(int maximum, TransactionContext tx) { StoragePreconditions.notNegative(maximum); int amount = Math.min(maximum, count((long)handler.extractEnergy(Integer.MAX_VALUE, true) + pending)); if (amount > 0) { updateSnapshots(tx); if (original == null) original = handler.getEnergyStored(); pending -= amount; } return amount; }
    }
}
