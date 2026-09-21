package com.kadamitas.fabricatedbackpacks.gametest.fixture;

import com.kadamitas.fabricatedbackpacks.platform.transfer.ContainerItemContext;
import com.kadamitas.fabricatedbackpacks.platform.transfer.EnergyStorage;
import com.kadamitas.fabricatedbackpacks.platform.transfer.ItemVariant;
import com.kadamitas.fabricatedbackpacks.platform.transfer.StoragePreconditions;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import com.kadamitas.fabricatedbackpacks.platform.transaction.TransactionContext;

public interface SimpleEnergyItem {
    DataComponentType<Long> ENERGY_COMPONENT = com.kadamitas.fabricatedbackpacks.platform.NativeRegistries.register(BuiltInRegistries.DATA_COMPONENT_TYPE,
            Identifier.fromNamespaceAndPath("fabricated_backpacks_tests", "fixture_energy"), DataComponentType.<Long>builder()
                    .persistent(com.mojang.serialization.Codec.LONG).networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.VAR_LONG).build());
    long getEnergyCapacity(ItemStack stack);
    long getEnergyMaxInput(ItemStack stack);
    long getEnergyMaxOutput(ItemStack stack);
    static void initialize() { }
    static EnergyStorage storage(SimpleEnergyItem item, ContainerItemContext context) {
        return new EnergyStorage() {
            private ItemStack stack() { return context.getItemVariant().toStack(); }
            @Override public long getAmount() { return stack().getOrDefault(ENERGY_COMPONENT, 0L); }
            @Override public long getCapacity() { return item.getEnergyCapacity(stack()); }
            private long change(long maximum, TransactionContext tx, boolean input) {
                StoragePreconditions.notNegative(maximum); var stack = stack();
                long amount = stack.getOrDefault(ENERGY_COMPONENT, 0L);
                long moved = Math.min(maximum, input ? Math.min(item.getEnergyMaxInput(stack), Math.max(0, getCapacity() - amount)) : Math.min(item.getEnergyMaxOutput(stack), amount));
                if (moved == 0) return 0;
                stack.set(ENERGY_COMPONENT, input ? amount + moved : amount - moved);
                return context.exchange(ItemVariant.of(stack), 1, tx) == 1 ? moved : 0;
            }
            @Override public long insert(long maximum, TransactionContext tx) { return change(maximum, tx, true); }
            @Override public long extract(long maximum, TransactionContext tx) { return change(maximum, tx, false); }
        };
    }
}
